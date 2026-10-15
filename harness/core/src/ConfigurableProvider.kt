package kinuvo.harness.core

import io.ktor.http.Url
import kinuvo.protocol.SettingsFieldType
import kinuvo.protocol.SettingsSchema

interface ConfigurableProvider {
    val id: String
    val name: String get() = id
    val settings: SettingsSchema get() = SettingsSchema()
    val hostAccess: kinuvo.protocol.PluginAccess get() = kinuvo.protocol.PluginAccess()

    fun effectiveOptions(options: Map<String, String>): Map<String, String> =
        settings.fields.mapNotNull { field -> field.defaultValue?.let { field.key to it } }.toMap() + options.filterValues { it.isNotBlank() }

    /** Validates without starting resources, resolving Secret references or making requests. */
    fun checkOptions(options: Map<String, String>) {
        require(settings.supported) { "Unsupported settings schema for $name" }
        val fields = settings.fields.associateBy { it.key }
        require(fields.size == settings.fields.size) { "Duplicate settings fields for $name" }
        require(options.keys.all { it in fields }) { "Unknown setting for $name" }
        val effective = effectiveOptions(options)
        for (field in settings.fields) {
            val value = effective[field.key].orEmpty()
            require(!field.required || value.isNotBlank()) { "${field.label} is required" }
            if (value.isBlank() || Secrets.hasReferences(value)) continue
            val valid = when (field.type) {
                SettingsFieldType.Text -> true
                SettingsFieldType.Boolean -> value == "true" || value == "false"
                SettingsFieldType.Integer -> value.toLongOrNull() != null
                SettingsFieldType.Choice -> value in field.choices
                SettingsFieldType.Url -> runCatching { httpServiceUrl(value) }.isSuccess
                else -> false
            }
            require(valid) { "Invalid ${field.label}" }
        }
    }
}

/** Checked after interpolation, before a service sends any credentials. */
fun httpServiceUrl(value: String): String {
    val candidate = value.trim()
    val url = runCatching { Url(candidate) }.getOrNull()
    require(url != null && candidate.startsWith("${url.protocol.name}://", ignoreCase = true) &&
        url.protocol.name in listOf("http", "https") && url.host.isNotBlank()) { "Server URL must be an absolute HTTP or HTTPS URL" }
    return candidate.trimEnd('/')
}
