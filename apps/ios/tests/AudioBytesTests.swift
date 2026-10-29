// This uses the production Kotlin/Native bridge, compiled for the Mac host.
for count in [0, 1, 960000, 8388608] {
    let expected = Data((0..<count).map { UInt8(truncatingIfNeeded: $0) })
    var bytes: KotlinByteArray!
    autoreleasepool {
        var source = expected
        bytes = AudioBytes.shared.fromData(data: source)
        source.resetBytes(in: 0..<source.count)
    }
    let actual = AudioBytes.shared.toData(bytes: bytes)
    bytes = nil
    precondition(actual == expected, "Bulk copy must own its bytes after the source is released")
}
let large = Data(repeating: 0x5a, count: 8388608)
let began = Date()
var bridgeFinished = false
DispatchQueue.global(qos: .userInitiated).async {
    for _ in 0..<20 {
        let bytes = AudioBytes.shared.fromData(data: large)
        precondition(AudioBytes.shared.toData(bytes: bytes) == large)
    }
    DispatchQueue.main.async { bridgeFinished = true }
}
var heartbeats = 0
while !bridgeFinished && Date().timeIntervalSince(began) < 10 {
    DispatchQueue.main.async { heartbeats += 1 }
    RunLoop.main.run(until: Date().addingTimeInterval(0.001))
}
precondition(bridgeFinished && heartbeats > 0, "Large bulk copies must leave main responsive")
print("20 native 8 MiB round trips: \(Date().timeIntervalSince(began))s; main heartbeats: \(heartbeats)")

// Exercise the production capture and playback paths with large buffers.
var clip: KotlinByteArray?
audio.record(false, { clip = $0 }, failure)
audio.synchronizeForTests()
TestAudioEngine.latest!.inputNode.tap!(buffer(Array(repeating: 0.25, count: 479000)), time)
audio.synchronizeForTests()
let finishBegan = Date()
audio.finish()
precondition(Date().timeIntervalSince(finishBegan) < 0.1)
drain()
precondition(clip?.size == 958000)
let pcm = AudioBytes.shared.toData(bytes: clip!)
precondition(pcm.prefix(4) == Data([0xff, 0x1f, 0xff, 0x1f]), "Capture must remain signed little-endian PCM")
var decodeFailed = false
let wav = AudioBytes.shared.fromData(data: large)
let playBegan = Date()
audio.play(wav, { preconditionFailure("Invalid WAV played") }, { _ in
    precondition(Thread.isMainThread)
    decodeFailed = true
})
precondition(Date().timeIntervalSince(playBegan) < 0.1)
drain()
precondition(decodeFailed, "Playback decode errors must reach main and clean up")
audio.stopPlayback()
drain()
print("Large capture finish and invalid playback return immediately and deliver callbacks on main")
