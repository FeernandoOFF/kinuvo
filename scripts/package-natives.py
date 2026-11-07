#!/usr/bin/env python3
"""Keep target natives in a Spring Boot executable jar; nested jars must remain STORED."""
import argparse
import io
import re
import zipfile
from pathlib import Path


def target_native(name, os, arch):
    name = '/' + name.lower()
    foreign_cpu = ('x86_64', 'amd64', 'x64', 'i386', '/x86/', 'ppc', 's390', 'riscv', 'loong', 'mips') if arch == 'arm64' else ('aarch64', 'arm64', 'i386', '/x86/', 'ppc', 's390', 'riscv', 'loong', 'mips')
    if any(c in name for c in foreign_cpu):
        return False
    systems = {'macos': ('macos', 'darwin', 'osx', '.dylib'), 'linux': ('linux',), 'windows': ('windows', '/win/', '.dll')}
    for system, tokens in systems.items():
        if system != os and any(t in name for t in tokens):
            return False
    if any(t in name for t in ('freebsd', 'aix/', 'android', '/arm/')):
        return False
    return True


def prune(data, os, arch, on_demand=False):
    result = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as source, zipfile.ZipFile(result, 'w') as out:
        removed = False
        for entry in source.infolist():
            if re.search(r'\.(so(\.\d+)*|dylib|dll|jnilib)$', entry.filename) and (not target_native(entry.filename, os, arch) or on_demand):
                removed = True
                continue
            out.writestr(entry, source.read(entry))
    if not removed:
        return data
    # Modifying a signed jar invalidates its signature, not its license notices.
    cleaned = io.BytesIO()
    with zipfile.ZipFile(result) as source, zipfile.ZipFile(cleaned, 'w') as out:
        for entry in source.infolist():
            if not re.match(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)$', entry.filename, re.I):
                out.writestr(entry, source.read(entry))
    return cleaned.getvalue()


def package(path, os, arch):
    temporary = path.with_suffix('.tmp')
    with zipfile.ZipFile(path) as source, zipfile.ZipFile(temporary, 'w') as out:
        for entry in source.infolist():
            data = source.read(entry)
            if entry.filename.startswith('BOOT-INF/lib/') and entry.filename.endswith('.jar'):
                data = prune(data, os, arch, on_demand="/litertlm-jvm-" in entry.filename)
                if len(data) != entry.file_size:
                    print(f'{entry.filename}: {entry.file_size} -> {len(data)} bytes')
                entry.compress_type = zipfile.ZIP_STORED
            out.writestr(entry, data)
    temporary.replace(path)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path)
    parser.add_argument('os', choices=['macos', 'linux', 'windows'])
    parser.add_argument('arch', choices=['arm64', 'x64'])
    args = parser.parse_args()
    package(args.jar, args.os, args.arch)
