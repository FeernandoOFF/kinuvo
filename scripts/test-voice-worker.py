#!/usr/bin/env python3
"""Probe the built worker against the exact hash-pinned engine archive."""
import hashlib
import io
import pathlib
import platform
import subprocess
import tarfile
import tempfile
import urllib.request

platforms = {
    'Linux': ('linux-x64-shared-lib', '3892d184be41027e18165e67f549cd4e4cdd8dcd73ac5579e97afd55e14e30b6', 'linux-x64/KinuvoVoiceInference'),
    'Windows': ('win-x64-shared-MT-Release-lib', 'b8eedf41bd6d3779218887b48367bb7a3ece5aaa7667f01f69ee823a12b0a9e7', 'windows-x64/KinuvoVoiceInference.exe'),
    'Darwin': ('osx-universal2-shared-lib', 'c1cca5b4a1867543e27d42fbea513983e73f629d0aa53b43b51555a71d2108d9', 'MacOS/KinuvoVoiceInference'),
}
archive, digest, worker = platforms[platform.system()]
url = f'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-{archive}.tar.bz2'
data = urllib.request.urlopen(url, timeout=120).read()
assert hashlib.sha256(data).hexdigest() == digest
with tempfile.TemporaryDirectory() as directory:
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:bz2') as tar:
        for member in tar:
            if member.isfile() and '/lib/' in member.name and member.name.endswith(('.so', '.dll', '.dylib')):
                pathlib.Path(directory, pathlib.PurePosixPath(member.name).name).write_bytes(tar.extractfile(member).read())
    subprocess.run([str(pathlib.Path('build/voice-worker', worker).resolve()), directory, 'probe'], check=True, timeout=30)
