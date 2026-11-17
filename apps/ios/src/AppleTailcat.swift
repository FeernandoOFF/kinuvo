import Foundation
import Network
import UIKit
import Tailcat
import KotlinModules

private let serverPort: UInt16 = 8787
private let connectionLimit = 128
private let streamTimeout: TimeInterval = 10
private let closeTimeout: TimeInterval = 5

/// Forwards loopback TCP ports to a Server's Tailcat port; each open owns its own tunnel.
final class AppleTailcat: NSObject, NativeTailcat {
    private let lock = NSLock()
    private var forwards: [Int32: TailcatLoopback] = [:]
    private var observer: NSObjectProtocol?

    override init() {
        super.init()
        observer = NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: nil) { [weak self] _ in
            self?.wake()
        }
    }

    func open(address: String, completion: @escaping (KotlinInt) -> Void) {
        Task {
            do {
                let forward = try await TailcatLoopback.start(address: address)
                let port = Int32(forward.port)
                self.lock.lock(); self.forwards[port] = forward; self.lock.unlock()
                completion(KotlinInt(int: port))
            } catch {
                completion(KotlinInt(int: 0))
            }
        }
    }

    func close(port: Int32, completion: @escaping () -> Void) {
        lock.lock(); let forward = forwards.removeValue(forKey: port); lock.unlock()
        guard let forward else { completion(); return }
        Task { await forward.close(); completion() }
    }

    private func wake() {
        lock.lock(); let current = Array(forwards.values); lock.unlock()
        for forward in current { Task { await forward.wake() } }
    }
}

private final class TailcatLoopback: @unchecked Sendable {
    private let client: TailcatClient
    private let listener: NWListener
    private let queue = DispatchQueue(label: "kinuvo.tailcat.loopback")
    private let lock = NSLock()
    private var connections: [ObjectIdentifier: NWConnection] = [:]
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]
    private var closed = false

    var port: UInt16 { listener.port?.rawValue ?? 0 }

    static func start(address: String) async throws -> TailcatLoopback {
        var configuration = TailcatClient.Configuration()
        configuration.connectTimeout = 20
        let client = try await TailcatClient.connect(to: address, configuration: configuration)
        do {
            let loopback = try TailcatLoopback(client: client)
            try await loopback.ready()
            return loopback
        } catch {
            await client.close()
            throw error
        }
    }

    private init(client: TailcatClient) throws {
        self.client = client
        let parameters = NWParameters.tcp
        parameters.requiredInterfaceType = .loopback
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: .any)
        listener = try NWListener(using: parameters)
        listener.newConnectionHandler = { [unowned self] connection in self.accept(connection) }
    }

    private func ready() async throws {
        let gate = Gate<Void>()
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            gate.set(continuation)
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready: gate.fire(.success(()))
                case .failed(let error): gate.fire(.failure(error))
                case .cancelled: gate.fire(.failure(TailcatError.closed))
                default: break
                }
            }
            listener.start(queue: queue)
        }
    }

    func wake() async { await client.wake() }

    func close() async {
        lock.lock()
        guard !closed else { lock.unlock(); return }
        closed = true
        let openConnections = Array(connections.values)
        let running = Array(tasks.values)
        connections.removeAll(); tasks.removeAll()
        lock.unlock()
        listener.cancel()
        openConnections.forEach { $0.cancel() }
        running.forEach { $0.cancel() }
        _ = try? await bounded(closeTimeout) { await self.client.close() }
    }

    private func accept(_ connection: NWConnection) {
        let id = ObjectIdentifier(connection)
        lock.lock()
        guard !closed, connections.count < connectionLimit else { lock.unlock(); connection.cancel(); return }
        connections[id] = connection
        lock.unlock()
        connection.start(queue: queue)
        let task = Task {
            await self.relay(connection)
            self.lock.lock(); self.connections[id] = nil; self.tasks[id] = nil; self.lock.unlock()
        }
        lock.lock()
        if connections[id] != nil { tasks[id] = task }
        lock.unlock()
    }

    private func relay(_ connection: NWConnection) async {
        let stream: TailcatConnection
        do { stream = try await bounded(streamTimeout) { try await self.client.openTCP(port: serverPort) } }
        catch { connection.cancel(); return }
        async let uploaded = upload(connection, to: stream)
        async let downloaded = download(stream, to: connection)
        let up = await uploaded
        let down = await downloaded
        let clean = up && down
        if clean { await stream.close() } else { await stream.abort() }
        connection.cancel()
    }

    private func upload(_ connection: NWConnection, to stream: TailcatConnection) async -> Bool {
        while true {
            guard let chunk = await receive(connection) else { connection.cancel(); return false }
            do {
                if !chunk.data.isEmpty { try await stream.write(chunk.data) }
                if chunk.last { try await stream.closeWrite(); return true }
            } catch { connection.cancel(); return false }
        }
    }

    private func download(_ stream: TailcatConnection, to connection: NWConnection) async -> Bool {
        while true {
            let data: Data
            do { data = try await stream.read() } catch { connection.cancel(); return false }
            if data.isEmpty { return await send(connection, nil) }
            guard await send(connection, data) else { connection.cancel(); return false }
        }
    }

    private func receive(_ connection: NWConnection) async -> (data: Data, last: Bool)? {
        await withCheckedContinuation { continuation in
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { data, _, complete, error in
                if error != nil { continuation.resume(returning: nil) }
                else if data != nil || complete { continuation.resume(returning: (data ?? Data(), complete)) }
                else { continuation.resume(returning: nil) }
            }
        }
    }

    private func send(_ connection: NWConnection, _ data: Data?) async -> Bool {
        await withCheckedContinuation { continuation in
            connection.send(content: data, contentContext: data == nil ? .finalMessage : .defaultMessage, isComplete: data == nil,
                            completion: .contentProcessed { continuation.resume(returning: $0 == nil) })
        }
    }
}

private final class Gate<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<T, Error>?

    func set(_ continuation: CheckedContinuation<T, Error>) {
        lock.lock(); self.continuation = continuation; lock.unlock()
    }

    func fire(_ result: Result<T, Error>) {
        lock.lock(); let pending = continuation; continuation = nil; lock.unlock()
        pending?.resume(with: result)
    }
}

private func bounded<T: Sendable>(_ seconds: TimeInterval, _ work: @escaping @Sendable () async throws -> T) async throws -> T {
    let gate = Gate<T>()
    return try await withCheckedThrowingContinuation { continuation in
        gate.set(continuation)
        let running = Task {
            do { gate.fire(.success(try await work())) } catch { gate.fire(.failure(error)) }
        }
        Task {
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            gate.fire(.failure(TailcatError.timedOut))
            running.cancel()
        }
    }
}
