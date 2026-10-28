import AVFoundation
import Foundation
#if canImport(KotlinModules)
import KotlinModules
#endif

/** In-memory WAV recording and playback shared by iOS and the macOS speech helper. Main-thread callbacks. */
final class NativeVoiceAudio: NSObject, AVAudioPlayerDelegate {
    private let engine = AVAudioEngine()
    private let format = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: true)!
    private var converter: AVAudioConverter?
    private var pcm = Data()
    private var generation = UUID()
    private var timer: Timer?
    private var recorded: ((String) -> Void)?
    private var voiced = 0
    private var quiet = 0
    private var player: AVAudioPlayer?
    private var played: (() -> Void)?
    private var playError: ((String) -> Void)?

    func authorizeMicrophone(completion: @escaping (String?) -> Void) {
        #if os(iOS)
        AVAudioApplication.requestRecordPermission { granted in
            DispatchQueue.main.async { completion(granted ? nil : "Allow microphone access for Kinuvo in Settings") }
        }
        #else
        AVCaptureDevice.requestAccess(for: .audio) { granted in
            DispatchQueue.main.async { completion(granted ? nil : "Allow microphone access for Kinuvo Voice in System Settings") }
        }
        #endif
    }

    func record(untilPause: Bool, onRecorded: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
        stopRecording()
        do {
            #if os(iOS)
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothA2DP])
            try session.setActive(true)
            #endif
            let input = engine.inputNode
            let source = input.outputFormat(forBus: 0)
            guard source.sampleRate > 0, let converter = AVAudioConverter(from: source, to: format) else {
                onError("No microphone input is available"); return
            }
            self.converter = converter
            recorded = onRecorded
            let id = generation
            input.installTap(onBus: 0, bufferSize: 1024, format: source) { [weak self] buffer, _ in
                guard let self else { return }
                let capacity = AVAudioFrameCount(Double(buffer.frameLength) * 16000 / source.sampleRate) + 1
                guard let out = AVAudioPCMBuffer(pcmFormat: self.format, frameCapacity: capacity) else { return }
                var fed = false
                var error: NSError?
                converter.convert(to: out, error: &error) { _, status in
                    if fed { status.pointee = .noDataNow; return nil }
                    fed = true; status.pointee = .haveData; return buffer
                }
                guard error == nil, let samples = out.int16ChannelData?[0] else {
                    DispatchQueue.main.async { if self.generation == id { self.stopRecording(); onError("Cannot convert microphone audio") } }
                    return
                }
                let count = Int(out.frameLength)
                guard count > 0 else { return }
                let bytes = Data(bytes: samples, count: count * 2)
                let energy = (0..<count).reduce(0.0) { $0 + pow(Double(samples[$1]) / 32768, 2) }
                let audible = sqrt(energy / Double(count)) > 0.006
                DispatchQueue.main.async {
                    guard self.generation == id else { return }
                    self.pcm.append(bytes)
                    if audible { self.voiced += count; self.quiet = 0 } else { self.quiet += count }
                    if untilPause && self.voiced >= 1920 && self.quiet >= 20800 || self.pcm.count >= 960000 {
                        self.finishRecording()
                    }
                }
            }
            timer = Timer.scheduledTimer(withTimeInterval: 10, repeats: false) { [weak self] _ in
                guard let self, self.voiced < 1920 else { return }
                self.finishRecording()
            }
            engine.prepare()
            try engine.start()
        } catch { stopRecording(); onError("Cannot start microphone recording") }
    }

    func finishRecording() {
        guard let completion = recorded else { return }
        let bytes = pcm
        let audible = voiced >= 1920
        stopRecording()
        if !audible { completion(""); return }
        var wav = Data("RIFF".utf8)
        func number<T: FixedWidthInteger>(_ value: T) { var n = value.littleEndian; withUnsafeBytes(of: &n) { wav.append(contentsOf: $0) } }
        number(UInt32(bytes.count + 36)); wav.append(Data("WAVEfmt ".utf8))
        number(UInt32(16)); number(UInt16(1)); number(UInt16(1)); number(UInt32(16000))
        number(UInt32(32000)); number(UInt16(2)); number(UInt16(16))
        wav.append(Data("data".utf8)); number(UInt32(bytes.count)); wav.append(bytes)
        completion(wav.base64EncodedString())
    }

    func stopRecording() {
        let wasRecording = recorded != nil
        generation = UUID()
        timer?.invalidate(); timer = nil
        if recorded != nil { engine.stop(); engine.inputNode.removeTap(onBus: 0) }
        recorded = nil; pcm.removeAll(); voiced = 0; quiet = 0; converter = nil
        #if os(iOS)
        if wasRecording && player == nil { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
        #endif
    }

    func play(base64: String, onDone: @escaping () -> Void, onError: @escaping (String) -> Void) {
        stopPlayback()
        do {
            #if os(iOS)
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothA2DP])
            try session.setActive(true)
            #endif
            guard let bytes = Data(base64Encoded: base64) else { onError("Invalid speech audio"); return }
            let player = try AVAudioPlayer(data: bytes)
            self.player = player; played = onDone; playError = onError
            player.delegate = self
            guard player.play() else { stopPlayback(); onError("Cannot play speech audio"); return }
        } catch { stopPlayback(); onError("Cannot play speech audio") }
    }

    func stopPlayback() {
        let wasPlaying = player != nil
        player?.stop(); player = nil; played = nil; playError = nil
        #if os(iOS)
        if wasPlaying && recorded == nil { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
        #endif
    }
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        guard self.player === player else { return }
        let done = played; let error = playError
        stopPlayback()
        if flag { done?() } else { error?("Speech playback failed") }
    }
    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        guard self.player === player else { return }
        let failed = playError; stopPlayback(); failed?("Cannot decode speech audio")
    }
}

#if canImport(KotlinModules)
extension NativeVoiceAudio: VoiceAudio {}
#endif
