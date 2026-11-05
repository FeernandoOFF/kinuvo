package kinuvo.app.settings

import kinuvo.app.agent.*
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import kinuvo.protocol.*
import kotlin.uuid.Uuid

/**
 * A provider being picked and its settings edited, e.g. for web search or Tasks, from the [providers] a Server has and the
 * [saved] ones (secret options masked). Provider drafts stay separate; credentials are changed explicitly and never copied
 * between providers.
 */
data class ProviderSettingsForm(
    val providers: List<SettingsProvider>,
    val saved: ServiceSettings,
    val configuredSecrets: Set<String> = emptySet(),
    val provider: String? = saved.provider,
    private val drafts: Map<String, Map<String, String>> = emptyMap(),
    private val changes: Map<String, Map<String, SecretUpdate>> = emptyMap(),
) {
    constructor(setup: ServiceSetup) : this(setup.providers, setup.settings, setup.configuredSecrets)
    constructor(setup: TasksSetup) : this(setup.providers, ServiceSettings(setup.settings.provider, setup.settings.options), setup.configuredSecrets)

    val definition get() = providers.firstOrNull { it.id == provider }
    val editable get() = provider == null || definition?.schema?.supported == true
    val fields get() = definition?.schema?.fields.orEmpty()

    fun select(id: String?) = copy(provider = id)

    fun value(key: String): String {
        if (fields.none { it.key == key }) return ""
        return drafts[provider]?.get(key) ?: saved.options[key]?.takeIf { provider == saved.provider && it != KnowledgeSettings.SECRET }
            ?: ""
    }

    fun effectiveValue(key: String) = value(key).ifBlank { fields.firstOrNull { it.key == key }?.defaultValue.orEmpty() }

    fun configured(key: String) = provider == saved.provider && key in configuredSecrets

    fun secretUpdate(key: String) = changes[provider]?.get(key) ?: SecretUpdate.Keep

    fun change(key: String, value: String): ProviderSettingsForm {
        val id = requireNotNull(provider)
        val field = fields.first { it.key == key }
        val updated = copy(drafts = drafts + (id to (drafts[id].orEmpty() + (key to value))))
        return if (field.sensitive) updated.updateSecret(key, if (value.isBlank()) SecretUpdate.Keep else SecretUpdate.Replace(value)) else updated
    }

    fun removeSecret(key: String) = change(key, "").updateSecret(key, SecretUpdate.Remove)
    fun keepSecret(key: String) = updateSecret(key, SecretUpdate.Keep)

    fun generate(key: String) = change(key, requireNotNull(fields.first { it.key == key }.randomPrefix) + Uuid.random().toHexString())

    fun link(key: String): String? {
        val link = fields.firstOrNull { it.key == key }?.link ?: return null
        val path = value(key).takeIf { it.isNotBlank() && "\${" !in it } ?: return null
        val base = effectiveValue(link.baseField).takeIf { "\${" !in it } ?: return null
        return runCatching {
            val url = Url(base)
            require(url.protocol.name in listOf("http", "https") && url.host.isNotBlank())
            URLBuilder(url).apply { appendPathSegments(path, encodeSlash = true) }.buildString()
        }.getOrNull()
    }

    /** The provider and its options to save or try; secret ones are in [secretUpdates]. */
    fun settings(): ServiceSettings {
        check(editable) { "This client does not support these provider settings" }
        return ServiceSettings(provider, fields.filterNot { it.sensitive }.associate { it.key to value(it.key) }.filterValues { it.isNotBlank() })
    }

    fun secretUpdates(): Map<String, SecretUpdate> = fields.filter { it.sensitive }.associate { it.key to secretUpdate(it.key) }

    private fun updateSecret(key: String, update: SecretUpdate): ProviderSettingsForm {
        val id = requireNotNull(provider)
        require(fields.any { it.key == key && it.sensitive }) { "Not a sensitive setting" }
        return copy(changes = changes + (id to (changes[id].orEmpty() + (key to update))))
    }
}

fun ProviderSettingsForm.saveParams(kind: ServiceKind) = SaveServiceParams(kind, settings(), secretUpdates())

fun ProviderSettingsForm.testParams(kind: ServiceKind) = TestServiceParams(kind, settings(), secretUpdates = secretUpdates())
