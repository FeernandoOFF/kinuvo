"""Sets android:extractNativeLibs="true" in a built APK, so proot can run from nativeLibraryDir.

Usage: android-extract-native-libs.py <in.apk> <out.apk>, then zipalign and apksigner."""
import sys, zipfile, struct
src, dst = sys.argv[1:3]
zin = zipfile.ZipFile(src)
m = bytearray(zin.read('AndroidManifest.xml'))
# find resource map chunk
off = 8; idx = None
while off < len(m):
    t, hs, sz = struct.unpack_from('<HHI', m, off)
    if t == 0x0180:
        ids = struct.unpack_from('<%dI' % ((sz-8)//4), m, off+8)
        idx = ids.index(0x010104ea) if 0x010104ea in ids else None
    off += sz
n = 0
for i in range(len(m)-20):
    ns, name, raw, size, res0, typ, data = struct.unpack_from('<iiiHBBI', m, i)
    if name == idx and size == 8 and typ == 0x12 and ns >= 0 and data == 0 and raw == -1:
        struct.pack_into('<I', m, i+16, 0xFFFFFFFF); n += 1
if not n: sys.exit('extractNativeLibs not found in the manifest')
print('patched', n)
zout = zipfile.ZipFile(dst, 'w')
for it in zin.infolist():
    if it.filename.startswith('META-INF/') and it.filename.endswith(('.SF','.RSA','.MF')): continue
    data = bytes(m) if it.filename == 'AndroidManifest.xml' else zin.read(it.filename)
    zout.writestr(it, data, compress_type=it.compress_type)
zout.close()
