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
and retain the Server-offered live-versus-speech selection. The attachment's
observation belongs to the destination scope; audio belongs to the singleton.
Resolving a new Session runs in the call owner's scope.

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

When the Server offers Device-held calls, `AndroidSpeech.createCallPeer` gives the call an `AndroidLiveCallPeer`:
libwebrtc (`io.getstream:stream-webrtc-android`) with its Java audio device module on the voice-communication
source and stream, using the phone's echo canceller and noise suppressor where it has them and WebRTC's otherwise.
Creating the peer also starts `AudioRoute` (communication mode, audio focus, speakerphone unless a wired,
USB or Bluetooth headset is connected) and `LiveVoiceService`, a foreground service of type `microphone` with an End
action that keeps the call alive in the background. Closing the peer releases both; stopping the service ends the call.
The microphone permission goes through `authorizeAudio`. Phones' own Servers offer these calls like any other
Server once ChatGPT is signed in.

## iOS

The app declares background audio and Live Activity support. When the Server offers Device-held calls, `AppleSpeech.createCallPeer` gives the call an `AppleLiveCallPeer`: Stream's
libwebrtc build (`StreamWebRTC`, Swift package `stream-video-swift-webrtc`, pinned to an exact version) in Swift,
adapted to `LiveCallPeer` by `NativeLiveCallPeer` (`client/app/src/native`), which Swift implements with callbacks. The
peer takes the audio session as `playAndRecord` in `.voiceChat` mode (Apple's echo cancellation and noise
suppression), defaulting to the speaker with Bluetooth allowed, and starts WebRTC's audio unit only once the connection
exists (`RTCAudioSession` manual audio). Closing the peer deactivates the session, retrying while a cue still plays, and
restores the category it found; an interruption that ends with `shouldResume` restarts the audio unit and a
media-services reset fails the call. Cues play through the call's session without reconfiguring it. The microphone
permission goes through `authorizeAudio`. `UIBackgroundModes` audio keeps the call running in the background and the
Live Activity works as for Server-held calls. A phone's own Server offers these calls like any other once ChatGPT is
signed in.

A Server-held call, from a Server without Device-held calls, runs the app's own audio engine. A route change
that leaves the engine running with the same microphone format only restores the
speaker override when output fell back to the receiver. Otherwise route changes,
and engine configuration changes that stop the engine, coalesce for 250 ms before
rebuilding the audio connections and microphone converter on the current route. Interruptions
suspend capture and playback while retaining the call; an end notification with
`shouldResume` reactivates audio. Failed restarts and media-services resets end
live audio with an error. Audio queued across a route change or interruption is
discarded.
Live audio starts once Device speech audio has released its graph; a stop in the
meantime cancels that start. Ending live audio disables voice processing and deactivates the audio session,
so each call starts from the same state as the first. Live audio logs its start,
route and session changes and failures under the `dev.kinuvo.app` subsystem's
`live` category.
Microphone PCM arrives about 30 times per second. Output RMS follows queued PCM
and the player's reported playback position at 25 Hz; it is an estimate, not a
hardware audio-output measurement.

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

Hardware verification must cover locked-screen and background streaming,
Bluetooth and wired routes, phone/Siri interruptions, Live Activity actions,
deep-link navigation, and signed extension installation.
