# macOS HTML Surface checks

Build the helper, then run its opt-in JVM integration check:

```sh
mise run exec -- scripts/build-mac-web.sh
KINUVO_TEST_NATIVE_HTML="$PWD/build" mise run kotlin -- test -m ui -p jvm --include-classes=kinuvo.ui.DesktopHtmlSurfaceTest
```

This exercises the actual FFM transport, sandboxed shell, WKWebView and BGRA-to-Compose conversion. The default JVM suite skips native checks unless the environment variable is set.
