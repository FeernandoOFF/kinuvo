---
title: "Voice"
description: "Use Live voice, Device speech or Server transcription and synthesis."
---

Voice chat lets you talk with an Agent in a Session, with a transcript saved there. For **Dictation**, speak into the composer, review the text and send it yourself; Dictation does not send automatically.

## Live voice

Live voice is an opt-in speech-to-speech mode for Voice chat, using ChatGPT's realtime voice model through the owner's ChatGPT/Codex subscription. Sign in to Codex on the Server, then enable **Live voice** under **Settings → Server → Voice**. The setting appears only when the Server can offer it: currently a JVM Server on macOS arm64 with Codex signed in. Devices on iOS, Android and desktop can join through that Server.

Start a Voice call from the Session's voice controls. Audio streams continuously from the Device through the Server to OpenAI during the call. Calls count against the ChatGPT plan's usage limits; the call screen shows readable errors, including a reached usage limit and its reset time when available.

The voice model hands tasks to the Session's Agent, including External Agents. These run as ordinary Session messages labelled **By voice**, with Approvals shown in a sheet over the call. When the call ends, its transcript is saved in the Session as one collapsible **Voice call** block.

Calls keep running in the background. Minimize a call to a strip at the top of the app; iOS also shows a Live Activity. **Mute** stops only the microphone; replies keep playing. Use **End** to finish the call.

## Transcription and synthesis

Without Live voice, Voice chat turns what you say into messages and reads the Agent's replies aloud. Use the Session's voice controls to start, pause, resume or end it.

Voice chat and Dictation use speech parts provided by the selected Server, with Device system speech for parts left unset.

By default, recognition and synthesis use the Device's speech services. The Agent and its model still run through the selected Server and model provider, so recognized text can be processed by a cloud model.

Download and select models in **Settings → Server → Voice**. Server transcription sends microphone audio to the selected Server; Server synthesis sends text there and returns audio for Device playback. A selected downloaded model takes precedence over a configured provider. See [Server voice models](../device-local-voice.md) for platform support, privacy and limits.

## OpenAI-compatible speech providers

Configure `voice.transcription` for speech to text (STT), `voice.synthesis` for text to speech (TTS), or both in the Server home's `config.yaml`. Each part can use a different hosted or local endpoint. Unconfigured parts keep using Device speech. See the [configuration reference](../reference/config.md#voice) for all fields and an example.

Restart the Server after editing the configuration. Voice chat chooses the advertised providers when it starts; end an existing chat and start it again to use a changed selection. No separate model-provider login is needed: audio providers use their own optional `apiKey`.

For this transcription-and-synthesis mode, Server-backed Voice chat and Dictation are available on iOS and macOS. Windows uses Device speech. Android offers Live voice through a supported Server; browser clients do not currently offer Voice chat. On desktop, allow microphone access in system settings; Device transcription also needs speech recognition access. Microphone access is always needed; Device speech recognition access is needed only when using Device transcription or Dictation.

Server transcription records a WAV turn and uploads it after about 1.3 seconds of quiet. Hold to talk to include pauses, then let go to transcribe and send. Recordings stop after 30 seconds; ten seconds without speech restarts listening without a provider request. The 30-second cap bounds memory use and upload latency for a buffered turn; it is an application limit, not an OpenAI API requirement. At 16 kHz mono PCM16, a full turn contains about 960 KB of audio. Transcripts appear after the recording is uploaded, rather than word by word.

When either Server provider is selected, listening pauses while the Agent thinks and replies play, to avoid transcribing the speaker. Pause cuts speech short; resume listens again. Replies are synthesized in ordered chunks of up to 240 characters. Provider errors end Voice chat with an error; they do not silently switch providers.

## Compatible speech services

Include the API prefix, such as `/v1`, in the base URL. The service must support OpenAI-compatible audio transcription and WAV speech synthesis; a chat-only endpoint is not enough. Requests time out after two minutes, so warm local services first if initial loading takes longer.

Endpoints and keys remain on the Server. A configured STT service receives your recorded speech; a configured TTS service receives the text being read aloud, including spoken Approval notices. Audio clips stay in memory and are not stored in Session history by Kinuvo. Provider retention policies still apply.

<div class="k-future">

**Future enhancements**

- **Transcript clean-up**: not implemented.
- The design is recorded in [ADR 0013](../adr/0013-voice-providers.md).

</div>

## Download voice models onto the Server

In **Settings → Server → Voice**, download and select a transcription or synthesis model when your Server offers downloaded models. These run on the Server’s machine, including when you control the download from a phone. Downloading requires network access; installed models can run offline. The initial download also installs the required inference engine.

Downloaded selections take precedence over configured speech endpoints. Removing a selected model clears that selection; active models cannot be removed while in use. A configured model failure is reported rather than silently using Device speech. Audio may still leave the Device when the selected Server is remote.
