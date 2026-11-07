#!/usr/bin/env python3
"""Check the decoded Play manifest and 64-bit native ELF page alignment."""
import argparse
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('bundle', type=Path)
parser.add_argument('manifest', type=Path, help='bundletool dump manifest output')
args = parser.parse_args()
a = '{http://schemas.android.com/apk/res/android}'
root = ET.parse(args.manifest).getroot()
assert root.get('package') == 'com.feernandooff.kinuvo'
assert root.find('uses-sdk').get(a + 'minSdkVersion') == '26'
assert root.find('uses-sdk').get(a + 'targetSdkVersion') == '36'
for service in root.findall('.//service'):
    assert 'ComputerService' not in service.get(a + 'name', '')
    assert service.get(a + 'permission') != 'android.permission.BIND_ACCESSIBILITY_SERVICE'
for permission in root.findall('uses-permission'):
    assert permission.get(a + 'name') not in {'android.permission.WRITE_SECURE_SETTINGS', 'android.permission.TURN_SCREEN_ON'}
checked = 0
failures = []
with zipfile.ZipFile(args.bundle) as bundle:
    assert not any('computer_use' in n for n in bundle.namelist())
    assert any(n.startswith('META-INF/') and n.endswith('.RSA') for n in bundle.namelist())
    for name in bundle.namelist():
        if not name.endswith('.so') or not any('/' + abi + '/' in name for abi in ['arm64-v8a', 'x86_64']):
            continue
        data = bundle.read(name)
        assert data[:5] == b'\x7fELF\x02', name
        endian = '<' if data[5] == 1 else '>'
        offset = struct.unpack_from(endian + 'Q', data, 32)[0]
        size, count = struct.unpack_from(endian + 'HH', data, 54)
        for index in range(count):
            fields = struct.unpack_from(endian + 'IIQQQQQQ', data, offset + index * size)
            if fields[0] == 1 and (fields[7] < 16384 or fields[2] % 16384 != fields[3] % 16384):
                failures.append(name)
        checked += 1
print(f'Manifest: {root.get("package")} {root.get(a + "versionName")} ({root.get(a + "versionCode")}); SDK 26/36; Accessibility excluded.')
print(f'Checked {checked} 64-bit native ELF libraries for 16 KB load-segment alignment.')
assert checked > 0
assert not failures, 'Misaligned libraries: ' + ', '.join(sorted(set(failures)))
print('ELF alignment passed. Bundle ZIP configuration and actual 16 KB Device execution require separate checks.')
