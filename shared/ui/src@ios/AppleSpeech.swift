import AVFoundation
import Speech
import os
import KotlinModules

/**
 * Voice chat on-device. SpeechAnalyzer listens and AVSpeechSynthesizer speaks, both through one AVAudioEngine with
 * voice processing, so the microphone hears the user but not the reply (echo cancellation). Runs on the main thread.
 */
final class AppleSpeech: NSObject, SpeechEngine, VoiceAudio {
    var audioSupported: Bool { true }
    private let serverAudio = AppleServerAudio()
    var halfDuplex: Bool { true }
    func authorizeAudio(completion: @escaping (String?) -> Void) { serverAudio.authorize(completion) }
    func record(untilPause: Bool, onAudio: @escaping (KotlinByteArray) -> Void, onError: @escaping (String) -> Void) {
        releaseDeviceAudio()
        serverAudio.record(untilPause, onAudio, onError)
    }
    func finishRecording() { serverAudio.finish() }
    func stopRecording() { serverAudio.stop() }
    func play(wav: KotlinByteArray, onDone: @escaping () -> Void, onError: @escaping (String) -> Void) {
        releaseDeviceAudio()
        serverAudio.play(wav, onDone, onError)
    }
    func stopPlayback() { serverAudio.stopPlayback() }
    private var engine = AVAudioEngine()
    private var player = AVAudioPlayerNode()
    private let synthesizer = AVSpeechSynthesizer()
    private let playback = AVAudioFormat(standardFormatWithSampleRate: 22050, channels: 1)!
    /** Converts and schedules speech off the main thread, which a streaming reply keeps busy: late buffers crackle. */
    private let audio = DispatchQueue(label: "dev.kinuvo.speech.audio", qos: .userInteractive)
    /** Only touched on [audio]. */
    private var converter: AVAudioConverter?
    private var configured = false
    private var configurationObserver: NSObjectProtocol?
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
        let request = UUID()
        listening = request
        serverAudio.release {
            guard self.listening == request else { return }
            self.listenOnDevice(untilPause: untilPause, onPartial: onPartial, onFinal: onFinal, onError: onError)
        }
    }

    private func listenOnDevice(untilPause: Bool, onPartial: @escaping (String) -> Void, onFinal: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
        do { try start() } catch {
            log.error("Couldn't start audio to listen: \(error.localizedDescription)")
            listening = nil
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

    func finish(text: String, onFinal: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
        stop()
        onFinal(text)
    }

    func speak(text: String, onDone: @escaping () -> Void, onError: @escaping (String) -> Void) {
        speak(text: text, onDone: onDone)
    }

    func speak(text: String, onDone: @escaping () -> Void) {
        let request = lock.withLock { generation }
        serverAudio.release {
            guard self.lock.withLock({ self.generation == request }) else { return }
            self.speakOnDevice(text: text, onDone: onDone)
        }
    }

    private func speakOnDevice(text: String, onDone: @escaping () -> Void) {
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

    private func releaseDeviceAudio() {
        guard configured else { return }
        stop()
        // Release the voice-processing I/O unit before Server audio opens the microphone.
        if let configurationObserver { NotificationCenter.default.removeObserver(configurationObserver) }
        configurationObserver = nil
        engine = AVAudioEngine()
        player = AVAudioPlayerNode()
        configured = false
        lock.withLock { inputConverter = nil }
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
            configurationObserver = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] notification in
                guard let self, let changed = notification.object as? AVAudioEngine, changed === self.engine, self.active, !self.engine.isRunning else { return }
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


enum ServerAudioPCM {
    static func valid(_ format: AVAudioFormat) -> Bool {
        format.sampleRate.isFinite && format.sampleRate > 0 && format.channelCount > 0
    }

    static func samples(_ buffer: AVAudioPCMBuffer) -> (pcm: [Int16], power: Float)? {
        guard buffer.format.commonFormat == .pcmFormatFloat32, buffer.format.channelCount == 1,
              !buffer.format.isInterleaved, let data = buffer.floatChannelData?[0] else { return nil }
        var pcm = [Int16]()
        pcm.reserveCapacity(Int(buffer.frameLength))
        var power: Float = 0
        for index in 0..<Int(buffer.frameLength) {
            let value = data[index].isFinite ? max(-1, min(1, data[index])) : 0
            pcm.append(Int16(value * 32767))
            power += value * value
        }
        return (pcm, power / Float(max(1, buffer.frameLength)))
    }
}

private final class AppleServerAudio: NSObject, AVAudioPlayerDelegate {
    private let queue = DispatchQueue(label: "dev.kinuvo.server.audio", qos: .userInitiated)
    private var recordingID = UUID()
    private var playbackID = UUID()
    private var recordingTimer: DispatchWorkItem?
    private var playbackTimer: DispatchWorkItem?
    private var recordingError: ((String) -> Void)?
    private var finishingRecording = false

    func record(_ untilPause: Bool, _ completion: @escaping (KotlinByteArray) -> Void, _ error: @escaping (String) -> Void) {
        stop()
        let id = recordingID
        recordingError = error
        func failed(_ message: String) {
            DispatchQueue.main.async {
                guard self.recordingID == id else { return }
                self.stop()
                error(message)
            }
        }
        let startup = DispatchWorkItem { failed("The microphone did not start. Check the audio route and try again.") }
        recordingTimer = startup
        DispatchQueue.main.asyncAfter(deadline: .now() + 8, execute: startup)
        queue.async {
            self.recordOnQueue(untilPause, { bytes in
                DispatchQueue.main.async {
                    guard self.recordingID == id else { return }
                    self.stop()
                    completion(bytes)
                }
            }, failed)
            DispatchQueue.main.async {
                guard self.recordingID == id, !self.finishingRecording else { return }
                self.recordingTimer?.cancel()
                let deadline = DispatchWorkItem { failed("Microphone recording timed out. Try again.") }
                self.recordingTimer = deadline
                DispatchQueue.main.asyncAfter(deadline: .now() + 32, execute: deadline)
            }
        }
    }
    func finish() {
        finishingRecording = true
        let id = recordingID
        let error = recordingError
        recordingTimer?.cancel()
        let timer = DispatchWorkItem {
            guard self.recordingID == id, let error else { return }
            self.stop()
            error("Finishing microphone recording timed out. Try again.")
        }
        recordingTimer = timer
        DispatchQueue.main.asyncAfter(deadline: .now() + 8, execute: timer)
        queue.async { self.finishOnQueue() }
    }
    func stop() {
        recordingID = UUID()
        finishingRecording = false
        recordingTimer?.cancel(); recordingTimer = nil; recordingError = nil
        queue.async { self.stopOnQueue() }
    }
    func release(_ completion: (() -> Void)? = nil) {
        stop(); stopPlayback()
        if let completion { queue.async { DispatchQueue.main.async(execute: completion) } }
    }
    func play(_ wav: KotlinByteArray, _ completion: @escaping () -> Void, _ error: @escaping (String) -> Void) {
        stopPlayback()
        let id = playbackID
        func finish(_ message: String?) {
            DispatchQueue.main.async {
                guard self.playbackID == id else { return }
                self.stopPlayback()
                if let message { error(message) } else { completion() }
            }
        }
        let startup = DispatchWorkItem { finish("Voice playback did not start. Try again.") }
        playbackTimer = startup
        DispatchQueue.main.asyncAfter(deadline: .now() + 8, execute: startup)
        queue.async {
            self.playOnQueue(wav, { finish(nil) }, { finish($0) })
            let duration = self.player?.duration ?? 0
            DispatchQueue.main.async {
                guard self.playbackID == id else { return }
                self.playbackTimer?.cancel()
                let deadline = DispatchWorkItem { finish("Voice playback timed out. Try again.") }
                self.playbackTimer = deadline
                DispatchQueue.main.asyncAfter(deadline: .now() + min(305, duration + 5), execute: deadline)
            }
        }
    }
    func stopPlayback() {
        playbackID = UUID()
        playbackTimer?.cancel(); playbackTimer = nil
        queue.async { self.stopPlaybackOnQueue() }
    }
    private var engine: AVAudioEngine?
    private var player: AVAudioPlayer?
    private var samples = [Int16]()
    private var captured: ((KotlinByteArray) -> Void)?
    private var failure: ((String) -> Void)?
    private var playbackFailure: ((String) -> Void)?
    private var done: (() -> Void)?
    private var token = UUID()
    private var tapped = false
    private var deadline: DispatchWorkItem?
    private var observers = [NSObjectProtocol]()
    private var audible = 0
    private var quiet = 0
    func authorize(_ completion: @escaping (String?) -> Void) {
        AVAudioApplication.requestRecordPermission { granted in
            DispatchQueue.main.async { completion(granted ? nil : "Allow microphone access in Settings") }
        }
    }
    private func session() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try session.setActive(true)
    }
    func recordOnQueue(_ untilPause: Bool, _ completion: @escaping (KotlinByteArray) -> Void, _ error: @escaping (String) -> Void) {
        stopOnQueue()
        captured = completion; failure = error; audible = 0; quiet = 0
        let epoch = token
        do {
            guard AVAudioApplication.shared.recordPermission == .granted else {
                throw Failure("Allow microphone access for Kinuvo in Settings")
            }
            try session()
            let engine = AVAudioEngine()
            self.engine = engine
            let node = engine.inputNode
            let source = node.outputFormat(forBus: 0)
            guard ServerAudioPCM.valid(node.inputFormat(forBus: 0)), ServerAudioPCM.valid(source), let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000, channels: 1, interleaved: false), let converter = AVAudioConverter(from: source, to: target) else { throw Failure("The microphone has no usable audio format. Check the audio route and try again.") }
            let fail: (String) -> Void = { [weak self] message in
                self?.queue.async {
                    guard let self, self.token == epoch else { return }
                    self.failRecording(message)
                }
            }
            // Let the input retain its negotiated format instead of setting a hardware format on the tap.
            node.installTap(onBus: 0, bufferSize: 2048, format: nil) { buffer, _ in
                guard buffer.format == source else { fail("The microphone audio format changed. Try recording again."); return }
                guard buffer.frameLength > 0 else { return }
                guard let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: AVAudioFrameCount(Double(buffer.frameLength) * 16000 / source.sampleRate) + 32) else { fail("Couldn't allocate microphone audio buffer"); return }
                var supplied = false
                var conversionError: NSError?
                let status = converter.convert(to: output, error: &conversionError) { _, status in
                    if supplied { status.pointee = .noDataNow; return nil }
                    supplied = true; status.pointee = .haveData; return buffer
                }
                guard status != .error, conversionError == nil, let result = ServerAudioPCM.samples(output) else {
                    fail(conversionError?.localizedDescription ?? "Couldn't convert microphone audio")
                    return
                }
                let (pcm, power) = result
                self.queue.async {
                    guard self.token == epoch else { return }
                    self.samples.append(contentsOf: pcm.prefix(max(0, 480000 - self.samples.count)))
                    if power > 0.000064 { self.audible += pcm.count; self.quiet = 0 } else { self.quiet += pcm.count }
                    if self.samples.count >= 480000 || (untilPause && self.audible >= 2400 && self.quiet >= 20800) || (self.audible == 0 && self.samples.count >= 160000) { self.finishOnQueue() }
                }
            }
            tapped = true; engine.prepare(); try engine.start()
            let center = NotificationCenter.default
            observers = [
                center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
                    self?.queue.async {
                        guard let self, self.token == epoch, self.engine?.isRunning == false else { return }
                        self.failRecording("The microphone audio route changed. Try recording again.")
                    }
                },
                center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] _ in
                    self?.queue.async {
                        guard let self, self.token == epoch else { return }
                        self.failRecording("Microphone recording was interrupted. Try again.")
                    }
                },
                center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
                    self?.queue.async {
                        guard let self, self.token == epoch else { return }
                        self.failRecording("Audio services restarted. Try recording again.")
                    }
                }
            ]
            let timer = DispatchWorkItem { if self.token == epoch { self.finishOnQueue() } }
            deadline = timer; queue.asyncAfter(deadline: .now() + 30, execute: timer)
        } catch let reason { stopOnQueue(); error((reason as? Failure)?.message ?? reason.localizedDescription) }
    }
    func finishOnQueue() {
        guard let completion = captured else { return }
        guard !samples.isEmpty else { failRecording("No microphone audio arrived. Check the audio route and try again."); return }
        let values = audible >= 2400 ? samples : []
        let data = values.withUnsafeBytes { Data($0) }
        let bytes = AudioBytes.shared.fromData(data: data)
        stopOnQueue(); completion(bytes)
    }
    private func failRecording(_ message: String) {
        let error = failure
        stopOnQueue()
        error?(message)
    }
    func stopOnQueue() {
        observers.forEach { NotificationCenter.default.removeObserver($0) }; observers.removeAll()
        token = UUID(); deadline?.cancel(); deadline = nil
        engine?.stop()
        if tapped { engine?.inputNode.removeTap(onBus: 0); tapped = false }
        // Drop the stopped I/O graph before the caller deactivates the session or starts another recording.
        engine = nil
        samples.removeAll(); captured = nil; failure = nil
    }
    func playOnQueue(_ wav: KotlinByteArray, _ completion: @escaping () -> Void, _ error: @escaping (String) -> Void) {
        stopPlaybackOnQueue()
        guard wav.size <= 8388608 else { error("Audio clip too large"); return }
        let data = AudioBytes.shared.toData(bytes: wav)
        do {
            try session(); player = try AVAudioPlayer(data: data); player?.delegate = self; done = completion; playbackFailure = error
            guard player?.play() == true else { throw NSError(domain: "Voice", code: 2) }
        } catch let reason { stopPlaybackOnQueue(); error(reason.localizedDescription) }
    }
    func stopPlaybackOnQueue() { player?.stop(); player = nil; done = nil; playbackFailure = nil }
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        queue.async {
            guard self.player === player else { return }
            let completion = self.done, error = self.playbackFailure
            self.stopPlaybackOnQueue()
            if flag { completion?() } else { error?("Voice playback failed") }
        }
    }
    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        queue.async {
            guard self.player === player else { return }
            let failure = self.playbackFailure
            self.stopPlaybackOnQueue()
            failure?(error?.localizedDescription ?? "Voice playback failed")
        }
    }
}
