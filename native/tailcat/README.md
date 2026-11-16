# Tailcat shim

Build with Go 1.27.1+ and a C toolchain using ../../scripts/build-tailcat.sh [arm64|x64].
macOS supports both architectures with the installed Apple SDK. Linux cross builds
require a matching CC; by default the script builds for the host architecture.

ABI 1 exports kinuvo_tailcat_abi_version() -> int,
kinuvo_tailcat_call(method, params) -> UTF-8 JSON string, and
kinuvo_tailcat_free(string). All strings passed to call are borrowed for that call;
the returned string must be freed, including error responses.

Methods:
- serve: {"state":"/private/server/home/tailcat","port":12345} -> {"handle":1,"address":"tc…"}
- forward: {"address":"tc…"} -> {"handle":2,"port":12346}
- stop: {"handle":1} -> {}
- reset: {"state":"/private/server/home/tailcat"} -> {}

Failures return {"error":"…"} without credentials. Handles are process-local.
Stop is idempotent and bounded to two seconds; a timed-out native close may remain
in the process until exit. Do not unload the library. Reset requires exclusive
access to the state directory and must follow shutdown, never key deletion while
a Server is active. Callers should use the dedicated untrusted listener as the
serve target, not a listener configured to trust loopback proxy headers.

Tests use temporary directories and local sockets. KINUVO_TAILCAT_LIVE=1 opts into
the public relay test; the default tests do not contact relays.

Android: `mise run exec -- scripts/build-tailcat.sh android` uses NDK
28.2.13676358 (API 26) from ANDROID_HOME/ANDROID_SDK_ROOT, or ANDROID_NDK_HOME.
It builds arm64-v8a and x86_64 sequentially with cgo and 16 KB ELF page alignment.
The Android-only C wrapper exports JNI methods in the same libKinuvoTailcat.so;
desktop exports and builds are unchanged. syscall.Flock is supplied by Go's
Android/Linux syscall implementation.

Kotlin Toolchain 0.13.0's SourceAndroidConventionPaths uses the module-root
`jniLibs/<abi>/` directory for app packaging. Its library AAR writer does not
include local JNI directories, so the script builds once under
`build/tailcat/android-<arch>/` and stages ignored copies in both
`apps/android/jniLibs/` and `apps/android-play/jniLibs/`. Never commit these outputs. The shared Kotlin interface and
JSON/handle ownership live in src@jvmAndAndroid; src@android uses JNI and src@jvm
uses FFM. Native calls must run on an IO dispatcher.
