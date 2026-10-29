// Hardware-free lifecycle checks. Buffers and conversion still use AVFoundation;
// only the microphone engine, permissions and session are substituted.
enum TestAudioApplication {
    enum Permission { case granted }
    static let shared = Application()
    struct Application { let recordPermission = Permission.granted }
    static func requestRecordPermission(_ completion: (Bool) -> Void) { completion(true) }
}
enum TestAudioSession {
    static let interruptionNotification = Notification.Name("test.interruption")
    static let mediaServicesWereResetNotification = Notification.Name("test.reset")
}
final class TestAudioEngine {
    static weak var latest: TestAudioEngine?
    let inputNode = Input()
    var isRunning = false
    init() { Self.latest = self }
    func prepare() { precondition(!Thread.isMainThread) }
    static var startDelay: TimeInterval = 0
    func start() throws {
        precondition(!Thread.isMainThread)
        Thread.sleep(forTimeInterval: Self.startDelay)
        isRunning = true
    }
    func stop() { precondition(!Thread.isMainThread); isRunning = false }
    final class Input {
        var tap: AVAudioNodeTapBlock?
        func inputFormat(forBus: Int) -> AVAudioFormat { outputFormat(forBus: forBus) }
        func outputFormat(forBus: Int) -> AVAudioFormat { AVAudioFormat(standardFormatWithSampleRate: 16000, channels: 1)! }
        func installTap(onBus: Int, bufferSize: AVAudioFrameCount, format: AVAudioFormat?, block: @escaping AVAudioNodeTapBlock) {
            precondition(tap == nil, "Cannot install a second tap")
            precondition(format == nil, "Do not force the hardware format")
            tap = block
        }
        func removeTap(onBus: Int) { precondition(tap != nil); tap = nil }
    }
}

private let audio = AppleServerAudio()
var completed = 0
var failed = 0
let captured: (KotlinByteArray) -> Void = { _ in
    precondition(Thread.isMainThread)
    completed += 1
}
let failure: (String) -> Void = { _ in precondition(Thread.isMainThread); failed += 1 }
func drain() {
    audio.synchronizeForTests()
    RunLoop.main.run(until: Date().addingTimeInterval(0.03))
    audio.synchronizeForTests()
    RunLoop.main.run(until: Date().addingTimeInterval(0.03))
}
let input = buffer(Array(repeating: 0.25, count: 3200))
let time = AVAudioTime(sampleTime: 0, atRate: 16000)

for _ in 0..<10 {
    audio.record(false, captured, failure)
audio.synchronizeForTests()
    let old = TestAudioEngine.latest!
    let late = old.inputNode.tap!
    // Queue audio from the cancelled recording, then restart before it reaches main.
    late(input, time)
    audio.stop()
    audio.stop()
    audio.synchronizeForTests()
    precondition(!old.isRunning && old.inputNode.tap == nil)
    audio.record(false, captured, failure)
audio.synchronizeForTests()
    let fresh = TestAudioEngine.latest!
    precondition(fresh !== old && fresh.isRunning)
    late(input, time)
    drain()
    precondition(completed == 0 && failed == 0, "Cancelled callbacks reached the new recording")
    audio.stop()
    audio.synchronizeForTests()
}
weak var released: TestAudioEngine?
autoreleasepool {
    audio.record(false, captured, failure)
audio.synchronizeForTests()
    released = TestAudioEngine.latest
    audio.stop()
    audio.synchronizeForTests()
}
precondition(released == nil, "Cancellation retained the old I/O graph")
audio.record(false, captured, failure)
audio.synchronizeForTests()
TestAudioEngine.latest!.inputNode.tap!(input, time)
drain()
audio.finish()
audio.finish()
drain()
precondition(completed == 1 && failed == 0, "Finish must complete once")
audio.record(false, captured, failure)
audio.synchronizeForTests()
NotificationCenter.default.post(name: TestAudioSession.interruptionNotification, object: nil)
drain()
precondition(failed == 1 && TestAudioEngine.latest == nil)
audio.record(false, captured, failure)
audio.synchronizeForTests()
NotificationCenter.default.post(name: TestAudioSession.mediaServicesWereResetNotification, object: nil)
drain()
precondition(failed == 2 && TestAudioEngine.latest == nil)
print("Server audio start/cancel/restart and stale callback checks passed (substituted microphone engine)")

// Hardware setup can be slow; it must leave the main run loop free.
TestAudioEngine.startDelay = 0.25
let start = Date()
audio.record(false, captured, failure)
precondition(Date().timeIntervalSince(start) < 0.1)
var responsive = false
DispatchQueue.main.async { responsive = true }
RunLoop.main.run(until: Date().addingTimeInterval(0.05))
precondition(responsive, "Audio startup blocked the main queue")
audio.stop()
drain()
TestAudioEngine.startDelay = 0
// Missing tap callbacks must fail rather than silently return an empty transcript.
audio.record(false, captured, failure)
audio.synchronizeForTests()
audio.finish()
drain()
precondition(failed == 3)
print("Audio startup remains responsive; missing microphone buffers report a main-thread error")

TestAudioEngine.startDelay = 0.3
let failuresBeforeTimeout = failed
audio.record(false, captured, failure)
RunLoop.main.run(until: Date().addingTimeInterval(0.22))
precondition(failed == failuresBeforeTimeout + 1, "Startup watchdog must report while hardware work is still blocked")
drain()
TestAudioEngine.startDelay = 0
var releasedOnMain = false
audio.release {
    precondition(Thread.isMainThread && TestAudioEngine.latest == nil)
    releasedOnMain = true
}
drain()
precondition(releasedOnMain, "Device audio must wait until Server I/O graph teardown completes")
print("Startup timeout reports without waiting for hardware; graph release completion is ordered on main")
