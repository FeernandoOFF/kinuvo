package kinuvo.protocol

import kotlinx.serialization.Serializable

@Serializable
data class PluginDefinition(val id: String, val name: String) {
    init { require(id.isNotBlank()) { "A plugin needs a stable ID" } }
}

@Serializable
data class PluginInstanceId(val pluginId: String, val instanceId: String = "default") {
    init { require(pluginId.isNotBlank() && instanceId.isNotBlank()) { "A plugin instance needs both IDs" } }
}

@Serializable
data class ToolIdentity(val instance: PluginInstanceId, val localId: String) {
    init { require(localId.isNotBlank()) { "A tool needs a local ID" } }
    val key get() = "tool:" + listOf(instance.pluginId, instance.instanceId, localId).joinToString("/", transform = ::identityPart)
}

private fun identityPart(value: String) = buildString {
    for (byte in value.encodeToByteArray()) {
        val n = byte.toInt() and 255
        if (n in 65..90 || n in 97..122 || n in 48..57 || n in listOf(45, 46, 95)) append(n.toChar())
        else append('%').append(n.toString(16).uppercase().padStart(2, '0'))
    }
}
