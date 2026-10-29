from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[5]
bridge = root / 'shared/ui/native/macos/Bridge.swift'
with tempfile.TemporaryDirectory(prefix='kinuvo-speech-close-') as directory:
    directory = Path(directory)
    stub = directory / 'Speech.swift'
    stub.write_text('''import Foundation
final class Speech {
    init(_ emit: @escaping (String, Int, String) -> Void) {}
    func handle(_ command: [String: Any]) {}
}
''')
    main = directory / 'main.swift'
    main.write_text('''import Foundation
let handle = createSpeech()
let returned = DispatchSemaphore(value: 0)
DispatchQueue.global().async {
    closeSpeech(handle)
    returned.signal()
}
// Model AppKit waiting for JVM shutdown: its main queue cannot service cleanup.
precondition(returned.wait(timeout: .now() + 2) == .success, "Native close waited for the blocked main queue")
print("Native close returned while the AppKit main queue was blocked")
''')
    binary = directory / 'test'
    subprocess.run(['xcrun', 'swiftc', '-module-cache-path', str(root / 'build/swift-module-cache'), str(bridge), str(stub), str(main), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True, timeout=5)
