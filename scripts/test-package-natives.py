import importlib.util
import io
import unittest
import zipfile
import tempfile
from pathlib import Path

spec = importlib.util.spec_from_file_location('natives', 'scripts/package-natives.py')
natives = importlib.util.module_from_spec(spec)
spec.loader.exec_module(natives)


class PackageNativesTest(unittest.TestCase):
    def test_platform_matrix(self):
        paths = {
            ('macos', 'arm64'): 'darwin/aarch64/libzstd.dylib',
            ('macos', 'x64'): 'darwin/x86_64/libzstd.dylib',
            ('linux', 'arm64'): 'linux/aarch64/libzstd.so',
            ('linux', 'x64'): 'linux/amd64/libzstd.so',
            ('windows', 'arm64'): 'win/aarch64/libzstd.dll',
            ('windows', 'x64'): 'win/amd64/libzstd.dll',
        }
        for target, keep in paths.items():
            for path in paths.values():
                self.assertEqual(path == keep, natives.target_native(path, *target), (target, path))

    def test_webrtc_classifiers(self):
        paths = {
            ('macos', 'arm64'): 'libwebrtc-java-macos-aarch64.dylib',
            ('macos', 'x64'): 'libwebrtc-java-macos-x86_64.dylib',
            ('linux', 'x64'): 'libwebrtc-java-linux-x86_64.so',
            ('windows', 'x64'): 'webrtc-java-windows-x86_64.dll',
        }
        for target in list(paths) + [('linux', 'arm64')]:
            for platform, name in paths.items():
                source = io.BytesIO()
                with zipfile.ZipFile(source, 'w') as jar:
                    jar.writestr(name, b'native')
                    jar.writestr('META-INF/licenses/webrtc/LICENSE.md', b'license')
                with zipfile.ZipFile(io.BytesIO(natives.prune(source.getvalue(), *target))) as jar:
                    self.assertEqual(name in jar.namelist(), platform == target)
                    self.assertIn('META-INF/licenses/webrtc/LICENSE.md', jar.namelist())

    def test_repack_preserves_classes_and_licenses(self):
        original = io.BytesIO()
        with zipfile.ZipFile(original, 'w') as jar:
            for name in ['Example.class', 'META-INF/LICENSE', 'META-INF/UPSTREAM.SF',
                         'darwin/aarch64/engine.so', 'linux-x86_64/engine.so']:
                jar.writestr(name, b'payload')
        with zipfile.ZipFile(io.BytesIO(natives.prune(original.getvalue(), 'macos', 'arm64'))) as jar:
            self.assertEqual(set(jar.namelist()), {'Example.class', 'META-INF/LICENSE', 'darwin/aarch64/engine.so'})

    def test_tailcat_target_resources(self):
        source = io.BytesIO()
        paths = ['natives/macos-arm64/libKinuvoTailcat.dylib',
                 'natives/macos-x64/libKinuvoTailcat.dylib',
                 'natives/linux-arm64/libKinuvoTailcat.so']
        with zipfile.ZipFile(source, 'w') as jar:
            for name in paths:
                jar.writestr(name, b'native')
        with zipfile.ZipFile(io.BytesIO(natives.prune(source.getvalue(), 'macos', 'arm64'))) as jar:
            self.assertEqual(jar.namelist(), paths[:1])

    def test_tailcat_outer_resources_are_pruned(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.jar'
            with zipfile.ZipFile(path, 'w') as jar:
                jar.writestr('BOOT-INF/classes/natives/macos-arm64/libKinuvoTailcat.dylib', b'mac')
                jar.writestr('BOOT-INF/classes/natives/linux-x64/libKinuvoTailcat.so', b'linux')
                jar.writestr('BOOT-INF/classes/natives/windows-x64/KinuvoTailcat.dll', b'windows')
                jar.writestr('BOOT-INF/classes/natives/LICENSE.tailcat', b'license')
            natives.package(path, 'linux', 'x64')
            with zipfile.ZipFile(path) as jar:
                self.assertEqual(set(jar.namelist()), {
                    'BOOT-INF/classes/natives/linux-x64/libKinuvoTailcat.so',
                    'BOOT-INF/classes/natives/LICENSE.tailcat'})

    def test_on_demand_keeps_api_without_engine(self):
        original = io.BytesIO()
        with zipfile.ZipFile(original, 'w') as jar:
            jar.writestr('Engine.class', b'api')
            jar.writestr('darwin-aarch64/liblitertlm_jni.so', b'native')
        with zipfile.ZipFile(io.BytesIO(natives.prune(original.getvalue(), 'macos', 'arm64', on_demand=True))) as jar:
            self.assertEqual(jar.namelist(), ['Engine.class'])


unittest.main()
