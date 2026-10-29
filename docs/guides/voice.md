---
title: "Voice"
description: "Use shipped Device speech recognition, Dictation and spoken replies."
---

Voice chat uses speech parts configured on the selected Server, with Device system speech for parts left unset. The transcript stays in the Session. Availability and microphone or speech access depend on the Device and platform.

Use the Session's voice controls to start Voice chat. For **Dictation**, speak into the composer, review the text and send it yourself; Dictation does not send automatically.

Download and select models in **Settings → Server → Voice**. Server transcription sends microphone audio to the selected Server; Server synthesis sends text there and returns audio for Device playback. See [Server voice models](../device-local-voice.md) for platform support, privacy and limits. The Agent and its model still run through the selected Server and provider, so recognized text can be processed by a cloud model.

Desktop speech is available on macOS and Windows; see [Desktop speech](../desktop-voice.md) for setup and platform requirements. Desktop Voice chat releases the microphone while the Agent thinks and speaks, then resumes listening.

Implementation: [voice interfaces](https://github.com/FeernandoOFF/kinuvo/blob/main/shared/app/src/session/Voice.kt).

<div class="k-future">

**Future enhancements**

- **Transcript clean-up and external speech services**: not implemented; downloadable Server models currently provide transcription and synthesis.
- **Speech to speech**: a live audio conversation that hands anything needing tools, Memory or Approvals to the Agent.
- The design is recorded in [ADR 0013](../adr/0013-voice-providers.md).

</div>
