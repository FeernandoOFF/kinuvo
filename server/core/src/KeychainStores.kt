package kinuvo.server

import kinuvo.server.plugin.Keychain

/** The host's keychain, or null when it has none (e.g. headless Linux or a container). */
fun Keychain.Companion.system(service: String, account: String): Keychain? = systemKeychain(service, account)

internal expect fun systemKeychain(service: String, account: String): Keychain?
