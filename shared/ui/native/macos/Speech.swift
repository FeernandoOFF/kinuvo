import AppKit
import AVFoundation
import Speech

final class Speech: NSObject, AVSpeechSynthesizerDelegate {
    let output: (String, Int, String) -> Void
    lazy var local = LocalSpeech(emit: output)
    let audio = AVAudioEngine()
    let synth = AVSpeechSynthesizer()
    var request: SFSpeechAudioBufferRecognitionRequest?
    var task: SFSpeechRecognitionTask?
    var recognizer: SFSpeechRecognizer?
    var listening = 0
    var tapped = false
    var timer: DispatchWorkItem?
    var utterances: [ObjectIdentifier: Int] = [:]

    init(emit: @escaping (String, Int, String) -> Void) { self.output = emit; super.init(); synth.delegate = self }

    func emit(_ event: String, _ id: Int, _ text: String = "") { output(event, id, text) }

    func stopListening() {
        listening = 0
        timer?.cancel(); timer = nil
        audio.stop()
        if tapped { audio.inputNode.removeTap(onBus: 0); tapped = false }
        request?.endAudio(); request = nil
        task?.cancel(); task = nil
    }

    func finish(_ id: Int, _ text: String) {
        guard listening == id else { return }
        stopListening()
        self.emit("final", id, text)
    }

    func listen(_ id: Int, _ untilPause: Bool) {
        stopListening()
        guard let recognizer = SFSpeechRecognizer(locale: Locale.current), recognizer.isAvailable else {
            self.emit("error", id, "Apple speech recognition is unavailable for \(Locale.current.identifier). Check your language and network.")
            return
        }
        self.recognizer = recognizer
        listening = id
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        if recognizer.supportsOnDeviceRecognition { request.requiresOnDeviceRecognition = true }
        self.request = request
        let input = audio.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            stopListening(); self.emit("error", id, "No microphone input. Choose a microphone in System Settings → Sound."); return
        }
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in request.append(buffer) }
        tapped = true
        task = recognizer.recognitionTask(with: request) { result, error in
            DispatchQueue.main.async {
                guard self.listening == id else { return }
                if let result = result {
                    let text = result.bestTranscription.formattedString
                    self.emit("partial", id, text)
                    self.timer?.cancel()
                    if result.isFinal { self.finish(id, text); return }
                    if untilPause && !text.isEmpty {
                        let timer = DispatchWorkItem { self.finish(id, text) }
                        self.timer = timer
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.3, execute: timer)
                    }
                }
                if let error = error {
                    self.stopListening()
                    self.emit("error", id, error.localizedDescription)
                }
            }
        }
        do { audio.prepare(); try audio.start() }
        catch { stopListening(); self.emit("error", id, error.localizedDescription) }
    }

    func authorizeMicrophone(_ id: Int) {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: self.emit("authorized", id)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { _ in
                DispatchQueue.main.async { self.authorizeMicrophone(id) }
            }
        case .restricted: self.emit("authorized", id, "Microphone access is restricted by system policy.")
        default: self.emit("authorized", id, "Allow microphone access for Kinuvo in System Settings → Privacy & Security.")
        }
    }

    func authorizedSpeech(_ status: SFSpeechRecognizerAuthorizationStatus, _ id: Int) {
        switch status {
        case .authorized: authorizeMicrophone(id)
        case .restricted: self.emit("authorized", id, "Speech Recognition is restricted by system policy.")
        default: self.emit("authorized", id, "Allow Speech Recognition for Kinuvo in System Settings → Privacy & Security.")
        }
    }

    func handle(_ command: [String: Any]) {
        let id = command["id"] as? Int ?? 0
        switch command["op"] as? String {
        case "localAuthorize": authorizeMicrophone(id)
        case "record":
            stopListening(); var capture = command; capture["rawAudio"] = true; local.listen(id, capture)
        case "playClip": local.playClip(id, command["text"] as? String ?? "")
        case "localListen": stopListening(); local.listen(id, command)
        case "localFinish": local.finish()
        case "localSpeak": local.speak(id, command["text"] as? String ?? "", command)
        case "authorize":
            let status = SFSpeechRecognizer.authorizationStatus()
            if status == .notDetermined {
                SFSpeechRecognizer.requestAuthorization { status in
                    DispatchQueue.main.async { self.authorizedSpeech(status, id) }
                }
            } else { authorizedSpeech(status, id) }
        case "listen": local.stopListening(); listen(id, command["untilPause"] as? Bool ?? true)
        case "stop": stopListening(); local.stopListening()
        case "speak":
            let utterance = AVSpeechUtterance(string: command["text"] as? String ?? "")
            utterance.voice = AVSpeechSynthesisVoice(language: Locale.current.identifier.replacingOccurrences(of: "_", with: "-"))
            utterances[ObjectIdentifier(utterance)] = id
            synth.speak(utterance)
        case "stopSpeaking":
            local.stopSpeaking()
            synth.stopSpeaking(at: .immediate)
            let pending = utterances.values; utterances.removeAll()
            pending.forEach { self.emit("done", $0) }
        default: break
        }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) { done(utterance) }
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) { done(utterance) }
    func done(_ utterance: AVSpeechUtterance) {
        if let id = utterances.removeValue(forKey: ObjectIdentifier(utterance)) { self.emit("done", id) }
    }
}

