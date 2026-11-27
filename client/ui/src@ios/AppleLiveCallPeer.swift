import AVFoundation
import StreamWebRTC
import os
import KotlinModules

/**
 * The iPhone's end of a call it holds: Stream's libwebrtc build carries the microphone and speaker to the Server's model
 * as one send+receive audio track over a max-bundle connection whose offer is complete (no trickle). Apple's voice
 * processing (the `.voiceChat` mode) cancels echo and suppresses noise. [prepare] frees Device speech audio before the
 * call takes the audio session, and [finished] runs once the call has given it back.
 */
final class AppleLiveCallPeer: NSObject, NativeLiveCallPeer, RTCPeerConnectionDelegate {
    private let queue = DispatchQueue(label: "dev.kinuvo.live.call", qos: .userInteractive)
    private let prepare: (@escaping () -> Void) -> Void
    private let finished: () -> Void
    private let log = Logger(subsystem: "dev.kinuvo.app", category: "live")
    // Everything below is touched on `queue`.
    private var events: NativeLiveCallEvents?
    private var factory: RTCPeerConnectionFactory?
    private var connection: RTCPeerConnection?
    private var track: RTCAudioTrack?
    private var audio: CallAudioSession?
    private var gathered: (() -> Void)?
    private var measuring = false
    private var closed = false
    private var muted = false
    private var speakerMuted = false

    init(prepare: @escaping (@escaping () -> Void) -> Void, finished: @escaping () -> Void) {
        self.prepare = prepare
        self.finished = finished
    }

    func attach(events: NativeLiveCallEvents) { queue.async { self.events = events } }

    func createOffer(onOffer: @escaping (String) -> Void, onFailure: @escaping (String) -> Void) {
        DispatchQueue.main.async { [self] in
            prepare { [self] in queue.async { [self] in begin(onOffer, onFailure) } }
        }
    }

    private func begin(_ onOffer: @escaping (String) -> Void, _ onFailure: @escaping (String) -> Void) {
        guard !closed else { return onFailure("The call was closed.") }
        guard AVAudioApplication.shared.recordPermission == .granted else { return onFailure("Microphone access is denied. Enable it in Settings.") }
        do { audio = try CallAudioSession(onReset: { [weak self] in self?.queue.async { self?.events?.failed() } }) }
        catch {
            log.error("Couldn't set up call audio: \(String(describing: error), privacy: .public)")
            return onFailure("Couldn't set up audio for the call.")
        }
        let encoder = RTCDefaultVideoEncoderFactory()
        let decoder = RTCDefaultVideoDecoderFactory()
        let factory = RTCPeerConnectionFactory(encoderFactory: encoder, decoderFactory: decoder)
        let config = RTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        config.bundlePolicy = .maxBundle
        config.continualGatheringPolicy = .gatherOnce
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: ["DtlsSrtpKeyAgreement": "true"])
        let processing = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: [
            "googEchoCancellation": "true", "googNoiseSuppression": "true", "googAutoGainControl": "true", "googHighpassFilter": "true",
        ])
        guard let connection = factory.peerConnection(with: config, constraints: constraints, delegate: self) else {
            audio?.release(); audio = nil
            return onFailure("Couldn't start the call.")
        }
        let track = factory.audioTrack(with: factory.audioSource(with: processing), trackId: "kinuvo-microphone")
        track.isEnabled = !muted
        guard connection.add(track, streamIds: ["kinuvo"]) != nil else {
            connection.close()
            audio?.release(); audio = nil
            return onFailure("Couldn't start the call.")
        }
        self.factory = factory
        self.connection = connection
        self.track = track
        audio?.enable()
        connection.offer(for: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)) { [self] sdp, error in
            queue.async { [self] in
                guard !closed, self.connection === connection else { return onFailure("The call was closed.") }
                guard let sdp else { return onFailure("Couldn't prepare the call: \(error?.localizedDescription ?? "no offer")") }
                connection.setLocalDescription(sdp) { [self] error in
                    queue.async { [self] in
                        if let error { return onFailure("Couldn't prepare the call: \(error.localizedDescription)") }
                        awaitCandidates(onOffer, onFailure)
                    }
                }
            }
        }
    }

    private func awaitCandidates(_ onOffer: @escaping (String) -> Void, _ onFailure: @escaping (String) -> Void) {
        guard !closed, let connection else { return onFailure("The call was closed.") }
        var done = false
        let finish = { [self] in
            guard !done else { return }
            done = true
            gathered = nil
            guard !closed, let sdp = connection.localDescription?.sdp else { return onFailure("The call was closed.") }
            onOffer(sdp)
        }
        if connection.iceGatheringState == .complete { return finish() }
        gathered = finish
        queue.asyncAfter(deadline: .now() + 10) { [self] in
            guard !done else { return }
            done = true
            gathered = nil
            onFailure("Couldn't reach the network to start the call.")
        }
    }

    func accept(answerSdp: String, onAccepted: @escaping () -> Void, onFailure: @escaping (String) -> Void) {
        queue.async { [self] in
            guard !closed, let connection else { return onFailure("The call was closed.") }
            connection.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: answerSdp)) { [self] error in
                queue.async { [self] in
                    if let error { return onFailure("The call's answer was refused: \(error.localizedDescription)") }
                    applySpeaker()
                    measure()
                    onAccepted()
                }
            }
        }
    }

    func setMuted(muted: Bool) {
        queue.async { [self] in
            self.muted = muted
            track?.isEnabled = !muted
        }
    }

    func setSpeakerMuted(muted: Bool) {
        queue.async { [self] in
            speakerMuted = muted
            applySpeaker()
        }
    }

    private func applySpeaker() {
        connection?.transceivers.forEach { ($0.receiver.track as? RTCAudioTrack)?.isEnabled = !speakerMuted }
    }

    func close() {
        queue.async { [self] in
            guard !closed else { return }
            closed = true
            gathered = nil
            track?.isEnabled = false
            connection?.close()
            connection = nil; track = nil; factory = nil
            audio?.release(); audio = nil
            events?.closed()
            finished()
        }
    }

    private func measure() {
        guard !measuring else { return }
        measuring = true
        tick()
    }

    private func tick() {
        guard !closed, let connection else { return }
        connection.statistics { [self] report in
            var input: Float = 0
            var output: Float = 0
            for stat in report.statistics.values {
                guard let level = (stat.values["audioLevel"] as? NSNumber)?.floatValue else { continue }
                if stat.type == "media-source" { input = level } else if stat.type == "inbound-rtp" { output = level }
            }
            queue.async { [self] in
                guard !closed else { return }
                events?.levels(input: muted ? 0 : input, output: output)
                queue.asyncAfter(deadline: .now() + 0.1) { [self] in tick() }
            }
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        queue.async { [self] in
            guard !closed, connection === peerConnection else { return }
            log.info("Call connection: \(newState.rawValue)")
            switch newState {
            case .connected: events?.connected()
            case .failed: events?.failed()
            case .closed: events?.closed()
            default: events?.connecting()
            }
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        guard newState == .complete else { return }
        queue.async { [self] in gathered?() }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}

/**
 * The audio session a call holds: play-and-record in voice-chat mode, which turns on Apple's echo cancellation and noise
 * suppression, defaulting to the speaker and allowing Bluetooth. WebRTC starts its audio unit only once [enable]d, and
 * [release] puts back what it found. Interruptions that end with `shouldResume` restart the unit; a media-services
 * reset fails the call through [onReset].
 */
private final class CallAudioSession {
    private static var epoch = 0
    private let rtc = RTCAudioSession.sharedInstance()
    private let category: AVAudioSession.Category
    private let mode: AVAudioSession.Mode
    private let options: AVAudioSession.CategoryOptions
    private let manual: Bool
    private let enabled: Bool
    private let ours: Int
    private var observers = [NSObjectProtocol]()
    private let log = Logger(subsystem: "dev.kinuvo.app", category: "live")

    init(onReset: @escaping () -> Void) throws {
        let session = AVAudioSession.sharedInstance()
        category = session.category; mode = session.mode; options = session.categoryOptions
        manual = rtc.useManualAudio; enabled = rtc.isAudioEnabled
        Self.epoch += 1
        ours = Self.epoch
        rtc.useManualAudio = true
        rtc.isAudioEnabled = false
        rtc.lockForConfiguration()
        defer { rtc.unlockForConfiguration() }
        try rtc.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetoothHFP, .allowBluetoothA2DP])
        try rtc.setActive(true)
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) { [weak self] note in
            guard let self,
                  let type = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  type == AVAudioSession.InterruptionType.ended.rawValue,
                  AVAudioSession.InterruptionOptions(rawValue: note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0).contains(.shouldResume) else { return }
            self.resume()
        })
        observers.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [log] _ in
            log.error("Audio services were reset")
            onReset()
        })
    }

    func enable() { rtc.isAudioEnabled = true }

    private func resume() {
        log.notice("Call audio resuming after an interruption")
        do { try AVAudioSession.sharedInstance().setActive(true) } catch { log.error("Couldn't reactivate call audio: \(String(describing: error), privacy: .public)") }
        rtc.isAudioEnabled = false
        rtc.isAudioEnabled = true
    }

    func release() {
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        rtc.isAudioEnabled = false
        rtc.lockForConfiguration()
        do { try rtc.setActive(false) } catch { log.notice("Call audio is still busy: \(String(describing: error), privacy: .public)") }
        do { try rtc.setCategory(category, mode: mode, options: options) } catch { log.error("Couldn't restore the audio session: \(String(describing: error), privacy: .public)") }
        rtc.unlockForConfiguration()
        rtc.useManualAudio = manual
        rtc.isAudioEnabled = enabled
        deactivate(attempt: 0)
    }

    /** Playing a cue, or WebRTC's audio unit still stopping, keeps the session busy for a moment. */
    private func deactivate(attempt: Int) {
        guard Self.epoch == ours else { return }
        do { try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
        catch where attempt < 10 { DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { self.deactivate(attempt: attempt + 1) } }
        catch { log.error("Couldn't release the audio session: \(String(describing: error), privacy: .public)") }
    }
}
