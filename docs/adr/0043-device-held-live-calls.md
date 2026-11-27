---
title: "Device-held live calls"
description: "The Device holds the WebRTC call to the realtime model; the Server only creates it and controls it over a sideband WebSocket."
---

Status: Accepted

## Context

Live voice through the owner's ChatGPT subscription used a WebRTC call held by the Server and relayed as PCM to the Device. That needs a native WebRTC library, so only JVM Servers offered it; the Server embedded in the Android and iOS apps did not.

## Decision

- The Device owns the WebRTC peer (microphone and speaker) and sends its complete, max-bundle offer in `voice.call.start`. The Server creates the call with the ChatGPT token, which never reaches the Device, and returns the answer SDP.
- The Server controls the call over a sideband WebSocket: delegations run through the Session's Agent, replies and notifications go back as context, and pause, resume and end are commands. The Device reports its peer state (`voice.call.peer`); the call ends with an error when the peer fails.
- `LiveCallProvider` in `server/plugin-api` is the vendor-neutral seam, one adapter per vendor protocol. The Codex adapter is common Ktor HTTP and WebSocket code, so every Server host offers it, and plugins offer it through `ServerPlugin.liveCalls`. `VoiceSetup.liveCalls` tells Devices it is available; endpoints and keys stay on the Server.
- Transcripts reach the Session as one Voice call block, as before.

## Consequences

- No audio crosses the Server, and Servers need no WebRTC library.
- The Server-held path (`SpeechToSpeech`, `voice.live.*`) stays until Devices move to Device-held calls, then is removed.
- Devices need a WebRTC stack on every platform.
