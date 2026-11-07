import importlib.util
import io
import unittest
import zipfile

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

    def test_repack_preserves_classes_and_licenses(self):
        original = io.BytesIO()
        with zipfile.ZipFile(original, 'w') as jar:
            for name in ['Example.class', 'META-INF/LICENSE', 'META-INF/UPSTREAM.SF',
                         'darwin/aarch64/engine.so', 'linux-x86_64/engine.so']:
                jar.writestr(name, b'payload')
        with zipfile.ZipFile(io.BytesIO(natives.prune(original.getvalue(), 'macos', 'arm64'))) as jar:
            self.assertEqual(set(jar.namelist()), {'Example.class', 'META-INF/LICENSE', 'darwin/aarch64/engine.so'})


unittest.main()
