#!/usr/bin/env python3
"""Check production PCM conversion and recording lifecycle without microphone hardware."""
from pathlib import Path
import subprocess
import tempfile
import glob

root = Path(__file__).resolve().parents[3]
source = (root / 'shared/ui/src@ios/AppleSpeech.swift').read_text()
helper = source[source.index('enum ServerAudioPCM {'):source.index('private final class AppleServerAudio:')]
lifecycle = source[source.index('private final class AppleServerAudio:'):]
# Shorten only watchdogs, keeping native work unchanged for deterministic timeout checks.
lifecycle = lifecycle.replace('.now() + 8,', '.now() + 0.15,')
lifecycle = lifecycle.replace('    private let queue =', '    func synchronizeForTests() { queue.sync {} }\n    private let queue =')
start = lifecycle.index('    private func session() throws {')
end = lifecycle.index('    func recordOnQueue(', start)
lifecycle = lifecycle[:start] + '    private func session() throws {}\n' + lifecycle[end:]
for name in ['AVAudioEngine', 'AVAudioApplication', 'AVAudioSession']:
    lifecycle = lifecycle.replace(name, name.replace('AV', 'Test', 1))
# The notification name belongs to Foundation, not the engine stand-in.
lifecycle = lifecycle.replace('.TestAudioEngineConfigurationChange', '.AVAudioEngineConfigurationChange')
failure = source[source.index('private struct Failure: Error {'):source.index('/**', source.index('private struct Failure: Error {'))]
with tempfile.TemporaryDirectory(prefix='kinuvo-server-audio-') as directory:
    compiler = sorted(glob.glob(str(Path.home() / 'Library/Caches/JetBrains/Kotlin/extract.cache/*/bin/kotlinc-native')))[-1]
    framework = Path(directory) / 'AudioBridge'
    subprocess.run([compiler, str(root / 'shared/ui/src@ios/AudioBytes.ios.kt'),
                    '-target', 'macos_arm64', '-produce', 'framework', '-o', str(framework)], check=True)
    main = Path(directory) / 'main.swift'
    main.write_text('import AVFoundation\nimport AudioBridge\n' + helper + failure + lifecycle
                    + Path(__file__).with_name('ServerAudioPCMTests.swift').read_text()
                    + Path(__file__).with_name('ServerAudioLifecycleTests.swift').read_text()
                    + Path(__file__).with_name('AudioBytesTests.swift').read_text())
    binary = Path(directory) / 'tests'
    subprocess.run(['xcrun', 'swiftc', str(main), '-F', directory, '-framework', 'AudioBridge',
                    '-Xlinker', '-rpath', '-Xlinker', directory, '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
