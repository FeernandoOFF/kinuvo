# libs/tailcat

Native Tailcat transport: a Go shim over [TailPocket](https://github.com/jt-wang/tailpocket) v0.2.4 (MIT, on Tailcat v0.6.0), prebuilt binaries published as GitHub Release assets, and the Kotlin bindings. `libs/` is the home for prebuilt native libraries; CI and releases never need Go or the NDK.

- `go/`: the shim source (`go test ./...`; `KINUVO_TAILCAT_LIVE=1` adds a ~30 s live relay test).
- `prebuilt/` (git-ignored): macOS arm64 and x64 dylibs, glibc-based Linux arm64 and x64 shared objects, a Windows x64 DLL, Android arm64-v8a and x86_64 shared objects, and `KinuvoTailcat.xcframework` for iOS devices and simulators. The desktop libraries target macOS 13 and glibc 2.28. The `mise` tasks, the iOS “Fetch native libraries” build phase, and CI use `scripts/libs.sh fetch` to download the files pinned in `prebuilt.json`. Run `mise run libs:fetch` before calling `./kotlin` directly if you need the published binaries.
- `prebuilt.json`: the `libs-tailcat-<stamp>` release tag plus URL and sha256 of every file (the xcframework is one zip). Written by `scripts/libs.sh publish tailcat`.
- `STAMP`: hash of `go/*.go`, `go/*.c`, go.mod, go.sum and the Go and NDK versions. `stamp.sh check` verifies it, and that `prebuilt.json` is published for it, without any toolchain; it runs in CI.
- `build.sh [macos] [linux] [windows] [android] [ios]` defaults to all targets and rewrites `STAMP`. On a Mac, run `mise exec go@1.27.3 zig@0.15.2 -- libs/tailcat/build.sh` with Xcode and NDK 28.2.13676358 installed. Zig cross-compiles the Linux and Windows libraries. Build all targets before publishing with `scripts/libs.sh publish tailcat`, then commit `STAMP` and `prebuilt.json`.
- Kotlin module (`src`, `src@jvm`, `src@android`, `src@ios`): implements the SDK's `TailcatForwarder` and the Server's `TailcatServing`; only app shells depend on it. FFM on the JVM, JNI on Android, and on iOS a Swift wrapper (`apps/ios/src/AppleTailcat.swift`) installs the linked library's entry point through `installTailcat`.

Android apps pick up the fetched libraries through symlinks in `apps/android/jniLibs` and `apps/android-play/jniLibs`. The iOS project links the xcframework with `-lresolv -framework Security -framework CoreFoundation`.

## ABI 3

Exports `kinuvo_tailcat_abi_version() -> int`, `kinuvo_tailcat_call(method, params) -> UTF-8 JSON` and `kinuvo_tailcat_free(string)`. Strings passed to `call` are borrowed for the call; the returned string must be freed, errors included. Failures return `{"error":"…"}` with a fixed, non-secret message. Handles are process-local; `stop` is idempotent and bounded to two seconds. Never unload the library.

Server (host) methods use `<home>/tailcat` for `state`. This directory holds a lock file and `state.json`, which contains the private identity and paired phone keys. State files use owner-only permissions on Unix and inherit the directory ACL on Windows.
- `host_start {state,name,port}` -> `{handle}`: serves paired phones, proxying each connection to loopback `port`.
- `host_pair {handle,code,payload,ttl,boundPhones}` -> `{pairing,invite,expiresAt}`: opens a one-phone pairing window; the phone receives `payload`, and the host remembers which phone used `code`.
- `host_pair_open {handle,pairing}` -> `{open}`; `host_close_pair {handle,pairing}`.
- `host_phone {handle,code}` -> `{phone}`: the phone key bound to `code`, once.
- `host_peer {handle,port}` -> `{phone}`: the verified phone on the bridge with loopback source `port`.
- `host_revoke {handle,phone}`; `revoke {state,phone}` records it in `revoked.json` while no host runs. A revoked phone stays allowed at the Tailcat level so its connections reach the shim, which gives open bridges one second to deliver the revoke reply and immediately answers new connections like a Server answers a revoked Device (the `/ws` upgrade succeeds, the first request frame fails `forbidden`, other requests get 401) without ever proxying them; connecthost's own `Revoke` only closes silently, which a phone cannot tell from a Server that is down. When the paired list is full, opening an invite removes the oldest revoked phone or the oldest phone without a Device binding, preserving all bound phones.
- `reset {state}`: deletes the identity and phones; needs the host stopped.

Client (phone) methods:
- `pair {invite,device,op,timeoutMs}` -> `{link,payload,name}`; `cancel {op}`.
- `forward {link}` -> `{handle,port}`: a loopback port forwarded to the Server.
- `stop {handle}` closes any handle.

Use the dedicated untrusted listener as the host's `port`, never one that trusts loopback proxy headers. Native calls block; run them on an IO dispatcher. Diagnostics use the `KinuvoTailcat` logcat tag on Android and `KINUVO_TAILCAT_DEBUG=1` (stderr) elsewhere; they redact addresses and credential-bearing messages.
