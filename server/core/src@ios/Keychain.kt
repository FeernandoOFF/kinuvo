package kinuvo.server

import kinuvo.server.plugin.Keychain

/** The iOS Keychain, which the app implements in Swift and sets at launch. */
var iosKeychain: ((service: String, account: String) -> Keychain)? = null

internal actual fun systemKeychain(service: String, account: String): Keychain? = iosKeychain?.invoke(service, account)
