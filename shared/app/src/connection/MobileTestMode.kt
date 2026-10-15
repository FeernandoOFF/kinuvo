package kinuvo.app.connection

import kotlinx.serialization.json.*

/** Mockstro's launch override, accepted only by platform test builds. */
data class MobileTestOverride(val runId: String, val websocketUrl: String) {
    companion object {
        fun read(text: String, appId: String, now: Long, android: Boolean): MobileTestOverride? = runCatching {
            val json = Json.parseToJsonElement(text).jsonObject
            fun value(key: String) = json[key]?.jsonPrimitive?.content.orEmpty()
            if (android) {
                require(value("schema") == "1" && value("applicationId") == appId)
            }
            val runId = value(if (android) "runId" else "e2eRunId")
            val expires = value(if (android) "expiresAt" else "e2eExpiresAt").toLong()
            val url = value(if (android) "websocketUrl" else "e2eWebsocketUrl")
            require(runId.matches(Regex("[A-Za-z0-9._-]{1,160}")) && expires > now)
            require(url.matches(Regex("ws://127\\.0\\.0\\.1:[0-9]+/[^?#\\\\\\s]*")))
            require(normalizeUrl(url) == url)
            MobileTestOverride(runId, url)
        }.getOrNull()
    }
}

/** Test launches keep preferences and tokens in memory for the lifetime of the process. */
class MobileTestMode(val override: MobileTestOverride?) {
    private val values = mutableMapOf<String, String>()
    private val tokens = mutableMapOf<String, String>()
    val preferences: Preferences = object : Preferences {
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }
    val credentials: DeviceCredentials = object : DeviceCredentials {
        override fun read(server: String) = tokens[server]
        override fun write(server: String, token: String) { tokens[server] = token }
        override fun delete(server: String) { tokens.remove(server) }
    }
    val servers: Servers? = override?.let { Servers(preferences, credentials = credentials).apply { onboardingComplete = true } }
}
