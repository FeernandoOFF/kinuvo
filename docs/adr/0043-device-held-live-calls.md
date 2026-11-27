---
title: "Device-held live calls"
description: "The Device holds the WebRTC call to the realtime model; the Server only creates it and controls it over a sideband WebSocket."
---

Status: Accepted. The Server-held path described below as the previous design has been removed.

## Context

Live voice through the owner's ChatGPT subscription used a WebRTC call held by the Server and relayed as PCM to the Device. That needs a native WebRTC library, so only JVM Servers offered it; the Server embedded in the Android and iOS apps did not.

## Decision

- The Device owns the WebRTC peer (microphone and speaker) and sends its complete, max-bundle offer in `voice.call.start`. The Server creates the call with the ChatGPT token, which never reaches the Device, and returns the answer SDP.
- The Server controls the call over a sideband WebSocket: delegations run through the Session's Agent, replies and notifications go back as context, and pause, resume and end are commands. The Device reports its peer state (`voice.call.peer`); the call ends with an error when the peer fails.
- `LiveCallProvider` in `server/plugin-api` is the vendor-neutral seam, one adapter per vendor protocol. The Codex adapter is common Ktor HTTP and WebSocket code, so every Server host offers it, and plugins offer it through `ServerPlugin.liveCalls`. `VoiceSetup.liveCalls` tells Devices it is available; endpoints and keys stay on the Server.
- Transcripts reach the Session as Voice call blocks: what was said before a delegation is saved before it, so the Session reads in the order things happened.
- Replies are either progress or the final answer. An answer is delivered only when no turn is open, a model that stays silent after it is nudged once, and progress never asks to be spoken. The Agent's run belongs to the Server, so it carries on after the call ends and its answer lands in the Session.

## Consequences

- No audio crosses the Server, and Servers need no WebRTC library.
- The Server-held path is gone: `SpeechToSpeech`, `CodexRealtime`, the `voice.live.start`/`end` methods, the `voice.live.input`/`output` events and `VoiceSetup.speechToSpeech`. Only the owner's switch (`voice.live.settings`, `voice.live.save`) keeps its name, and `LiveVoiceSettings.available` now means the Server has a live call provider.
- Compatibility follows [ADR 0037](0037-protocol-version-negotiation.md): additive and removed fields are not a version change. A Device ignores `speechToSpeech` from an older Server and uses speech-based Voice chat, as does a Device against a Server without `liveCalls`. A Server answers `voice.live.start` from an older Device with an unknown-method error, which the Device only sends when `speechToSpeech` was offered, so it does not.
- Devices need a WebRTC stack on every platform.
