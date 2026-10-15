import AVFoundation
import Speech
import os
import KotlinModules

/**
 * Voice chat on-device. SFSpeechRecognizer listens and AVSpeechSynthesizer speaks, both through one AVAudioEngine with
 * voice processing, so the microphone hears the user but not the reply (echo cancellation). Runs on the main thread.
 */
final class AppleSpeech: NSObject, SpeechEngine {
    private let recognizer = SFSpeechRecognizer()
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let synthesizer = AVSpeechSynthesizer()
    private let playback = AVAudioFormat(standardFormatWithSampleRate: 22050, channels: 1)!
    private var converter: AVAudioConverter?
    private var configured = false
    /** Between start and stop, so audio changes after voice chat ends don't turn the microphone back on. */
    private var active = false
    private let lock = NSLock()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var listening: UUID?
    private var pause: DispatchWorkItem?
    /** Bumped by stopSpeaking, so buffers of cancelled utterances aren't played. */
    private var generation = 0
    /** Completions of utterances not yet played; stopping speech completes them too. */
    private var unfinished = [UUID: () -> Void]()
    /** How long the user stays quiet before what they said is final. */
    private let endOfSpeech: TimeInterval = 1.3
    private let log = Logger(subsystem: "dev.kinuvo.app", category: "speech")

    func authorize(completion: @escaping (String?) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            guard status == .authorized else {
                DispatchQueue.main.async { completion("Allow Speech Recognition for Kinuvo in Settings") }
                return
            }
            AVAudioApplication.requestRecordPermission { granted in
                DispatchQueue.main.async { completion(granted ? nil : "Allow microphone access for Kinuvo in Settings") }
            }
        }
    }

    func listen(untilPause: Bool, onPartial: @escaping (String) -> Void, onFinal: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
        stopListening()
        guard let recognizer, recognizer.isAvailable else { onError("Speech recognition is unavailable"); return }
        guard recognizer.supportsOnDeviceRecognition else { onError("On-device speech recognition isn't available for \(recognizer.locale.identifier)"); return }
        do { try start() } catch {
            log.error("Couldn't start audio to listen: \(error.localizedDescription)")
            onError(error.localizedDescription)
            return
        }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.addsPunctuation = true
        request.requiresOnDeviceRecognition = true
        let id = UUID()
        listening = id
        lock.withLock { self.request = request }
        func finish(_ text: String) {
            stopListening()
            onFinal(text)
        }
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            DispatchQueue.main.async {
                guard let self, self.listening == id else { return }
                if let result {
                    let text = result.bestTranscription.formattedString
                    onPartial(text)
                    if result.isFinal { finish(text); return }
                    guard untilPause else { return }
                    self.pause?.cancel()
                    let pause = DispatchWorkItem { if self.listening == id { finish(text) } }
                    self.pause = pause
                    DispatchQueue.main.asyncAfter(deadline: .now() + self.endOfSpeech, execute: pause)
                } else if let error {
                    self.stopListening()
                    onError(error.localizedDescription)
                }
            }
        }
    }

    func speak(text: String, onDone: @escaping () -> Void) {
        do { try start() } catch {
            log.error("Couldn't start audio to speak: \(error.localizedDescription)")
            onDone()
            return
        }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voice
        let generation = self.generation
        let id = UUID()
        unfinished[id] = onDone
        // The synthesizer ends an utterance with an empty buffer, sometimes more than one.
        var ended = false
        synthesizer.write(utterance) { [weak self] buffer in
            DispatchQueue.main.async {
                guard let self, self.generation == generation, !ended, let buffer = buffer as? AVAudioPCMBuffer else { return }
                if buffer.frameLength == 0 {
                    ended = true
                    // The last buffer is empty; a frame of silence after the speech reports when it has played.
                    let end = AVAudioPCMBuffer(pcmFormat: self.playback, frameCapacity: 1)!
                    end.frameLength = 1
                    end.floatChannelData?[0][0] = 0
                    self.player.scheduleBuffer(end, completionCallbackType: .dataPlayedBack) { _ in
                        DispatchQueue.main.async { self.unfinished.removeValue(forKey: id)?() }
                    }
                } else if let converted = self.convert(buffer) {
                    self.player.scheduleBuffer(converted)
                } else {
                    self.log.error("Couldn't convert speech from \(buffer.format)")
                }
                if !self.player.isPlaying { self.player.play() }
            }
        }
    }

    func stopSpeaking() {
        generation += 1
        synthesizer.stopSpeaking(at: .immediate)
        player.stop()
        let stopped = unfinished.values
        unfinished.removeAll()
        stopped.forEach { $0() }
    }

    func stop() {
        active = false
        stopListening()
        stopSpeaking()
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    func stopListening() {
        listening = nil
        pause?.cancel()
        pause = nil
        lock.withLock {
            request?.endAudio()
            request = nil
        }
        task?.cancel()
        task = nil
    }

    private func start() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothA2DP])
        try session.setActive(true)
        active = true
        guard !engine.isRunning else { return }
        if !configured {
            do { try engine.inputNode.setVoiceProcessingEnabled(true) } catch { log.error("No echo cancellation: \(error.localizedDescription)") }
            engine.attach(player)
            engine.connect(player, to: engine.mainMixerNode, format: playback)
            installTap()
            // Route and session changes stop the engine; it's started again for what's still to say.
            NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
                guard let self, self.active, !self.engine.isRunning else { return }
                // The microphone's format may have changed with the route.
                self.engine.inputNode.removeTap(onBus: 0)
                self.installTap()
                do {
                    self.engine.prepare()
                    try self.engine.start()
                    self.player.play()
                } catch { self.log.error("Couldn't restart audio: \(error.localizedDescription)") }
            }
            configured = true
        }
        engine.prepare()
        try engine.start()
        // Voice processing moves output to the earpiece; speak from the speaker unless headphones are in.
        if session.currentRoute.outputs.contains(where: { $0.portType == .builtInReceiver }) { try session.overrideOutputAudioPort(.speaker) }
    }

    private func installTap() {
        let input = engine.inputNode
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { [weak self] buffer, _ in
            guard let self else { return }
            self.lock.withLock { self.request?.append(buffer) }
        }
    }

    /** [buffer] in the player's format; the synthesizer's depends on the voice. */
    private func convert(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        if buffer.format == playback { return buffer }
        if converter?.inputFormat != buffer.format { converter = AVAudioConverter(from: buffer.format, to: playback) }
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * playback.sampleRate / buffer.format.sampleRate) + 1
        guard let converter, let out = AVAudioPCMBuffer(pcmFormat: playback, frameCapacity: capacity) else { return nil }
        var fed = false
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            if fed { status.pointee = .noDataNow; return nil }
            fed = true
            status.pointee = .haveData
            return buffer
        }
        return error == nil ? out : nil
    }

    /** The best installed voice for the user's language; premium and enhanced ones are downloaded in Settings. */
    private lazy var voice: AVSpeechSynthesisVoice? = {
        let language = AVSpeechSynthesisVoice.currentLanguageCode()
        return AVSpeechSynthesisVoice.speechVoices().filter { $0.language == language }.max { $0.quality.rawValue < $1.quality.rawValue }
            ?? AVSpeechSynthesisVoice(language: language)
    }()
}
