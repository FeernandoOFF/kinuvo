package kinuvo.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class DeviceToolKind { Contacts, Workouts, Sleep, Computer }

@Serializable
data class DeviceToolsParams(val tools: Set<DeviceToolKind> = emptySet())

@Serializable
data class DeviceToolRequest(
    val kind: DeviceToolKind,
    val query: String? = null,
    val start: String? = null,
    val end: String? = null,
    val timeZone: String? = null,
    val limit: Int = 20,
)

@Serializable
enum class DeviceToolStatus { Ready, Denied, Unavailable, Unsupported, Skipped }

@Serializable
data class DeviceToolResult(
    val status: DeviceToolStatus,
    val data: JsonObject = JsonObject(emptyMap()),
    val message: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val readAt: String? = null,
)

@Serializable
data class ToolDevice(val id: String, val name: String, val tools: Set<DeviceToolKind>, val available: Boolean = false)
