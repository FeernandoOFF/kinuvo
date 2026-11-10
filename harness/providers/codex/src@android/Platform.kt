package kinuvo.harness.codex

import io.ktor.client.engine.okhttp.OkHttp
import kinuvo.server.plugin.Keychain

/** Android apps pass their own [CodexAuth] store, in the app's private files. */
internal actual fun defaultCodexStore(): Keychain = error("Pass a Keychain for the ChatGPT sign-in on Android")

internal actual fun codexEngine() = OkHttp.create()
