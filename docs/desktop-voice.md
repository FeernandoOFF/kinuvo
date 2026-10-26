---
title: "Desktop speech"
description: "Native speech recognition and synthesis on macOS and Windows."
---

The desktop Device supplies `SpeechEngine` through its platform Koin module. The
existing Dictation and Voice chat controls use the same `Transcriber` and
`Synthesizer` interfaces as iOS. No Server speech provider or API key is needed.
Linux keeps its existing behavior, with no local speech engine.

Both desktop engines use half duplex: the microphone is released while the Agent
thinks and speaks, then listening resumes. There is no echo cancellation or
barge-in. Dictation keeps listening through pauses until stopped. No floating
window or background conversation UI is included.

## macOS

A bundled, signed `KinuvoSpeech.app` uses `SFSpeechRecognizer`, `AVAudioEngine` and
`AVSpeechSynthesizer`. It prefers on-device recognition when available; otherwise
Apple may process audio over the network. It uses the current system locale.
Allow Speech Recognition and Microphone access when prompted. Missing microphones,
unavailable recognizers and denied access are reported through the shared voice
state.

Run `mise run desktop` from a pinned checkout. It builds an app image and launches
it with Launch Services, the checkout's data home and port, and a distinct local
bundle identity. A bare JVM launched from a terminal cannot reliably attribute
macOS privacy requests. `mise run desktop -- --compose-hot-reload` retains the
JVM development launcher, so use the normal packaged launch to validate speech
permissions. Xcode command line tools and a JetBrains Runtime 25 SDK
are required. Release packaging embeds the helper, sets both privacy usage strings
on the parent app, and signs the generated app image ad hoc; distribution signing
and notarization remain separate release work.

## Windows

The bundled x64 `KinuvoSpeech.exe` uses desktop SAPI through .NET Framework 4.x
`System.Speech`: `SpeechRecognitionEngine` with a dictation grammar and default
microphone, and `SpeechSynthesizer` with the default output device. It chooses an
installed recognizer matching the UI language, falling back to the first installed
recognizer. No audio is sent to a Server by this adapter.

This is an ordinary executable in the existing jpackage app image/ZIP, without an
MSIX identity or WinRT speech capabilities. Microsoft's [console recognition
example](https://learn.microsoft.com/en-us/dotnet/api/system.speech.recognition.speechrecognitionengine?view=netframework-4.8.1)
uses the same desktop API and documents installed recognizers, dictation grammar,
microphone and WAV inputs. The [synthesis API](https://learn.microsoft.com/en-us/dotnet/api/system.speech.synthesis.speechsynthesizer?view=netframework-4.8.1)
supports default audio output and WAV files.

Build with `powershell -NoProfile -ExecutionPolicy Bypass -File
scripts/build-windows-speech.ps1`, or run `mise run desktop` from Git Bash in a
pinned checkout. Packaging builds the helper automatically with Windows' .NET
Framework 4.x C# compiler; no .NET SDK or NuGet download is needed. Requires x64
Windows, .NET Framework 4.8+, an installed desktop speech recognizer and voice,
and microphone access for desktop apps in Windows Settings. Errors identify
missing speech languages or audio access rather than silently using another
provider.

## Helper contract and checks

The JVM adapter sends UTF-8 JSON lines on stdin and reads events on stdout.
`authorize`, `listen`, `stop`, `speak` and `stopSpeaking` commands carry request IDs;
`authorized`, `partial`, `final`, `error` and `done` events retain those IDs.
Callbacks run on the Swing UI thread. Replaced/canceled requests are ignored;
stopping speech completes pending callbacks once, and helper failure ends active
speech with an error. Closing the desktop process terminates its helper.
`KINUVO_SPEECH_HELPER` can point at a separately built helper for diagnostics.

Run `mise run kotlin -- test -m app -p jvm` and `mise run kotlin -- test -m ui -p
jvm` for shared lifecycle and process adapter checks. These use test doubles and
do not prove native microphone or speaker operation. Windows CI additionally
builds the helper and runs `build/KinuvoSpeech.exe --self-test`: real native
synthesis to a WAV, then real native dictation recognition of that audio. This
checks unpackaged SAPI operation, not physical microphone capture, privacy
settings, or audible speaker playback. Interactive validation on Windows remains
necessary for those device-dependent paths.
