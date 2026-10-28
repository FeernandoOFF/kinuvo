import AppKit
import AVFoundation
import Speech

let outputLock = NSLock()

func emit(_ event: String, _ id: Int = 0, _ text: String = "") {
    outputLock.lock()
    defer { outputLock.unlock() }
    let data = try! JSONSerialization.data(withJSONObject: ["event": event, "id": id, "text": text], options: [.sortedKeys])
    FileHandle.standardOutput.write(data + Data([10]))
}

final class Speech: NSObject, AVSpeechSynthesizerDelegate {
    let audio = AVAudioEngine()
    let synth = AVSpeechSynthesizer()
    let clips = NativeVoiceAudio()
    var request: SFSpeechAudioBufferRecognitionRequest?
    var task: SFSpeechRecognitionTask?
    var recognizer: SFSpeechRecognizer?
    var listening = 0
    var tapped = false
    var timer: DispatchWorkItem?
    var utterances: [ObjectIdentifier: Int] = [:]

    override init() { super.init(); synth.delegate = self }

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
        emit("final", id, text)
    }

    func listen(_ id: Int, _ untilPause: Bool) {
        stopListening()
        guard let recognizer = SFSpeechRecognizer(locale: Locale.current), recognizer.isAvailable else {
            emit("error", id, "Apple speech recognition is unavailable for \(Locale.current.identifier). Check your language and network.")
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
            stopListening(); emit("error", id, "No microphone input. Choose a microphone in System Settings → Sound."); return
        }
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in request.append(buffer) }
        tapped = true
        task = recognizer.recognitionTask(with: request) { result, error in
            DispatchQueue.main.async {
                guard self.listening == id else { return }
                if let result = result {
                    let text = result.bestTranscription.formattedString
                    emit("partial", id, text)
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
                    emit("error", id, error.localizedDescription)
                }
            }
        }
        do { audio.prepare(); try audio.start() }
        catch { stopListening(); emit("error", id, error.localizedDescription) }
    }

    func handle(_ command: [String: Any]) {
        let id = command["id"] as? Int ?? 0
        switch command["op"] as? String {
        case "authorizeMicrophone": clips.authorizeMicrophone { emit("authorized", id, $0 ?? "") }
        case "record":
            stopListening()
            clips.record(untilPause: command["untilPause"] as? Bool ?? true,
                onRecorded: { emit("recorded", id, $0) }, onError: { emit("error", id, $0) })
        case "finishRecording": clips.finishRecording()
        case "stopRecording": clips.stopRecording()
        case "play": clips.play(base64: command["text"] as? String ?? "", onDone: { emit("done", id) }, onError: { emit("error", id, $0) })
        case "stopPlayback": clips.stopPlayback()
        case "authorize":
            SFSpeechRecognizer.requestAuthorization { status in
                guard status == .authorized else {
                    emit("authorized", id, "Allow Speech Recognition for Kinuvo Voice in System Settings → Privacy & Security."); return
                }
                AVCaptureDevice.requestAccess(for: .audio) { granted in
                    emit("authorized", id, granted ? "" : "Allow microphone access for Kinuvo Voice in System Settings → Privacy & Security.")
                }
            }
        case "listen": listen(id, command["untilPause"] as? Bool ?? true)
        case "stop": stopListening()
        case "speak":
            let utterance = AVSpeechUtterance(string: command["text"] as? String ?? "")
            utterance.voice = AVSpeechSynthesisVoice(language: Locale.current.identifier.replacingOccurrences(of: "_", with: "-"))
            utterances[ObjectIdentifier(utterance)] = id
            synth.speak(utterance)
        case "stopSpeaking":
            synth.stopSpeaking(at: .immediate)
            let pending = utterances.values; utterances.removeAll()
            pending.forEach { emit("done", $0) }
        default: break
        }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) { done(utterance) }
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) { done(utterance) }
    func done(_ utterance: AVSpeechUtterance) {
        if let id = utterances.removeValue(forKey: ObjectIdentifier(utterance)) { emit("done", id) }
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let speech = Speech()
DispatchQueue.global().async {
    while let line = readLine() {
        guard let data = line.data(using: .utf8), let command = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { continue }
        DispatchQueue.main.async { speech.handle(command) }
    }
    DispatchQueue.main.async { speech.stopListening(); NSApp.terminate(nil) }
}
app.run()
