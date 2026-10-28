---
title: "Desktop speech"
description: "Native speech recognition and synthesis on macOS and Windows."
---

Desktop Dictation and Voice chat use the operating system’s speech recognition
and voices by default. No API key is needed for Device speech. Voice chat can
replace either part with an [OpenAI-compatible Server provider](guides/voice.md#openai-compatible-speech-providers);
Dictation stays on the Device. Voice chat pauses listening while the Agent thinks
and speaks, then resumes; use Pause to cut a spoken reply short.

## macOS

Run `mise run desktop` and allow Microphone and Speech Recognition access when
prompted. This builds and launches an app bundle so macOS can attribute the
permissions correctly. Development requires Xcode command line tools and a
JetBrains Runtime 25 SDK. `mise run desktop -- --compose-hot-reload` uses the JVM
launcher; use the normal launch to validate speech permissions.

Recognition uses the system locale and prefers on-device processing. Apple may
process audio online when on-device recognition is unavailable.

## Windows

Run `mise run desktop` from Git Bash. The task builds the native speech helper
using Windows’ bundled .NET Framework compiler. Requires x64 Windows, .NET
Framework 4.8+ for Device speech, and microphone access for desktop apps in Windows
Settings. Device transcription needs an installed desktop speech recognizer;
Device synthesis needs an installed voice.

Speech uses local Windows SAPI. If no recognizer is available, install a speech
language under **Settings → Time & language → Speech**.

With Server transcription, only microphone access is needed; an installed Device
recognizer is required only for Device transcription and Dictation. Server synthesis
plays WAV audio through the Device output instead of using an installed voice.
