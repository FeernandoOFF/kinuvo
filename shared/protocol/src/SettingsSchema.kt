package kinuvo.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Data-only settings shared by native plugins, future guests and every client. */
@Serializable
data class SettingsSchema(val fields: List<SettingsField> = emptyList(), val version: Int = 1) {
    val supported get() = version == 1 && fields.all { it.type in SettingsFieldType.known }
}

@Serializable
@JvmInline
value class SettingsFieldType(val value: String) {
    companion object {
        val Text = SettingsFieldType("Text")
        val Url = SettingsFieldType("Url")
        val Boolean = SettingsFieldType("Boolean")
        val Integer = SettingsFieldType("Integer")
        val Choice = SettingsFieldType("Choice")
        val known = setOf(Text, Url, Boolean, Integer, Choice)
    }
}

@Serializable
data class SettingsField(
    val key: String,
    val label: String,
    val type: SettingsFieldType = SettingsFieldType.Text,
    val description: String? = null,
    val placeholder: String = "",
    val required: Boolean = false,
    val sensitive: Boolean = false,
    val defaultValue: String? = null,
    val choices: List<String> = emptyList(),
    val randomPrefix: String? = null,
    val link: SettingsFieldLink? = null,
)

/** Opens this field as a path under another field's HTTP URL. */
@Serializable
data class SettingsFieldLink(val baseField: String)

@Serializable
data class SettingsProvider(val id: String, val name: String = id, val schema: SettingsSchema = SettingsSchema(), val owner: PluginInstanceId? = null)

@Serializable
sealed interface SecretUpdate {
    @Serializable @SerialName("keep")
    data object Keep : SecretUpdate

    @Serializable @SerialName("replace")
    data class Replace(val value: String) : SecretUpdate

    @Serializable @SerialName("remove")
    data object Remove : SecretUpdate
}
