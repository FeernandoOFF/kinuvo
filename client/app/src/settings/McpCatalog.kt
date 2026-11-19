package kinuvo.app.settings

typealias McpPreset = kinuvo.protocol.mcp.McpPreset
typealias McpPresetAuth = kinuvo.protocol.mcp.McpPresetAuth
val McpCatalog = kinuvo.protocol.mcp.McpCatalog

private const val PRESET = "preset/"

/** The [kinuvo.app.navigation.Route.Settings.item] that adds [preset]. */
fun presetItem(preset: McpPreset) = PRESET + preset.id

fun presetOf(item: String?) = item?.removePrefix(PRESET)?.takeIf { item.startsWith(PRESET) }?.let { id -> McpCatalog.firstOrNull { it.id == id } }
