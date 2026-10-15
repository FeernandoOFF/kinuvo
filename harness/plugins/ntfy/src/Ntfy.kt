package kinuvo.harness.ntfy

import kinuvo.harness.core.*
import kinuvo.protocol.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class NtfyPlugin : HarnessPlugin {
    override val definition = kinuvo.protocol.PluginDefinition("kinuvo.ntfy", "ntfy")
    override val notifications = listOf(object : NotificationProvider {
        override val id = "ntfy"
        override val settings = SettingsSchema(listOf(
            SettingsField("topic", "Topic", description = "Install the ntfy app on your phone and subscribe to this topic. Anyone who knows the topic can read it on a public server, so make it long and random.",
                placeholder = "kinuvo-…", required = true, randomPrefix = "kinuvo-", link = SettingsFieldLink("url")),
            SettingsField("url", "Server URL", SettingsFieldType.Url, "Only for a self-hosted ntfy.", defaultValue = Ntfy.DEFAULT_URL),
            SettingsField("token", "Access token", description = "Only for protected topics.", placeholder = "tk_…", sensitive = true),
        ))
        override val hostAccess = PluginAccess(network = setOf("url"), secrets = setOf("topic", "token"))
        override fun configured(options: Map<String, String>, host: PluginHost): Notifier {
            checkOptions(options)
            return Ntfy(host, authenticated = !options["token"].isNullOrBlank())
        }
    })
    override val manifest get() = super.manifest.copy(discoversTools = false)
}

/** Publishes using the host-bound server, topic and optional access token. */
class Ntfy(private val host: PluginHost, private val authenticated: Boolean = false) : Notifier {
    override suspend fun send(notification: Notification) {
        val headers = mutableMapOf("Content-Type" to "application/json")
        if (authenticated) headers["Authorization"] = "Bearer ${host.secret(TOKEN)}"
        val body = buildJsonObject {
            put("topic", host.secret(TOPIC))
            put("title", notification.title)
            put("message", notification.body.ifBlank { notification.title })
            put("priority", if (notification.urgent) 4 else 3)
            notification.agentId?.let { agent ->
                val link = Links.chat(agent, notification.sessionId)
                put("click", link)
                putJsonArray("actions") { addJsonObject { put("action", "view"); put("label", "Open in Kinuvo"); put("url", link) } }
            }
        }.toString()
        val res = host.http(PluginHttpRequest(URL, method = "POST", headers = headers, body = body))
        check(res.status in 200..299) { "ntfy failed (HTTP ${res.status}): ${res.body.take(300)}" }
    }

    companion object {
        const val DEFAULT_URL = "https://ntfy.sh"
        private const val TOPIC = "topic"
        private const val URL = "url"
        private const val TOKEN = "token"
    }
}
