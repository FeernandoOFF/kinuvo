#!/bin/sh
# Build and upload-sign the Play bundle; this does not upload it.
set -eu
cd "$(dirname "$0")/.."
./kotlin package -m android-play -f aab
python3 scripts/sign-android-play.py build/tasks/_android-play_bundleAndroid/gradle-project-release.aab
