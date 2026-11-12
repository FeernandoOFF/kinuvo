package kinuvo.providers.codex

import io.ktor.client.engine.java.Java
import kinuvo.server.plugin.Keychain
import kinuvo.server.plugin.PrivateFile
import kotlinx.io.files.Path

/** The Codex CLI's auth.json, so either can sign in for both. */
internal actual fun defaultCodexStore(): Keychain = PrivateFile(Path(System.getProperty("user.home"), ".codex", "auth.json"))

internal actual fun codexEngine() = Java.create()
