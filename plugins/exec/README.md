# exec

Exec backends (host, container, SSH) and the shell and file tools.

## Android Linux environment

On Android, the host backend runs in an Alpine rootfs through proot (`src@android/ProotLinux.kt`). The app downloads the rootfs on first use. proot, talloc, the loader and libandroid-shmem are Termux builds shipped as `lib*.so` in `apps/android/jniLibs`, because Android only executes files from the app's native library directory.

That needs `extractNativeLibs`, which AGP rejects in the manifest and Amper can't set, so the full app's APK is patched after the build:

```sh
./kotlin build -m android -p android
python3 -I scripts/android-extract-native-libs.py build/tasks/_android_buildAndroidDebug/gradle-project-debug.apk un.apk
zipalign -f -p 4 un.apk al.apk
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --out kinuvo.apk al.apk
adb install -r --no-incremental kinuvo.apk
```

Without the patch, `prootLinux` returns null and the phone has no Exec or ACP harnesses. Release builds don't patch yet.

The same runtime launches ACP harnesses (`apps/android-embedded/src/ProotAcpLauncher.kt`); their commands and working folders are paths inside the rootfs.
