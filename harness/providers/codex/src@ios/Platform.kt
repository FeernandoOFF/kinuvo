package kinuvo.harness.codex

import io.ktor.client.engine.darwin.Darwin
import kinuvo.harness.core.Keychain

internal actual fun defaultCodexStore(): Keychain = checkNotNull(Keychain.system("kinuvo", "codex")) { "The iOS Keychain isn't set up" }

internal actual fun codexEngine() = Darwin.create()
