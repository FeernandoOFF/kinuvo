package kinuvo.diagnostics

import kotlinx.serialization.Serializable

/** What the app and its Device are, for `system.json`; nothing here identifies the person or their Server. */
@Serializable
data class AppInfo(
    val version: String,
    val build: String,
    val platform: String,
    val os: String,
    val device: String = "",
    val arch: String = "",
)
