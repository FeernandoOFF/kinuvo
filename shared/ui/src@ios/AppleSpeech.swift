import AVFoundation
import Speech
import os
import KotlinModules

/**
 * Voice chat on-device. SpeechAnalyzer listens and AVSpeechSynthesizer speaks, both through one AVAudioEngine with
 * voice processing, so the microphone hears the user but not the reply (echo cancellation). Runs on the main thread.
 */
final class AppleSpeech: NSObject, SpeechEngine {
    var halfDuplex: Bool { false }
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let synthesizer = AVSpeechSynthesizer()
    private let playback = AVAudioFormat(standardFormatWithSampleRate: 22050, channels: 1)!
    /** Converts and schedules speech off the main thread, which a streaming reply keeps busy: late buffers crackle. */
    private let audio = DispatchQueue(label: "dev.kinuvo.speech.audio", qos: .userInteractive)
    /** Only touched on [audio]. */
    private var converter: AVAudioConverter?
    private var configured = false
    /** Between start and stop, so audio changes after voice chat ends don't turn the microphone back on. */
    private var active = false
    private let lock = NSLock()
    /** The microphone's audio going to the analyzer, in [analyzerFormat]; set while listening, guarded by [lock]. */
    private var feed: AsyncStream<AnalyzerInput>.Continuation?
    private var analyzerFormat: AVAudioFormat?
    /** Only touched on the microphone's thread. */
    private var inputConverter: AVAudioConverter?
    private var analyzer: SpeechAnalyzer?
    private var transcribing: Task<Void, Never>?
    private var listening: UUID?
    private var pause: DispatchWorkItem?
    /** Bumped by stopSpeaking, so buffers of cancelled utterances aren't played; guarded by [lock]. */
    private var generation = 0
    /** Utterances waiting for the one being synthesized. */
    private var queued = [(UUID, String)]()
    private var synthesizing = false
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
                guard granted else {
                    DispatchQueue.main.async { completion("Allow microphone access for Kinuvo in Settings") }
                    return
                }
                Task { @MainActor in
                    // Asked once; the voice is picked on first speaking, after the answer.
                    if AVSpeechSynthesizer.personalVoiceAuthorizationStatus == .notDetermined {
                        _ = await AVSpeechSynthesizer.requestPersonalVoiceAuthorization()
                    }
                    completion(await self.prepare())
                }
            }
        }
    }

    /** Downloads the speech model the first time, and finds the audio format it takes; returns why it can't, or nil. */
    private func prepare() async -> String? {
        guard let transcription = await Transcription.make() else {
            return "Speech recognition isn't available for \(Locale.current.identifier(.bcp47))"
        }
        do {
            if let request = try await AssetInventory.assetInstallationRequest(supporting: [transcription.module]) {
                try await request.downloadAndInstall()
            }
        } catch {
            log.error("Couldn't install the speech model: \(error.localizedDescription)")
            return "Couldn't download the speech model: \(error.localizedDescription)"
        }
        let format = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [transcription.module])
        lock.withLock { analyzerFormat = format }
        return format == nil ? "Speech recognition can't take this microphone's audio" : nil
    }

    func listen(untilPause: Bool, onPartial: @escaping (String) -> Void, onFinal: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
        stopListening()
        do { try start() } catch {
            log.error("Couldn't start audio to listen: \(error.localizedDescription)")
            onError(error.localizedDescription)
            return
        }
        let id = UUID()
        listening = id
        let (input, feed) = AsyncStream<AnalyzerInput>.makeStream()
        lock.withLock { self.feed = feed }
        func finish(_ text: String) {
            stopListening()
            onFinal(text)
        }
        transcribing = Task { @MainActor in
            do {
                if lock.withLock({ analyzerFormat == nil }), let error = await prepare() { throw Failure(error) }
                guard let transcription = await Transcription.make() else { throw Failure("Speech recognition is unavailable") }
                let analyzer = SpeechAnalyzer(modules: [transcription.module])
                guard listening == id else { return }
                self.analyzer = analyzer
                try await analyzer.start(inputSequence: input)
                // Final results add up; the latest volatile one is a guess at what follows them.
                var finalized = ""
                try await transcription.results { text, isFinal in
                    guard self.listening == id else { return }
                    let full = joined(finalized, text)
                    if isFinal { finalized = full }
                    onPartial(full)
                    guard untilPause else { return }
                    self.pause?.cancel()
                    let pause = DispatchWorkItem { if self.listening == id { finish(full) } }
                    self.pause = pause
                    DispatchQueue.main.asyncAfter(deadline: .now() + self.endOfSpeech, execute: pause)
                }
            } catch {
                guard listening == id else { return }
                log.error("Transcription failed: \(error.localizedDescription)")
                stopListening()
                onError((error as? Failure)?.message ?? error.localizedDescription)
            }
        }
    }

    func speak(text: String, onDone: @escaping () -> Void) {
        do { try start() } catch {
            log.error("Couldn't start audio to speak: \(error.localizedDescription)")
            onDone()
            return
        }
        // The synthesizer never ends an utterance with nothing to say, which would hold up the ones after it.
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return onDone() }
        let id = UUID()
        unfinished[id] = onDone
        queued.append((id, text))
        synthesizeNext()
    }

    /**
     * Synthesizes the next queued utterance once the one before is written: a new write while one is still going cuts
     * it short, which slower voices like Personal Voice make likely.
     */
    private func synthesizeNext() {
        guard !synthesizing, !queued.isEmpty else { return }
        let (id, text) = queued.removeFirst()
        synthesizing = true
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voice
        let generation = lock.withLock { self.generation }
        // The synthesizer ends an utterance with an empty buffer, sometimes more than one.
        var ended = false
        // Played once whole: Personal Voice can synthesize slower than real time, and a player running dry crackles.
        var speech = [AVAudioPCMBuffer]()
        synthesizer.write(utterance) { [weak self] buffer in
            guard let buffer = buffer as? AVAudioPCMBuffer, let copy = buffer.copy() as? AVAudioPCMBuffer else { return }
            self?.audio.async {
                guard let self, self.lock.withLock({ self.generation == generation }) else { return }
                guard !ended else {
                    if copy.frameLength > 0 { self.log.error("Dropped \(copy.frameLength) frames of speech after its end") }
                    return
                }
                if copy.frameLength == 0 {
                    ended = true
                    speech.forEach { self.player.scheduleBuffer($0) }
                    // The last buffer is empty; a frame of silence after the speech reports when it has played.
                    let end = AVAudioPCMBuffer(pcmFormat: self.playback, frameCapacity: 1)!
                    end.frameLength = 1
                    end.floatChannelData?[0][0] = 0
                    self.player.scheduleBuffer(end, completionCallbackType: .dataPlayedBack) { _ in
                        DispatchQueue.main.async { self.unfinished.removeValue(forKey: id)?() }
                    }
                    DispatchQueue.main.async {
                        guard self.lock.withLock({ self.generation == generation }) else { return }
                        self.synthesizing = false
                        self.synthesizeNext()
                    }
                } else if let converted = converted(copy, to: self.playback, with: &self.converter) {
                    speech.append(converted)
                    return
                } else {
                    self.log.error("Couldn't convert speech from \(copy.format)")
                }
                if !self.player.isPlaying { self.player.play() }
            }
        }
    }

    func stopSpeaking() {
        lock.withLock { generation += 1 }
        queued.removeAll()
        synthesizing = false
        synthesizer.stopSpeaking(at: .immediate)
        // Waits for a buffer being scheduled, and drops what the converter held of the cut-short speech.
        audio.sync {
            player.stop()
            converter = nil
        }
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
            feed?.finish()
            feed = nil
        }
        transcribing?.cancel()
        transcribing = nil
        if let analyzer {
            self.analyzer = nil
            Task { await analyzer.cancelAndFinishNow() }
        }
    }

    private func start() throws {
        // Setting the category again drops the speaker override, and the route change restarts the engine on the main thread.
        if active && engine.isRunning { return }
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
                self.log.notice("Audio configuration changed; restarting the engine")
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
            self.lock.withLock {
                guard let feed = self.feed, let format = self.analyzerFormat,
                      let converted = converted(buffer, to: format, with: &self.inputConverter, copying: true) else { return }
                feed.yield(AnalyzerInput(buffer: converted))
            }
        }
    }

    /**
     * The user's Personal Voice when they allow it and it speaks their language, else the best installed voice for it;
     * premium and enhanced ones are downloaded in Settings.
     */
    private lazy var voice: AVSpeechSynthesisVoice? = {
        let language = AVSpeechSynthesisVoice.currentLanguageCode()
        let voices = AVSpeechSynthesisVoice.speechVoices()
        let personal = AVSpeechSynthesizer.personalVoiceAuthorizationStatus == .authorized
            ? voices.first { $0.voiceTraits.contains(.isPersonalVoice) && $0.language.prefix(2) == language.prefix(2) } : nil
        return personal ?? voices.filter { $0.language == language }.max { $0.quality.rawValue < $1.quality.rawValue }
            ?? AVSpeechSynthesisVoice(language: language)
    }()
}

private func joined(_ a: String, _ b: String) -> String { [a, b].map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.joined(separator: " ") }

/**
 * [buffer] in [format], through [converter], which is replaced when the buffer's format changes. [copying] never returns
 * [buffer] itself, for buffers the engine reuses once its tap returns.
 */
private func converted(_ buffer: AVAudioPCMBuffer, to format: AVAudioFormat, with converter: inout AVAudioConverter?, copying: Bool = false) -> AVAudioPCMBuffer? {
    if buffer.format == format { return copying ? buffer.copy() as? AVAudioPCMBuffer : buffer }
    if converter?.inputFormat != buffer.format { converter = AVAudioConverter(from: buffer.format, to: format) }
    let capacity = AVAudioFrameCount(Double(buffer.frameLength) * format.sampleRate / buffer.format.sampleRate) + 1
    guard let converter, let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return nil }
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

private struct Failure: Error {
    let message: String
    init(_ message: String) { self.message = message }
}

/**
 * The best transcriber for the user's language: SpeechTranscriber, built for long-form speech, or DictationTranscriber
 * where it isn't available. [results] gets each result's text and whether it's final.
 */
private struct Transcription {
    let module: any SpeechModule
    let results: (@escaping (String, Bool) -> Void) async throws -> Void

    static func make() async -> Transcription? {
        if SpeechTranscriber.isAvailable, let locale = await SpeechTranscriber.supportedLocale(equivalentTo: .current) {
            let transcriber = SpeechTranscriber(locale: locale, preset: .progressiveTranscription)
            return Transcription(module: transcriber) { handle in
                for try await result in transcriber.results { await MainActor.run { handle(String(result.text.characters), result.isFinal) } }
            }
        }
        if let locale = await DictationTranscriber.supportedLocale(equivalentTo: .current) {
            let transcriber = DictationTranscriber(locale: locale, preset: .progressiveLongDictation)
            return Transcription(module: transcriber) { handle in
                for try await result in transcriber.results { await MainActor.run { handle(String(result.text.characters), result.isFinal) } }
            }
        }
        return nil
    }
}
