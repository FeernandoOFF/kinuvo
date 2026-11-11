package kinuvo.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class Platform(val displayName: String) {
    MacOS("macOS"), Windows("Windows"), Linux("Linux"), Android("Android"), IOS("iOS"), Web("Web"), Unknown("Unknown");

    companion object {
        fun fromName(name: String): Platform = entries.firstOrNull { name.startsWith(it.displayName, ignoreCase = true) }
            ?: if (name.startsWith("Mac", ignoreCase = true) || name.equals("Darwin", ignoreCase = true)) MacOS else Unknown
    }
}

expect val currentPlatform: Platform
