package kinuvo.providers.codex

import io.ktor.client.engine.HttpClientEngine
import kinuvo.server.plugin.Keychain

/** Where the ChatGPT sign-in is kept on this platform. */
internal expect fun defaultCodexStore(): Keychain

internal expect fun codexEngine(): HttpClientEngine
