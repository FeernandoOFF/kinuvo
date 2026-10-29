---
title: "Server voice models"
description: "Download speech models once on a Server and use them from paired Devices."
---

Open **Settings → Server → Voice** with management access. Download Whisper
Tiny or Base for transcription and Supertonic 3 for spoken replies, then select
each part independently. Downloads never change the selected model automatically.
Settings, model files, language and download progress belong to the selected
Server and are shared by its paired Devices. Model inference currently requires
a macOS Server with the packaged native worker. Windows, Linux and mobile
embedded Servers cannot run these downloaded models.

Models live in `<Server home>/server-voice/models`; selections live in
`server-voice/settings.json`. Existing `device-voice` downloads and desktop
preferences are left untouched. They are no longer used by Dictation or Voice
chat. There is no implicit file copy, deletion or migration. Download and select
models on the Server explicitly.

The catalog contains multilingual quantized Whisper Tiny (103.6 MB), Base
(160.6 MB), and Supertonic 3 (128.8 MB compressed). Whisper is MIT;
Supertonic uses OpenRAIL-M, including its use restrictions. The UI links sources
and licenses. Supertonic currently uses speaker 0. The language picker exposes
31 languages; accuracy and voice quality vary by language and accent.

Downloads use pinned revisions, exact byte counts and SHA256 checks. They stream
to staging directories, validate archive entries and payload sizes, then rename
atomically. Cancel removes staging after the active network read finishes
(up to its timeout). Leaving settings does not cancel a Server download. Removal
and replacement refuse models leased by active or queued inference. Removing a
selected model clears that selection. A missing or damaged selected model raises
an error rather than switching to system speech. Receipts check payload sizes and
modification times after download; they are not a tamper-proof disk monitor.

Dictation and Voice chat query the Server's configuration when they start.
A Device records at most 30 seconds of 16 kHz mono PCM16 and sends that audio to
the selected Server. Synthesized WAV clips return to the Device for playback.
Audio stays in memory and is not added to Session history or written to disk.
The resulting text follows the usual Agent and Session path, including any model
provider configured for the Agent. Audio therefore leaves the Device whenever
Server transcription is configured. System speech is used independently for
parts the Server leaves unset; platform speech services may process audio online.
Configured Server inference failures do not silently fall back to Device speech.

macOS desktop and iOS have audio capture and clip playback adapters. Android and
web can manage Server Voice settings, but do not currently expose Dictation or
Voice chat audio adapters. Windows retains system SAPI speech; it does not yet
support recording or playing Server audio. A configured Server part reports an
unsupported Device error there rather than pretending to use the Server.

Voice chat is half duplex. It closes the microphone while the Agent thinks and
speaks, then listens again. Pause and hold to talk interrupt; speaking over a
reply is not supported. Capture uses an amplitude gate and 1.3 seconds of quiet;
this is not a learned speech detector. Dictation ends when toggled again or after
30 seconds and never sends a message automatically.

## Native Server worker

The UI-free `harness/voice` JVM module owns verified storage and inference.
The C worker uses sherpa-onnx 1.13.8 and ONNX Runtime on CPU, two threads, with
one worker per inference. It unloads weights on exit, has a two-minute deadline,
and is killed on cancellation. Server inference is serialized to bound memory.
Audio RPC bounds are 960,000 input bytes, 20,000 transcript characters, 2,000
synthesis characters and 8 MiB output audio. Ordinary authenticated paired
Devices may infer; only management Devices may inspect or change model storage
and settings. Execution links have no voice access.

On macOS, `mise run server` builds the native worker, and `package-server.sh`
includes the worker, runtime dylibs and license notices inside the standalone
Server jar. The installed Server extracts these to a temporary runtime directory;
it needs no microphone permission, desktop app bundle or developer Xcode tools.
Building requires command line tools and pinned runtime downloads. No model is
included or downloaded automatically. `KINUVO_VOICE_WORKER` can explicitly select
an alternate worker executable for development.

Run routine storage/auth/routing tests with `mise run kotlin -- test -m voice -m
jvm -m app -p jvm`. Set `KINUVO_TEST_SERVER_VOICE_INFERENCE=1` and an absolute `KINUVO_VOICE_WORKER`
path for the explicit
network integration test: cancel a download, download Tiny and Supertonic, select
both on the Server, synthesize through an ordinary paired Device, transcribe the
result, then remove a selection. Microphone quality, speaker audibility and
physical-device permission/route changes still need interactive verification.
Set `KINUVO_TEST_VOICE_JAR` to the absolute packaged jar path to run that proof
against a standalone Server process instead; it extracts its own worker and uses
a temporary isolated home.
