---
title: "Voice provider contracts"
description: "Contributor reference for voice provider contracts."
---

Voice chat turns speech into a user message (`voice = true`), runs the Session's Agent and reads the reply aloud. Transcription and synthesis use the Device by default. Either part can instead run through an OpenAI-compatible provider on the Server, independently of the Agent's model provider.

## Decision

Audio never passes through `Model.stream`: Agents stay text, and voice providers are separate from model providers. The Server owns endpoints and keys in the `voice` section of `config.yaml`. Optional API keys support environment and Secret references. Configuration is read at Server startup; restart to apply changes.

- **Server**: `VoiceProviders` (`server/plugin-api/src/`) holds the configured parts. Missing parts stay on the Device. Downloaded models come from a plugin's `HarnessPlugin.voice` (`ServerVoice`; `DownloadedVoice` on JVM hosts) and take precedence.
- **Protocol**: `ServerCapabilities.voice` advertises a `VoiceSetup`, without endpoints or keys. Older Servers default to Device speech. Paired Devices use the authenticated `voice.transcribe` and `voice.synthesize` RPCs.
- **Device**: `ServerSpeech` (`client/app/src/session/`) selects each advertised part when Voice chat starts and owns requests through `ConnectedServer`. `VoiceAudio` supplies WAV recording and playback on iOS, macOS and Windows. Dictation uses the selected Server transcription when configured, with Device speech for an unset part.

A provider failure ends Voice chat with an error rather than silently falling back. Device synthesis reports errors independently of recognition, including when Server transcription supplies the input. Stopping a chat or switching Servers discards late callbacks. Cancelling a voice RPC sends `voice.cancel` with its request ID; the Server cancels that connection's matching provider request and releases its slot before handling the next request. Cancellation is accepted even when the connection's request quota is full. Audio stays in memory; only text enters Session history.

## Provider protocol

Use one adapter per protocol, not per vendor. The OpenAI-compatible adapter sends multipart WAV recordings to `/audio/transcriptions` and JSON synthesis requests to `/audio/speech`, relative to the configured base URL. Transcription returns a JSON string field `text`; synthesis returns WAV audio.

Endpoints use HTTPS, or HTTP on loopback only, without credentials, query parameters or fragments in URLs. Redirects are not followed. Bound every request and response: 2 MiB recordings, 20,000 transcript characters, 2,000 characters per synthesis request, 8 MiB WAV responses and 128 KiB transcription JSON. Requests time out after two minutes.

## Turn-taking

When either Server provider is selected, listening pauses while the Agent thinks and replies play, so speaker output does not become another user turn. Pause cuts speech short; resume starts listening again.

Recording ends after about 1.3 seconds of quiet following speech, or after 30 seconds total. Hold to talk includes pauses until release, subject to the same duration cap. Ten seconds without speech restarts listening without a provider request. The duration cap bounds memory and upload latency for buffered recordings; it is an application choice rather than a provider requirement. At 16 kHz mono PCM16, 30 seconds contains 960,000 audio bytes, below the 2 MiB upload limit.

Replies are synthesized and played in order, in chunks of up to 240 characters. Endpoints and API keys remain on the Server. The transcription provider receives recorded speech; the synthesis provider receives the text being read aloud. Provider retention policies apply.

## Server voice providers (implemented)

A Server can run transcription and synthesis instead of the Device, independently of the Agent's model provider.
Two sources fill each part: a downloaded model selected in **Settings → Server → Voice** (management-authenticated
`voice.*` methods manage verified Tiny/Base Whisper and Supertonic weights, run by a packaged inference worker on
macOS), or an OpenAI-compatible provider in `config.yaml`. A selected downloaded model takes precedence. Paired
Devices call `voice.setup`, send WAV to `voice.transcribe` and receive WAV from `voice.synthesize`; cancelling the
call cancels Server inference. Execution links are excluded, and no endpoints, keys or paths reach Devices.

Server selections are read when Dictation or Voice chat starts. Missing Server parts use Device system speech;
configured failures surface as errors. Audio is transient; Agents remain text. This transcription-and-synthesis mode remains half duplex.
See [Voice](../guides/voice.md), [Server voice models](../device-local-voice.md) and
[Server configuration](../reference/config.md#voice).
## Extension points

`TranscriptProcessor` provides a seam for transcript clean-up before a message is sent. `SpeechToSpeech` and `VoiceChat` provide seams for a live audio conversation whose Agent work goes through `askAgent`, with the Session's tools and Approvals. Live calls use a signed-in Codex subscription on macOS arm64 JVM Servers and require the owner's `liveVoiceEnabled` opt-in in the Server config (default off). Devices exchange mono PCM16 at 24 kHz; the Server converts provider rates. Each connection owns at most one call and rejects another start until it ends. Closing the connection or ending the call cancels it. Voice input events have a separate 1,200/minute budget, a 64 KiB base64 limit and a bounded 64-event queue; overflow disconnects the Device. Reply audio shares the connection's outbox with the call's other events, so they stay in order, but at most 125 packets (5 s) of it wait there; a Device that falls further behind loses audio packets rather than the call. Other call events are never dropped: if they cannot be queued, or the Device is gone, the call ends. Codex reply audio that arrives before its turn is announced waits in a 1 s buffer and the reply starts from its first sound; sound that outlasts the buffer is relayed under its own item. Transcripts stream as partial text so far for each turn, replaced by the final transcript; only final transcripts are recorded. On macOS a JVM Server holds an `NSProcessInfo` user-initiated activity during a call, so App Nap and idle sleep cannot throttle it. Calls delegate to native or External Agents through their execution dispatch; native delegation uses the ordinary voice-message run path after any active Session run finishes.

Live calls seed the model with the Agent name, delegation policy, latest compaction summary and bounded recent user/assistant messages. The Server relays reply audio in 40 ms packets, including pauses through 300 ms of quiet after the turn ends. Devices prebuffer 80 ms when playback is empty, releasing sooner on reply completion or after 100 ms, and discard interrupted reply audio.

Live speech-to-speech calls can delegate to native or External Agents through their own execution dispatch. External delegation waits up to five minutes for the dispatched execution to leave Running or RequiresAction, then returns its newly committed assistant text; a lost connection or timeout returns a short failure message. External snapshot output is committed into the same Session log used for catch-up and voice context.

A completed live call is one `SessionEntry.VoiceCall`, containing ordered final transcript turns, start time and duration. Session entries are append-only, so the Server appends this block at call end, after any delegated runs, rather than rewriting history while a run is active. Cancellation on Device disconnect persists collected turns in a non-cancellable finalizer; calls with no turns add nothing. The block represents the completed call, and its turns expand into user and assistant messages for subsequent native model history and voice context. Devices receive it through ordinary Session catch-up. The new Session entry requires ProtocolVersion 6; older Devices cannot decode it. External harnesses retain ownership of their model context; Kinuvo's local transcript does not inject history into an external harness.
