---
title: "Voice"
description: "Use shipped Device speech recognition, Dictation and spoken replies."
---

Voice chat uses the Device's speech recognizer to turn speech into a message, then reads the Agent's reply with the Device's voice. The transcript stays in the Session. Availability and microphone or speech access depend on the Device and platform.

Use the Session's voice controls to start Voice chat. For **Dictation**, speak into the composer, review the text and send it yourself; Dictation does not send automatically.

Speech recognition and synthesis run on the Device in the shipped mode. The Agent and its model still run through the selected Server and provider, so recognized text can be processed by a cloud model.

Implementation: [voice interfaces](https://github.com/FeernandoOFF/kinuvo/blob/main/shared/app/src/session/Voice.kt).

<div class="k-future">

**Future enhancements**

- **Server voice pipeline**: transcription, transcript clean-up and speech synthesis on the Server, through OpenAI-compatible endpoints, local ones included.
- **Speech to speech**: a live audio conversation that hands anything needing tools, Memory or Approvals to the Agent.
- The design is recorded in [ADR 0013](../adr/0013-voice-providers.md).

</div>
