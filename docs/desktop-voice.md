---
title: "Desktop speech"
description: "Native speech recognition and synthesis on macOS and Windows."
---

Desktop Dictation and Voice chat default to the operating system’s speech recognition
and voices. A macOS Server also offers [downloaded voice models](device-local-voice.md)
under Settings → Server → Voice, shared by paired macOS and iOS Devices. No API key is needed. Voice chat pauses listening while the Agent
thinks and speaks, then resumes; interrupting spoken replies uses Pause or hold to talk; speaking over replies is not supported.

## macOS

Run `mise run desktop` and allow Microphone and Speech Recognition access when
prompted. This builds and launches an app bundle so macOS can attribute the
permissions to Kinuvo itself. Capture, Apple recognition, system speech and WAV playback run
in the app process through `libKinuvoSpeech.dylib`, loaded using Java 25’s foreign
function API. Only downloaded-model inference uses a compute worker. The app
checks permission status when a voice action begins and reports denied and
restricted access separately. Development requires Xcode command line tools and a
JetBrains Runtime 25 SDK. `mise run desktop -- --compose-hot-reload` uses the JVM
launcher; use the normal launch to validate speech permissions.

Recognition uses the system locale and prefers on-device processing. Apple may
process audio online when on-device recognition is unavailable.

## Windows

Run `mise run desktop` from Git Bash. The task builds the native speech helper
using Windows’ bundled .NET Framework compiler. Requires x64 Windows, .NET
Framework 4.8+, an installed desktop speech recognizer and voice, and microphone
access for desktop apps in Windows Settings.

Speech uses local Windows SAPI. If no recognizer is available, install a speech
language under **Settings → Time & language → Speech**.

## macOS bridge regression check

After `mise run exec -- ./scripts/build-mac-speech.sh`, run:

```sh
KINUVO_TEST_NATIVE_SPEECH="$PWD/build" mise run kotlin -- test -m ui -p jvm \
  --include-classes='kinuvo.ui.Desktop*SpeechTest' \
  --jvm-args=--enable-native-access=ALL-UNNAMED
```

The native test exercises command dispatch and an invalid WAV error through the
loaded library without requesting microphone access. Permission attribution must
be checked using the bundled app launched by `mise run desktop`; an unbundled test
JVM does not prove app authorization.
