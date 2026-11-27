---
title: "Live voice ownership"
description: "Contributor guide to call lifetimes, platform audio and iOS Live Activities."
---

Koin's `LiveCalls` singleton owns the active live voice chat. Observe
`state: StateFlow<ActiveCall?>`: null means no call. `ActiveCall` identifies the
Agent and Session, carries the display name, start timestamp (epoch milliseconds),
duration in seconds, and `voice: VoiceChatState`. The voice state exposes phase,
`paused` (microphone muted; replies keep playing), speaker enabled, latest user `heard` and model
`caption`, normalized input/output RMS, and an error. `heard` and `caption` stream as
the turn is spoken and are replaced by its final transcript; a new turn replaces the
previous one. Captions follow the text as the model produces it, not playback, so they
can run slightly ahead of the voice.

Start with `start(agent, session, voice, name)`; the voice implementation must be
a live transport. Starting another Session ends the previous call. Starting the
same Session retains it. `mute(Boolean)`, `speaker(Boolean)`, `end()` and
`openSession()` operate on the active call. Session voice controls use `attach`
and retain the Server-offered live-versus-speech selection: a Server whose `VoiceSetup.liveCalls` is false, or a Device that can't hold a call, uses speech-based Voice chat. The attachment's
observation belongs to the destination scope; audio belongs to the singleton.
Resolving a new Session runs in the call owner's scope.

Every Device holds its own call with a `LiveCallPeer`; the Server only brokers it (see
[ADR 0043](adr/0043-device-held-live-calls.md)). The earlier Server-held path, where the Server held the call and the
Device streamed PCM over `voice.live.*`, is removed. A Device on a Server that has not been updated sees no
`liveCalls` and uses speech-based Voice chat; a Device that still sends `voice.live.start` gets an unknown-method
error. The `voice.live.settings` and `voice.live.save` methods remain for the owner's on/off switch.

Codex adapter calls need the Codex (device-code) ChatGPT sign-in: the browser "Continue with ChatGPT" token belongs to another OAuth app and the realtime backend rejects it (401 `rejected_by_access_enforcement`). `CodexAuth.signInKind()` reports which sign-in `tokens()` would use, without the network. While the browser sign-in is selected, `LiveCallProvider.available()` is false and `unavailable()` returns the reason and the provider whose device-code sign-in fixes it, which reaches Devices as `LiveVoiceSettings.unavailable` so Voice settings can offer that sign-in (`providers.login`); a call that is rejected anyway fails with the same advice.

While the Agent works on a delegation, the model hears only approval requests and Agent questions, as speakable context sent at once so the user always hears them; other progress stays off the call, because the model reads out whatever it is given. The final answer goes as speakable context, held until no turn is open (8 seconds at most) because context appended mid-turn is not spoken afterwards; if the model starts no turn within 8 seconds of the answer, one nudge tells it to give the result. Notifications are speakable. The Agent's run is the Server's, not the call's: ending the call stops only the wait, and the answer then lands in the Session unspoken. The Session saves a Voice call block before each delegation, so the call reads in order around the Agent's messages. The model is told the Agent's name and not to call itself ChatGPT.

A failed call stays the active call: its voice state keeps `live` with `phase`
Off and a readable `error` until `end()`, which clears it. The Server words
realtime failures for the Device (usage limit and reset time, sign-in, network,
dropped call) and logs the raw detail at warn level.

Each start or end invalidates the previous call's cleanup before releasing shared
Device audio, so a cancelled call cannot stop a later one. Ending an already ended
chat does nothing.

On iOS, `NativeLiveCalls.observe` returns a `StateObservation`; retain it and
cancel it when its native observer ends. The bridge also exposes mute, speaker,
end and openSession. The app retains the Live Activity observer for its lifetime.

The call minimizes to a strip at the top of the app and keeps running in the
background. Approvals appear in a sheet over the call.

## Android

`AndroidSpeech.createCallPeer` gives the call an `AndroidLiveCallPeer`:
libwebrtc (`io.getstream:stream-webrtc-android`) with its Java audio device module on the voice-communication
source and stream, using the phone's echo canceller and noise suppressor where it has them and WebRTC's otherwise.
Creating the peer also starts `AudioRoute` (communication mode, audio focus, speakerphone unless a wired,
USB or Bluetooth headset is connected) and `LiveVoiceService`, a foreground service of type `microphone` with an End
action that keeps the call alive in the background. Closing the peer releases both; stopping the service ends the call.
The microphone permission goes through `authorizeAudio`. A phone's own Server offers these calls like any other
Server once ChatGPT is signed in.

## iOS

The app declares background audio and Live Activity support. `AppleSpeech.createCallPeer` gives the call an `AppleLiveCallPeer`: Stream's
libwebrtc build (`StreamWebRTC`, Swift package `stream-video-swift-webrtc`, pinned to an exact version) in Swift,
adapted to `LiveCallPeer` by `NativeLiveCallPeer` (`client/app/src/native`), which Swift implements with callbacks. The
peer takes the audio session as `playAndRecord` in `.voiceChat` mode (Apple's echo cancellation and noise
suppression), defaulting to the speaker with Bluetooth allowed, and starts WebRTC's audio unit only once the connection
exists (`RTCAudioSession` manual audio). Closing the peer deactivates the session, retrying while a cue still plays, and
restores the category it found; an interruption that ends with `shouldResume` restarts the audio unit and a
media-services reset fails the call. Cues play through the call's session without reconfiguring it. The microphone
permission goes through `authorizeAudio`. `UIBackgroundModes` audio keeps the call running in the background and the
Live Activity follows the active call. A phone's own Server offers these calls like any other once ChatGPT is
signed in.

The Kotlin Toolchain's `ios/app` product builds `module.xcodeproj`. The app target
depends on `liveactivity` and copies `KinuvoCalls.appex` into PlugIns using its
existing extension copy phase. The extension identifier is
`dev.kinuvo.ios.calls`, beneath `dev.kinuvo.ios`; automatic signing uses team
`R5MBZ7H925`. There are no new entitlements or App Groups. The existing
notification extension is independent.

`src/CallActivity.swift` is compiled into both targets, with
`KINUVO_CALL_WIDGET` excluding the app-only action implementation in the widget.
LiveActivityIntent actions execute in the app process. Activity state updates
are coalesced to at most twice per second. Taps use the existing
`kinuvo://chat/<agent>/<session>` route. `liveactivity/CallWidget.swift` shows an Agent-coloured orb, status, elapsed timer,
and last line, with Mute/Unmute and End controls on the Lock Screen and in the
expanded Dynamic Island. The orb is static; the system updates the elapsed timer.

Hardware verification must cover locked-screen and background calls,
Bluetooth and wired routes, phone/Siri interruptions, Live Activity actions,
deep-link navigation, and signed extension installation.

## Web

The web app holds calls itself when the browser has `RTCPeerConnection` and `getUserMedia`, which means a secure context
(HTTPS, or localhost); otherwise it offers no voice. `WebSpeech` is the web app's `LiveAudio`: it supports calls
only, so Dictation, reading aloud and speech-based Voice chat stay unavailable in the browser. Starting voice
chat against a Server without live calls shows that.
`WebLiveCallPeer` (js and wasmJs share it in `client/ui/src@web`) asks for the microphone with echo cancellation, noise
suppression and auto gain on only when the offer is made, so the browser's permission prompt follows the tap that started
the call. It builds a max-bundle connection with the microphone's one audio track, returns the offer once ICE gathering is
complete and plays the model's voice through an `<audio>` element; a browser that blocks that until a gesture is retried on
the next tap or key press. Mute disables the microphone track. Levels come from `getStats` audio levels, which a browser may
not report. Denied, missing and busy microphones each get their own message. Connection cues play through one `AudioContext`
opened when the call starts. The browser connects to the realtime model directly, so nothing in the Server's headers needs to
allow it: the sample Caddyfile sets no `connect-src`, `media-src` or Permissions-Policy that would block WebRTC or the
microphone. The call ends when the tab closes or navigates away.
