---
title: "Voice"
description: "Use Device speech or OpenAI-compatible Server transcription and synthesis."
---

Voice chat turns what you say into messages and reads the Agent's replies aloud. The transcript stays in the Session. Use the Session's voice controls to start, pause, resume or end Voice chat. For **Dictation**, speak into the composer, review the text and send it yourself; Dictation does not send automatically and continues to use the Device recognizer.

By default, recognition and synthesis use the Device's speech services. The Agent and its model still run through the selected Server and model provider, so recognized text can be processed by a cloud model.

## OpenAI-compatible speech providers

Configure `voice.transcription` for speech to text (STT), `voice.synthesis` for text to speech (TTS), or both in the Server home's `config.yaml`. Each part can use a different hosted or local endpoint. Unconfigured parts keep using Device speech. See the [configuration reference](../reference/config.md#voice) for all fields and an example.

Restart the Server after editing the configuration. Voice chat chooses the advertised providers when it starts; end an existing chat and start it again to use a changed selection. No separate model-provider login is needed: audio providers use their own optional `apiKey`.

Server-backed Voice chat is available on iOS, macOS and Windows, where Voice chat has microphone and playback support. Android and browser clients do not currently offer Voice chat. Desktop setup is described in [Desktop speech](../desktop-voice.md). Microphone access is always needed; Device speech recognition access is needed only when using Device transcription or Dictation.

Server transcription records a WAV turn and uploads it after about 1.3 seconds of quiet. Hold to talk to include pauses, then let go to transcribe and send. Recordings stop after 30 seconds; ten seconds without speech restarts listening without a provider request. The 30-second cap bounds memory use and upload latency for a buffered turn; it is an application limit, not an OpenAI API requirement. At 16 kHz mono PCM16, a full turn contains about 960 KB of audio. Transcripts appear after the recording is uploaded, rather than word by word.

When either Server provider is selected, listening pauses while the Agent thinks and replies play, to avoid transcribing the speaker. Pause cuts speech short; resume listens again. Replies are synthesized in ordered chunks of up to 240 characters. Provider errors end Voice chat with an error; they do not silently switch providers.

## Endpoint contract and limits

The base URL must include the provider's API prefix, such as `/v1`. The Server appends:

- `/audio/transcriptions`: multipart `file` (WAV), `model`, `response_format=json`, and optional `language`; expects a JSON object with a string `text`.
- `/audio/speech`: JSON `model`, `input`, `voice` and `response_format=wav`; expects RIFF/WAVE audio.

The contract follows the official OpenAI [transcription](https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create) and [speech](https://developers.openai.com/api/reference/resources/audio/subresources/speech/methods/create) APIs. Compatible services must implement these formats; chat-only OpenAI compatibility is insufficient.

Limits are 2 MiB per recording, 20,000 transcript characters, 2,000 characters per synthesis request, 8 MiB per returned WAV and 128 KiB per transcription JSON response. Requests time out after two minutes. Warm local models first if their initial load takes longer.

Endpoints and keys remain on the Server. A configured STT service receives your recorded speech; a configured TTS service receives the text being read aloud, including spoken Approval notices. Audio clips stay in memory and are not stored in Session history by Kinuvo. Provider retention policies still apply.

The authenticated client API exposes `voice.transcribe` (`{base64}` WAV → string) and `voice.synthesize` (`{text}` → `{base64}` WAV). Cancelling either call sends `voice.cancel` (`{requestId}`) on the same connection, which cancels the matching Server request and releases its request slot. Cancellation does not consume a request slot. `ServerCapabilities.voice` advertises available parts; older Servers default to Device speech.

Implementation: [voice interfaces](https://github.com/FeernandoOFF/kinuvo/blob/main/shared/app/src/session/Voice.kt), [HTTP adapter](https://github.com/FeernandoOFF/kinuvo/blob/main/harness/core/src/voice/OpenAICompatibleVoice.kt).

<div class="k-future">

**Future enhancements**

- Transcript clean-up through a separate processing model.
- **Speech to speech**: a live audio conversation that hands anything needing tools, Memory or Approvals to the Agent.
- The design is recorded in [ADR 0013](../adr/0013-voice-providers.md).

</div>
