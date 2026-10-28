#!/bin/sh
set -eu
kinuvo_project_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
. "$kinuvo_project_root/scripts/project-env.sh"
bundle="$kinuvo_project_root/build/KinuvoSpeech.app"
source="$kinuvo_project_root/apps/desktop/speech/main.swift"
clips="$kinuvo_project_root/shared/ui/src@ios/NativeVoiceAudio.swift"
identity=dev.kinuvo.speech
if [ -f "$kinuvo_project_root/.kinuvo-home" ]; then
    identity="$identity.local$(printf %s "$kinuvo_project_root" | shasum | cut -c1-10)"
fi
mkdir -p "$bundle/Contents/MacOS"
if [ ! -x "$bundle/Contents/MacOS/KinuvoSpeech" ] || [ "$source" -nt "$bundle/Contents/MacOS/KinuvoSpeech" ] || [ "$clips" -nt "$bundle/Contents/MacOS/KinuvoSpeech" ] || [ "$0" -nt "$bundle/Contents/MacOS/KinuvoSpeech" ]; then
    cat > "$bundle/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>$identity</string>
<key>CFBundleExecutable</key><string>KinuvoSpeech</string>
<key>CFBundleName</key><string>Kinuvo Voice</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>LSUIElement</key><true/>
<key>NSMicrophoneUsageDescription</key><string>Kinuvo listens while Voice chat or Dictation is active.</string>
<key>NSSpeechRecognitionUsageDescription</key><string>Kinuvo turns speech into messages for your Agent. Apple may process audio when on-device recognition is unavailable.</string>
</dict></plist>
PLIST
    swiftc -O -module-cache-path "$kinuvo_project_root/build/swift-module-cache" "$source" "$clips" -o "$bundle/Contents/MacOS/KinuvoSpeech"
    codesign --force --sign - --identifier "$identity" "$bundle" >&2
fi
printf '%s\n' "$bundle/Contents/MacOS/KinuvoSpeech"
