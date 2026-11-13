#!/usr/bin/env python3
"""Sign a desktop app from the embedded natives out to the enclosing bundle."""
import argparse
import io
import os
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path


MAGIC = {bytes.fromhex(value) for value in (
    'feedface', 'cefaedfe', 'feedfacf', 'cffaedfe', 'cafebabe', 'bebafeca', 'cafebabf', 'bfbafeca')}
ROOT = Path(__file__).resolve().parent.parent


def macho(data):
    # Fat Mach-O and Java classes share a magic number; ask file to distinguish them.
    return data[:4] in MAGIC and b'Mach-O' in subprocess.run(
        ['file', '-b', '-'], input=data, capture_output=True, check=True).stdout


def sign(path, identity, entitlements=None):
    args = ['codesign', '--force', '--timestamp', '--options', 'runtime', '--sign', identity]
    if os.environ.get('KINUVO_MACOS_KEYCHAIN'):
        args += ['--keychain', os.environ['KINUVO_MACOS_KEYCHAIN']]
    if entitlements:
        args += ['--entitlements', str(entitlements)]
    subprocess.run([*args, str(path)], check=True)
    subprocess.run(['codesign', '--verify', '--strict', str(path)], check=True)


def sign_archive(data, identity):
    entries = []
    changed = False
    with zipfile.ZipFile(io.BytesIO(data)) as source:
        for entry in source.infolist():
            content = source.read(entry)
            if entry.filename.endswith('.jar'):
                content, nested_changed = sign_archive(content, identity)
                changed |= nested_changed
                entry.compress_type = zipfile.ZIP_STORED
            elif entry.filename.endswith(('.dylib', '.jnilib', '.so')) and macho(content):
                with tempfile.TemporaryDirectory(prefix='kinuvo-native-sign-') as temporary:
                    native = Path(temporary) / Path(entry.filename).name
                    native.write_bytes(content)
                    sign(native, identity)
                    content = native.read_bytes()
                changed = True
                print('Signed embedded native:', entry.filename, flush=True)
            entries.append((entry, content))
    if not changed:
        return data, False
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as target:
        for entry, content in entries:
            if not re.match(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)$', entry.filename, re.I):
                target.writestr(entry, content)
    return output.getvalue(), True


def sign_app(bundle, identity):
    entitlements = ROOT / 'apps/desktop/entitlements.plist'
    worker_entitlements = ROOT / 'apps/desktop/voice/entitlements.plist'
    jar = bundle / 'Contents/app/kinuvo-desktop.jar'
    data, changed = sign_archive(jar.read_bytes(), identity)
    if changed:
        temporary = jar.with_suffix('.signed.jar')
        temporary.write_bytes(data)
        temporary.replace(jar)
    for path in sorted(bundle.rglob('*')):
        if not path.is_file() or path.is_symlink():
            continue
        with path.open('rb') as stream:
            header = stream.read(8)
        if not macho(header):
            continue
        if path.name == 'KinuvoVoiceInference':
            sign(path, identity, worker_entitlements)
        elif path.name in ('Kinuvo', 'java', 'jspawnhelper'):
            sign(path, identity, entitlements)
        else:
            sign(path, identity)
    for path in sorted(bundle.rglob('*'), key=lambda p: len(p.parts), reverse=True):
        if path.is_dir() and not path.is_symlink() and path.suffix in ('.app', '.framework', '.bundle'):
            sign(path, identity, entitlements if path.suffix == '.app' else None)
    sign(bundle / 'Contents/runtime', identity, entitlements)
    sign(bundle, identity, entitlements)
    subprocess.run(['codesign', '--verify', '--deep', '--strict', '--verbose=2', str(bundle)], check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('identity')
    args = parser.parse_args()
    sign_app(args.bundle, args.identity)
