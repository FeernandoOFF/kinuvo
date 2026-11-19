# Native libraries

Unmodified Termux packages (aarch64 and x86_64), renamed to `lib*.so` so Android installs them in the app's native library directory. `libKinuvoTailcat.so` is a symlink to the Tailcat build in `libs/tailcat/prebuilt`.

| File | Termux package | Upstream source | Recipe |
|---|---|---|---|
| `libproot.so`, `libproot-loader.so` | proot 5.1.107.102 | https://github.com/termux/proot/tree/52d65a4dc140ca554d241a1438ddd4f6b00c9813 (tag `v5.1.107.102`) | https://github.com/termux/termux-packages/blob/master/packages/proot/build.sh |
| `libtalloc.so` | libtalloc 2.5.0 | https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz | https://github.com/termux/termux-packages/blob/master/packages/libtalloc/build.sh |
| `libandroid-shmem.so` | libandroid-shmem 0.7 | https://github.com/termux/libandroid-shmem/tree/v0.7 | https://github.com/termux/termux-packages/blob/master/packages/libandroid-shmem/build.sh |

PRoot is GPL-2.0-or-later and talloc is LGPL-3.0-or-later; their source is at the links above. Licenses are listed in `client/app/src/settings/OpenSourceLicenses.kt`.
