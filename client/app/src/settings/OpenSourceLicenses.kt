package kinuvo.app.settings

/** A third-party component the apps ship. [maven] lists coordinate prefixes ("group:artifact") of the module dependencies it covers. */
data class OpenSourceLicense(
    val name: String,
    val url: String,
    val license: String,
    val copyright: String? = null,
    val source: String? = null,
    val maven: List<String> = emptyList(),
) {
    /** The bundled license texts under `files/licenses`, without the SPDX "-only" / "-or-later" suffix; the LGPL builds on the GPL. */
    val texts: List<String> get() = license.removeSuffix("-or-later").removeSuffix("-only").let { if (it == "LGPL-3.0") listOf(it, "GPL-3.0") else listOf(it) }
}

private const val JB = "JetBrains s.r.o. and contributors"
private const val AOSP = "The Android Open Source Project"
private const val TERMUX = "Termux"

val openSourceLicenses = listOf(
    OpenSourceLicense("Kotlin, kotlinx and Compose Multiplatform", "https://kotlinlang.org", "Apache-2.0", JB, maven = listOf("org.jetbrains.kotlinx:", "org.jetbrains:markdown", "org.jetbrains.androidx.", "org.jetbrains.compose")),
    OpenSourceLicense("AndroidX", "https://developer.android.com/jetpack/androidx", "Apache-2.0", AOSP, maven = listOf("androidx.")),
    OpenSourceLicense("Koog", "https://github.com/JetBrains/koog", "Apache-2.0", JB, maven = listOf("ai.koog:")),
    OpenSourceLicense("Ktor", "https://ktor.io", "Apache-2.0", JB, maven = listOf("io.ktor:")),
    OpenSourceLicense("Koin", "https://insert-koin.io", "Apache-2.0", "Koin contributors", maven = listOf("io.insert-koin:")),
    OpenSourceLicense("OkHttp", "https://square.github.io/okhttp/", "Apache-2.0", "Square, Inc.", maven = listOf("com.squareup.okhttp3:")),
    OpenSourceLicense("Model Context Protocol Kotlin SDK", "https://github.com/modelcontextprotocol/kotlin-sdk", "MIT", "Anthropic, PBC and contributors", maven = listOf("io.modelcontextprotocol:")),
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
    OpenSourceLicense("Inter", "https://github.com/rsms/inter", "OFL-1.1", "The Inter Project Authors"),
    OpenSourceLicense("Libron, Readerly and Newsreader", "https://github.com/nicoverbruggen/libron", "OFL-1.1", "Nico Verbruggen; The Newsreader Project Authors"),
    OpenSourceLicense("Tailscale", "https://github.com/tailscale/tailscale", "BSD-3-Clause", "Tailscale Inc & contributors; the Go libraries it builds on are under BSD-3-Clause, MIT or Apache-2.0"),
    OpenSourceLicense("TailPocket", "https://github.com/jt-wang/tailpocket", "MIT", "the tailpocket authors"),
    OpenSourceLicense("PRoot", "https://github.com/termux/proot", "GPL-2.0-or-later", "STMicroelectronics and contributors; Termux build", source = "https://github.com/termux/proot"),
    OpenSourceLicense("talloc", "https://talloc.samba.org", "LGPL-3.0-or-later", "Andrew Tridgell, Samba Team", source = "https://www.samba.org/ftp/talloc/"),
    OpenSourceLicense("libandroid-shmem", "https://github.com/termux/libandroid-shmem", "BSD-3-Clause", "Sergii Pylypenko, Fredrik Fornwall", source = "https://github.com/termux/libandroid-shmem"),
)
