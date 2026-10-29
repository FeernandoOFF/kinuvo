import AVFoundation

func buffer(_ values: [Float], rate: Double = 16000) -> AVAudioPCMBuffer {
    let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1)!
    let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(max(1, values.count)))!
    buffer.frameLength = AVAudioFrameCount(values.count)
    for (index, value) in values.enumerated() { buffer.floatChannelData![0][index] = value }
    return buffer
}

var description = AudioStreamBasicDescription(
    mSampleRate: 48000, mFormatID: kAudioFormatLinearPCM, mFormatFlags: kAudioFormatFlagIsFloat,
    mBytesPerPacket: 0, mFramesPerPacket: 1, mBytesPerFrame: 0, mChannelsPerFrame: 0,
    mBitsPerChannel: 32, mReserved: 0)
let invalid = AVAudioFormat(streamDescription: &description)!
precondition(invalid.sampleRate > 0, "The former sample-rate-only guard accepted this format")
precondition(!ServerAudioPCM.valid(invalid), "Zero channels must be rejected before installing a tap")
precondition(!ServerAudioPCM.valid(AVAudioFormat(standardFormatWithSampleRate: 0, channels: 1)!))
precondition(ServerAudioPCM.valid(buffer([]).format))
let extremes = ServerAudioPCM.samples(buffer([-.infinity, .infinity, .nan, -2, -1, 0, 1, 2]))!
precondition(extremes.pcm == [0, 0, 0, -32767, -32767, 0, 32767, 32767])
precondition(extremes.power.isFinite && extremes.power == 0.5)
precondition(ServerAudioPCM.samples(buffer([]))!.power == 0)

// Exercise Apple's real converter at the common iPhone and Bluetooth input rates.
for rate in [8000.0, 16000, 44100, 48000] {
    let source = buffer(Array(repeating: 0.25, count: Int(rate / 10)), rate: rate)
    let target = buffer([]).format
    let converter = AVAudioConverter(from: source.format, to: target)!
    let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: 1632)!
    var supplied = false
    var error: NSError?
    let status = converter.convert(to: output, error: &error) { _, status in
        if supplied { status.pointee = .endOfStream; return nil }
        supplied = true
        status.pointee = .haveData
        return source
    }
    precondition(error == nil && status != .error && output.frameLength > 0)
    let result = ServerAudioPCM.samples(output)!
    precondition(result.pcm.count <= 1632 && result.power.isFinite && result.power > 0.01)
}
print("Server audio native PCM checks passed")
