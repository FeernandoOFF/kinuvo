---
title: "Voice providers: Device and Server speech pipelines"
description: "Independent speech providers with Device defaults and Server-owned endpoints and keys."
---

Voice chat turns speech into a user message (`voice = true`), runs the Session's Agent and reads the reply aloud. Transcription and synthesis use the Device by default. Either part can instead run through an OpenAI-compatible provider on the Server, independently of the Agent's model provider.

## Decision

Audio never passes through `Model.stream`: Agents stay text, and voice providers are separate from model providers. The Server owns endpoints and keys in the `voice` section of `config.yaml`. Optional API keys support environment and Secret references. Configuration is read at Server startup; restart to apply changes.

- **Server**: `VoiceProviders` (`harness/core/src/voice/`) holds the configured parts. Missing parts stay on the Device.
- **Protocol**: `ServerCapabilities.voice` advertises a `VoiceSetup`, without endpoints or keys. Older Servers default to Device speech. Paired Devices use the authenticated `voice.transcribe` and `voice.synthesize` RPCs.
- **Device**: `ServerSpeechEngine` (`shared/app/src/session/`) selects each advertised part when Voice chat starts and owns requests through `ConnectedServer`. `VoiceAudio` supplies WAV recording and playback on iOS, macOS and Windows. Dictation continues to use the Device recognizer.

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
configured failures surface as errors. Audio is transient; Agents remain text. Voice chat remains half duplex.
See [Voice](../guides/voice.md), [Server voice models](../device-local-voice.md) and
[Server configuration](../reference/config.md#voice).
## Extension points

`TranscriptProcessor` provides a seam for transcript clean-up before a message is sent. `SpeechToSpeech` and `VoiceChat` provide seams for a live audio conversation whose Agent work goes through `askAgent`, with the Session's tools and Approvals. These parts are not currently configurable providers.
