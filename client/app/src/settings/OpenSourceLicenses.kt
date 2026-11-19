package kinuvo.app.settings

/** A third-party component the apps ship. [maven] lists coordinate prefixes ("group:artifact") of the module dependencies it covers; [files] lists name prefixes of bundled native libraries and fonts. */
data class OpenSourceLicense(
    val name: String,
    val url: String,
    val license: String,
    val copyright: String? = null,
    val source: String? = null,
    val maven: List<String> = emptyList(),
    val files: List<String> = emptyList(),
) {
    /** The bundled license texts under `files/licenses` for each id of the SPDX expression, without the "-only" / "-or-later" suffix; the LGPL builds on the GPL. */
    val texts: List<String>
        get() = license.split(Regex(" (?:AND|WITH) ")).flatMap {
            val id = it.removeSuffix("-or-later").removeSuffix("-only")
            if (id == "LGPL-3.0") listOf(id, "GPL-3.0") else listOf(id)
        }.distinct()
}

private const val JB = "JetBrains s.r.o. and contributors"
private const val AOSP = "The Android Open Source Project"
private const val GO = "The Go Authors"
private const val TAILSCALE = "Tailscale Inc."

val openSourceLicenses = listOf(
    OpenSourceLicense("Kotlin, kotlinx and Compose Multiplatform", "https://kotlinlang.org", "Apache-2.0", JB, maven = listOf("org.jetbrains.kotlinx:", "org.jetbrains:markdown", "org.jetbrains.androidx.", "org.jetbrains.compose")),
    OpenSourceLicense("AndroidX", "https://developer.android.com/jetpack/androidx", "Apache-2.0", AOSP, maven = listOf("androidx.")),
    OpenSourceLicense("Koog", "https://github.com/JetBrains/koog", "Apache-2.0", JB, maven = listOf("ai.koog:")),
    OpenSourceLicense("Ktor", "https://ktor.io", "Apache-2.0", JB, maven = listOf("io.ktor:")),
    OpenSourceLicense("Koin", "https://insert-koin.io", "Apache-2.0", "Koin contributors", maven = listOf("io.insert-koin:")),
    OpenSourceLicense("OkHttp", "https://square.github.io/okhttp/", "Apache-2.0", "Square, Inc.", maven = listOf("com.squareup.okhttp3:")),
    OpenSourceLicense("Model Context Protocol Kotlin SDK", "https://github.com/modelcontextprotocol/kotlin-sdk", "Apache-2.0 AND MIT", "Model Context Protocol contributors; MIT portions Model Context Protocol a Series of LF Projects, LLC.", maven = listOf("io.modelcontextprotocol:")),
    OpenSourceLicense("Agent Client Protocol Kotlin SDK", "https://github.com/agentclientprotocol/kotlin-sdk", "Apache-2.0", "Agent Client Protocol contributors", maven = listOf("com.agentclientprotocol:")),
    OpenSourceLicense("OpenTelemetry Java", "https://opentelemetry.io", "Apache-2.0", "The OpenTelemetry Authors", maven = listOf("io.opentelemetry:")),
    OpenSourceLicense("Arrow", "https://arrow-kt.io", "Apache-2.0", "The Arrow Authors", maven = listOf("io.arrow-kt:")),
    OpenSourceLicense("kaml", "https://github.com/charleskorn/kaml", "Apache-2.0", "Charles Korn", maven = listOf("com.charleskorn.kaml:")),
    OpenSourceLicense("Clikt", "https://ajalt.github.io/clikt/", "Apache-2.0", "AJ Alt", maven = listOf("com.github.ajalt.clikt:")),
    OpenSourceLicense("KStore", "https://github.com/xxfast/KStore", "Apache-2.0", "Isuru Rajapakse", maven = listOf("io.github.xxfast:")),
    OpenSourceLicense("Stately", "https://github.com/touchlab/Stately", "Apache-2.0", "Touchlab", maven = listOf("co.touchlab:")),
    OpenSourceLicense("kotlin-logging", "https://github.com/oshai/kotlin-logging", "Apache-2.0", "Ohad Shai", maven = listOf("io.github.oshai:")),
    OpenSourceLicense("SLF4J", "https://www.slf4j.org", "MIT", "QOS.ch Sarl", maven = listOf("org.slf4j:")),
    OpenSourceLicense("Logback", "https://logback.qos.ch", "EPL-1.0", "QOS.ch Sarl, used under the EPL-1.0 option of its dual license", maven = listOf("ch.qos.logback:")),
    OpenSourceLicense("kdriver", "https://github.com/cdpdriver/kdriver", "Apache-2.0", "Nathan Fallet", maven = listOf("dev.kdriver:")),
    OpenSourceLicense("QuickJS-KT", "https://github.com/dokar3/quickjs-kt", "Apache-2.0", "dokar3", maven = listOf("io.github.dokar3:")),
    OpenSourceLicense("QuickJS", "https://bellard.org/quickjs/", "MIT", "Fabrice Bellard and Charlie Gordon"),
    OpenSourceLicense("webrtc-java", "https://github.com/devopvoid/webrtc-java", "Apache-2.0", "Alex Andres", maven = listOf("dev.onvoid.webrtc:")),
    OpenSourceLicense("WebRTC", "https://webrtc.org", "BSD-3-Clause", "The WebRTC project authors"),
    OpenSourceLicense("webview (swingwebview)", "https://github.com/webliteca/swingwebview", "MIT", "Steve Hannah", maven = listOf("ca.weblite:")),
    OpenSourceLicense("JetBrains Runtime API", "https://github.com/JetBrains/JetBrainsRuntimeApi", "Apache-2.0", JB, maven = listOf("com.jetbrains:jbr-api")),
    OpenSourceLicense("LiteRT-LM", "https://github.com/google-ai-edge/LiteRT-LM", "Apache-2.0", "Google LLC", maven = listOf("com.google.ai.edge.litertlm:")),
    OpenSourceLicense("ZXing", "https://github.com/zxing/zxing", "Apache-2.0", "ZXing authors", maven = listOf("com.google.zxing:")),
    OpenSourceLicense("Google Play services and Firebase Cloud Messaging", "https://developer.android.com/studio/terms.html", "LicenseRef-Android-SDK", "Google LLC", maven = listOf("com.google.android.gms:", "com.google.firebase:")),
    OpenSourceLicense("Compose Unstyled and Composables UI", "https://github.com/composablehorizons/compose-unstyled", "MIT", "Composable Horizons", maven = listOf("com.composables:ui", "com.composables:composeunstyled", "com.composables:compose-interaction-capabilities", "com.composables:ripple-indication")),
    OpenSourceLicense("Lucide icons for Compose", "https://lucide.dev", "ISC", "Lucide Contributors; portions Cole Bemis (Feather, MIT)", maven = listOf("com.composables:icons-lucide-cmp")),
    OpenSourceLicense("Haze", "https://github.com/chrisbanes/haze", "Apache-2.0", "Chris Banes", maven = listOf("dev.chrisbanes.haze:")),
    OpenSourceLicense("QRose", "https://github.com/alexzhirkevich/qrose", "MIT", "Alexander Zhirkevich", maven = listOf("io.github.alexzhirkevich:")),
    OpenSourceLicense("Multiplatform Markdown Renderer", "https://github.com/mikepenz/multiplatform-markdown-renderer", "Apache-2.0", "Mike Penz", maven = listOf("com.mikepenz:")),
    OpenSourceLicense("a2ui-compose", "https://github.com/uny/a2ui-compose", "Apache-2.0", "Yuki Nagai", maven = listOf("dev.ynagai.a2ui:")),
    OpenSourceLicense("Rive Compose Multiplatform", "https://github.com/MuazKadan/rive-cmp", "Apache-2.0", "Muaz Kadan", maven = listOf("dev.muazkadan:")),
    OpenSourceLicense("Rive runtime", "https://github.com/rive-app/rive-runtime", "MIT", "Rive, Inc."),
    OpenSourceLicense("Drawflow", "https://github.com/jerosoler/Drawflow", "MIT", "Jero Soler"),
    OpenSourceLicense("Inter", "https://github.com/rsms/inter", "OFL-1.1", "The Inter Project Authors", files = listOf("inter_")),
    OpenSourceLicense("Libron, Readerly and Newsreader", "https://github.com/nicoverbruggen/libron", "OFL-1.1", "Nico Verbruggen; The Newsreader Project Authors", files = listOf("libron_")),
    OpenSourceLicense("Skia", "https://skia.org", "BSD-3-Clause", "Google Inc.; bundled via Skiko in Compose Multiplatform"),
    OpenSourceLicense("JetBrains Runtime (desktop and CLI only)", "https://github.com/JetBrains/JetBrainsRuntime", "GPL-2.0-only WITH Classpath-exception-2.0", "Oracle and/or its affiliates; JetBrains s.r.o.", source = "https://github.com/JetBrains/JetBrainsRuntime/tree/jbr-release-25.0.4.1b635.72"),
    OpenSourceLicense("Tailscale (tailscale.com and tailcat)", "https://github.com/tailscale/tailscale", "BSD-3-Clause", "Tailscale Inc & contributors"),
    OpenSourceLicense("Tailscale hujson, peercred and web-client-prebuilt", "https://github.com/tailscale", "BSD-3-Clause", TAILSCALE),
    OpenSourceLicense("Tailscale wireguard-go", "https://github.com/tailscale/wireguard-go", "MIT", "WireGuard LLC"),
    OpenSourceLicense("Tailscale gliderssh", "https://github.com/tailscale/gliderssh", "BSD-3-Clause", "Glider Labs"),
    OpenSourceLicense("Tailscale certstore", "https://github.com/tailscale/certstore", "MIT", "Ben Toews"),
    OpenSourceLicense("TailPocket", "https://github.com/jt-wang/tailpocket", "MIT", "the tailpocket authors"),
    OpenSourceLicense("Go standard library and Go Authors modules (golang.org/x, edwards25519, json, kr/fs)", "https://go.dev", "BSD-3-Clause", GO),
    OpenSourceLicense("ed25519consensus", "https://github.com/hdevalence/ed25519consensus", "BSD-3-Clause", "$GO; Henry de Valence"),
    OpenSourceLicense("klauspost/compress", "https://github.com/klauspost/compress", "BSD-3-Clause", "$GO; Klaus Post"),
    OpenSourceLicense("netipx", "https://github.com/go4org/netipx", "BSD-3-Clause", "The Inet.af AUTHORS"),
    OpenSourceLicense("u-root", "https://github.com/u-root/u-root", "BSD-3-Clause", "u-root Authors"),
    OpenSourceLicense("msync", "https://github.com/creachadair/msync", "BSD-3-Clause", "Michael J. Fromberger"),
    OpenSourceLicense("pkg/sftp", "https://github.com/pkg/sftp", "BSD-2-Clause", "Dave Cheney"),
    OpenSourceLicense("coder/websocket", "https://github.com/coder/websocket", "ISC", "Coder"),
    OpenSourceLicense("go-shlex", "https://github.com/anmitsu/go-shlex", "MIT", "anmitsu"),
    OpenSourceLicense("creack/pty", "https://github.com/creack/pty", "MIT", "Keith Rarick"),
    OpenSourceLicense("fxamacker/cbor", "https://github.com/fxamacker/cbor", "MIT", "Faye Amacker"),
    OpenSourceLicense("float16", "https://github.com/x448/float16", "MIT", "Montgomery Edwards⁴⁴⁸ and Faye Amacker"),
    OpenSourceLicense("bart", "https://github.com/gaissmai/bart", "MIT", "Karl Gaissmaier"),
    OpenSourceLicense("go-ps", "https://github.com/mitchellh/go-ps", "MIT", "Mitchell Hashimoto"),
    OpenSourceLicense("groupcache and btree", "https://github.com/golang/groupcache", "Apache-2.0", "Google Inc."),
    OpenSourceLicense("go4.org/mem", "https://github.com/go4org/mem", "Apache-2.0", "The Go4 AUTHORS"),
    OpenSourceLicense("gVisor", "https://gvisor.dev", "Apache-2.0", "The gVisor Authors"),
    OpenSourceLicense("go-proxyproto", "https://github.com/pires/go-proxyproto", "Apache-2.0", "the go-proxyproto authors"),
    OpenSourceLicense("PRoot", "https://github.com/termux/proot", "GPL-2.0-or-later", "STMicroelectronics and contributors; unmodified Termux package proot 5.1.107.102", source = "https://github.com/termux/proot/tree/52d65a4dc140ca554d241a1438ddd4f6b00c9813", files = listOf("libproot")),
    OpenSourceLicense("talloc", "https://talloc.samba.org", "LGPL-3.0-or-later", "Andrew Tridgell, Samba Team; unmodified Termux package libtalloc 2.5.0", source = "https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz", files = listOf("libtalloc")),
    OpenSourceLicense("libandroid-shmem", "https://github.com/termux/libandroid-shmem", "BSD-3-Clause", "Sergii Pylypenko, Fredrik Fornwall; unmodified Termux package libandroid-shmem 0.7", source = "https://github.com/termux/libandroid-shmem/tree/v0.7", files = listOf("libandroid-shmem")),
)
