package kinuvo.server.jvm

import kinuvo.server.plugin.readText
import kinuvo.server.plugin.writeAtomically
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the desktop app's login service serves. Its launch agent is fixed inside the app, so `kinuvo service install`
 * leaves the home and flags here for it to read at start.
 */
@Serializable
data class LoginServiceSettings(val home: String, val options: ServeOptions = ServeOptions()) {
    companion object {
        fun file(userHome: String = System.getProperty("user.home")) = Path(userHome, "Library/Application Support/Kinuvo/service.json")

        fun read(userHome: String = System.getProperty("user.home")): LoginServiceSettings? = runCatching { Json.decodeFromString(serializer(), file(userHome).readText()) }.getOrNull()

        fun write(settings: LoginServiceSettings, userHome: String = System.getProperty("user.home")) = writeAtomically(file(userHome), Json.encodeToString(serializer(), settings))
    }
}
