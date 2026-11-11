package kinuvo.providers.codex

import io.ktor.client.engine.darwin.Darwin
import kinuvo.server.plugin.Keychain

/** iOS apps pass their own [CodexAuth] store, in the app's Keychain. */
internal actual fun defaultCodexStore(): Keychain = error("Pass a Keychain for the ChatGPT sign-in on iOS")

internal actual fun codexEngine() = Darwin.create()
