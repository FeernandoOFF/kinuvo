import importlib.util
import io
import unittest
import zipfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('signing', 'scripts/sign-macos.py')
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


def jar(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return output.getvalue()


class MacSigningTest(unittest.TestCase):
    def test_signs_nested_native_without_changing_classes_or_licenses(self):
        native = bytes.fromhex('cffaedfe0c000001') + bytes(64)
        java_class = bytes.fromhex('cafebabe00000034') + bytes(64)
        nested = jar({'darwin/libengine.so': native, 'Example.class': java_class,
                      'DebugProbesKt.bin': java_class, 'META-INF/LICENSE': b'license',
                      'META-INF/UPSTREAM.SF': b'signature', 'META-INF/UPSTREAM.RSA': b'key'})
        source = jar({'BOOT-INF/lib/engine.jar': nested, 'BOOT-INF/classes/Main.class': java_class})

        def sign(path, identity):
            self.assertEqual(identity, 'test-identity')
            self.assertEqual(path.read_bytes(), native)
            path.write_bytes(native + b'signed')

        with patch.object(signing, 'sign', side_effect=sign) as signer:
            result, changed = signing.sign_archive(source, 'test-identity')
        self.assertTrue(changed)
        signer.assert_called_once()
        with zipfile.ZipFile(io.BytesIO(result)) as outer:
            self.assertEqual(outer.getinfo('BOOT-INF/lib/engine.jar').compress_type, zipfile.ZIP_STORED)
            self.assertEqual(outer.read('BOOT-INF/classes/Main.class'), java_class)
            with zipfile.ZipFile(io.BytesIO(outer.read('BOOT-INF/lib/engine.jar'))) as inner:
                self.assertEqual(inner.read('darwin/libengine.so'), native + b'signed')
                self.assertEqual(inner.read('Example.class'), java_class)
                self.assertEqual(inner.read('DebugProbesKt.bin'), java_class)
                self.assertEqual(inner.read('META-INF/LICENSE'), b'license')
                self.assertNotIn('META-INF/UPSTREAM.SF', inner.namelist())
                self.assertNotIn('META-INF/UPSTREAM.RSA', inner.namelist())

    def test_leaves_archive_without_natives_byte_identical(self):
        source = jar({'META-INF/UPSTREAM.SF': b'signature', 'LICENSE': b'license'})
        result, changed = signing.sign_archive(source, 'test-identity')
        self.assertFalse(changed)
        self.assertEqual(result, source)


unittest.main()
