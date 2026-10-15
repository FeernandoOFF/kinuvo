package kinuvo.harness.core

import kinuvo.harness.core.tools.RawSchemaToolDescriptor
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.ToolKind
import kinuvo.harness.core.tools.withToolKey
import kinuvo.protocol.PluginInstanceId
import kinuvo.protocol.ToolIdentity

/** Names are bounded model aliases; the full identity remains the routing and policy authority. */
fun ToolIdentity.modelAlias(): String {
    val prefix = "${instance.pluginId.substringAfterLast('.')}__${instance.instanceId}__$localId"
    var hash = 0xcbf29ce484222325UL
    for (byte in key.encodeToByteArray()) hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3UL
    val suffix = hash.toString(16).padStart(16, '0')
    return prefix.replace(Regex("[^A-Za-z0-9_-]"), "_").take(46) + "__" + suffix
}

fun PluginTools.ownedBy(owner: PluginInstanceId, manifest: kinuvo.protocol.PluginManifest? = null): PluginTools {
    manifest?.let {
        require(it.plugin.id == owner.pluginId) { "Manifest belongs to another plugin" }
        it.checkSupported()
        if (!it.discoversTools) {
            val declared = it.tools.map { it.id }.toSet()
            val localIds = tools.associate { it.descriptor.name to (it.identity?.localId ?: it.descriptor.name) }
            require(tools.all { (it.identity?.localId ?: it.descriptor.name) in declared } &&
                catalog.all { (it.identity?.localId ?: localIds[it.id] ?: it.id) in declared }) { "Plugin exported an undeclared tool" }
        }
    }
    val bound = tools.associate { tool ->
        val identity = tool.identity ?: ToolIdentity(owner, tool.descriptor.name)
        require(identity.instance.pluginId == owner.pluginId) { "A plugin cannot contribute another plugin's tool" }
        tool.descriptor.name to tool.bound(identity)
    }
    require(bound.size == tools.size) { "Duplicate tool names" }
    return copy(tools = bound.values.toList(), catalog = catalog.map { entry ->
        val tool = bound[entry.id]
        val identity = tool?.identity ?: entry.identity ?: ToolIdentity(owner, entry.id)
        require(identity.instance.pluginId == owner.pluginId) { "A plugin cannot report another plugin's tool" }
        entry.copy(id = identity.modelAlias(), name = if (entry.name == entry.id) tool?.displayName ?: entry.name else entry.name,
            identity = identity)
    })
}

private fun Tool.bound(id: ToolIdentity): Tool {
    val original = this
    val alias = id.modelAlias()
    return object : Tool by original {
        override val identity = id
        override fun assess(args: kotlinx.serialization.json.JsonObject) = original.assess(args).let {
            if (kind == ToolKind.Shell) it else it.withToolKey(original.descriptor.name, id.key)
        }
        override val descriptor = when (val d = original.descriptor) {
            is RawSchemaToolDescriptor -> RawSchemaToolDescriptor(alias, d.description, d.schema)
            else -> d.copy(name = alias)
        }
    }
}
