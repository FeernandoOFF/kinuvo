import AppKit
import AVFoundation
import Darwin

final class LocalSpeech: NSObject, AVAudioPlayerDelegate {
    let output: (String, Int, String) -> Void
    init(emit: @escaping (String, Int, String) -> Void) { output = emit; super.init() }
    func emit(_ event: String, _ id: Int, _ text: String = "") { output(event, id, text) }
    let audio = AVAudioEngine()
    var listening = 0
    var samples = [Float]()
    var audible = 0
    var quiet = 0
    var tapped = false
    var recordingDeadline: DispatchWorkItem?
    var untilPause = true
    var recognition: [String: Any] = [:]
    var transcribingID = 0
    var transcribing: Process?
    var synthesizing: Process?
    var player: AVAudioPlayer?
    var playing = 0
    var queue = [(Int, String, [String: Any])]()
    var generation = 0

    func stopListening() {
        let previous = listening
        listening = 0
        recordingDeadline?.cancel(); recordingDeadline = nil
        audio.stop()
        if tapped { audio.inputNode.removeTap(onBus: 0); tapped = false }
        samples.removeAll(keepingCapacity: false)
        if let worker = transcribing { cancel(worker) }
        transcribing = nil
        if transcribingID != 0 { self.emit("released", transcribingID); transcribingID = 0 }
        if previous != 0 { self.emit("released", previous) }
    }
    func listen(_ id: Int, _ command: [String: Any]) {
        stopListening()
        listening = id; audible = 0; quiet = 0
        recognition = command; untilPause = command["untilPause"] as? Bool ?? true
        let input = audio.inputNode
        guard let source = input.microphoneFormat,
              let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000, channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: source, to: target) else {
            stopListening(); self.emit("error", id, "No microphone input. Choose a microphone in System Settings → Sound."); return
        }
        input.installTap(onBus: 0, bufferSize: 2048, format: source) { buffer, _ in
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * 16000 / source.sampleRate) + 32
            guard let converted = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return }
            var supplied = false
            var error: NSError?
            converter.convert(to: converted, error: &error) { _, status in
                if supplied { status.pointee = .noDataNow; return nil }
                supplied = true; status.pointee = .haveData; return buffer
            }
            guard error == nil, let data = converted.floatChannelData?[0] else { return }
            let chunk = Array(UnsafeBufferPointer(start: data, count: Int(converted.frameLength)))
            DispatchQueue.main.async {
                guard self.listening == id else { return }
                self.samples.append(contentsOf: chunk)
                let power = chunk.reduce(Float(0)) { $0 + $1 * $1 } / Float(max(1, chunk.count))
                if power > 0.000064 { self.audible += chunk.count; self.quiet = 0 }
                else { self.quiet += chunk.count }
                if self.samples.count >= 480000 || (self.untilPause && self.audible >= 2400 && self.quiet >= 20800) || (self.audible == 0 && self.samples.count >= 160000) {
                    self.finish()
                }
            }
        }
        tapped = true
        do {
            audio.prepare(); try audio.start()
            let deadline = DispatchWorkItem { if self.listening == id { self.finish() } }
            recordingDeadline = deadline
            DispatchQueue.main.asyncAfter(deadline: .now() + 30, execute: deadline)
        }
        catch { stopListening(); self.emit("error", id, error.localizedDescription) }
    }
    func cancel(_ worker: Process) {
        if worker.isRunning { kill(worker.processIdentifier, SIGKILL) }
        worker.waitUntilExit()
    }
    func worker(_ operation: String, _ command: [String: Any], _ input: Data,
                _ completion: @escaping (Process, Data?, String?) -> Void) -> Process? {
        guard let directory = command["modelDir"] as? String else {
            self.emit("error", command["id"] as? Int ?? 0, "Local model directory is missing"); return nil
        }
        let worker = Process()
        worker.executableURL = URL(fileURLWithPath: command["workerPath"] as? String ?? "")
        worker.arguments = [operation, directory, command["language"] as? String ?? "en"]
        if operation == "transcribe" { worker.arguments?.append(command["variant"] as? String ?? "tiny") }
        let incoming = Pipe(), outgoing = Pipe()
        worker.standardInput = incoming; worker.standardOutput = outgoing
        worker.standardError = FileHandle.standardError
        do { try worker.run() }
        catch { self.emit("error", command["id"] as? Int ?? 0, "Cannot launch local inference: \(error.localizedDescription)"); return nil }
        DispatchQueue.global().async {
            do { try incoming.fileHandleForWriting.write(contentsOf: input) } catch { }
            try? incoming.fileHandleForWriting.close()
        }
        DispatchQueue.global().async {
            var output = Data()
            var tooLarge = false
            while true {
                let chunk = outgoing.fileHandleForReading.readData(ofLength: 65536)
                if chunk.isEmpty { break }
                output.append(chunk)
                if output.count > 16 * 1024 * 1024 { tooLarge = true; kill(worker.processIdentifier, SIGKILL); break }
            }
            worker.waitUntilExit()
            let error = tooLarge ? "Local speech output exceeds its limit" : (worker.terminationStatus == 0 ? nil : "Local inference failed (\(worker.terminationStatus)). Check the model download and language.")
            DispatchQueue.main.async { completion(worker, error == nil ? output : nil, error) }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 120) {
            if worker.isRunning { kill(worker.processIdentifier, SIGKILL) }
        }
        return worker
    }
    func finish() {
        let id = listening
        guard id != 0 else { return }
        let captured = Array(samples.prefix(480000))
        let voiced = audible
        let command = recognition
        listening = 0
        stopListening()
        if voiced < 2400 { self.emit(command["rawAudio"] as? Bool == true ? "audio" : "final", id); return }
        if command["rawAudio"] as? Bool == true {
            var pcm = Data(capacity: captured.count * 2)
            for sample in captured {
                var value = Int16(max(-1, min(1, sample)) * 32767).littleEndian
                withUnsafeBytes(of: &value) { pcm.append(contentsOf: $0) }
            }
            self.emit("audio", id, pcm.base64EncodedString()); return
        }
        transcribingID = id
        transcribing = worker("transcribe", command, captured.withUnsafeBytes { Data($0) }) { process, result, error in
            guard self.transcribing === process else { return }
            self.transcribing = nil; self.transcribingID = 0
            if let error = error { self.emit("error", id, error); return }
            let text = result.flatMap { String(data: $0, encoding: .utf8) }?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let filtered = text.contains(where: { $0.isLetter || $0.isNumber }) ? text : ""
            self.emit("partial", id, filtered)
            self.emit("final", id, filtered)
        }
    }
    func playClip(_ id: Int, _ encoded: String) {
        stopSpeaking()
        guard let data = Data(base64Encoded: encoded), data.count <= 8388608 else { self.emit("error", id, "Invalid audio clip"); return }
        do {
            player = try AVAudioPlayer(data: data); player?.delegate = self; playing = id
            guard player?.play() == true else { throw NSError(domain: "Voice", code: 1) }
        } catch { playing = 0; self.emit("error", id, error.localizedDescription) }
    }
    func speak(_ id: Int, _ text: String, _ command: [String: Any]) {
        guard text.utf8.count <= 8192 else { self.emit("error", id, "Speech chunk is too long"); return }
        queue.append((id, text, command))
        next()
    }
    func next() {
        guard playing == 0, !queue.isEmpty else { return }
        let (id, text, command) = queue.removeFirst()
        playing = id
        let epoch = generation
        synthesizing = worker("synthesize", command, Data(text.utf8)) { process, result, error in
            guard self.generation == epoch, self.synthesizing === process else { return }
            self.synthesizing = nil
            if let error = error { self.playing = 0; self.emit("error", id, error); self.next(); return }
            do {
                self.player = try AVAudioPlayer(data: result ?? Data())
                self.player?.delegate = self
                guard self.player?.play() == true else { throw NSError(domain: "LocalSpeech", code: 1, userInfo: [NSLocalizedDescriptionKey: "Cannot play local speech"]) }
            } catch { self.player = nil; self.playing = 0; self.emit("error", id, error.localizedDescription); self.next() }
        }
        if synthesizing == nil { playing = 0; next() }
    }
    func stopSpeaking() {
        generation += 1
        if let worker = synthesizing { cancel(worker) }; synthesizing = nil
        player?.stop(); player = nil
        let ids = (playing == 0 ? [] : [playing]) + queue.map { $0.0 }
        playing = 0; queue.removeAll()
        ids.forEach { self.emit("done", $0) }
    }
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        guard self.player === player else { return }
        let id = playing; playing = 0; self.player = nil
        self.emit(flag ? "done" : "error", id, flag ? "" : "Local speech playback failed")
        next()
    }
    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        guard self.player === player else { return }
        let id = playing; playing = 0; self.player = nil
        self.emit("error", id, error?.localizedDescription ?? "Cannot decode local speech")
        next()
    }
}
