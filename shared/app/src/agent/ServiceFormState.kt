package kinuvo.app.agent

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import kinuvo.protocol.*
import kotlin.uuid.Uuid

/** Provider drafts stay separate; credentials are changed explicitly and never copied between providers. */
data class ServiceFormState(
    val setup: ServiceSetup,
    val provider: String? = setup.settings.provider,
    private val drafts: Map<String, Map<String, String>> = emptyMap(),
    private val changes: Map<String, Map<String, SecretUpdate>> = emptyMap(),
) {
    val definition get() = setup.providers.firstOrNull { it.id == provider }
    val editable get() = provider == null || definition?.schema?.supported == true
    val fields get() = definition?.schema?.fields.orEmpty()

    fun select(id: String?) = copy(provider = id)

    fun value(key: String): String {
        if (fields.none { it.key == key }) return ""
        return drafts[provider]?.get(key) ?: setup.settings.options[key]?.takeIf { provider == setup.settings.provider && it != KnowledgeSettings.SECRET }
            ?: ""
    }

    fun effectiveValue(key: String) = value(key).ifBlank { fields.firstOrNull { it.key == key }?.defaultValue.orEmpty() }

    fun configured(key: String) = provider == setup.settings.provider &&
        key in setup.configuredSecrets

    fun secretUpdate(key: String) = changes[provider]?.get(key) ?: SecretUpdate.Keep

    fun change(key: String, value: String): ServiceFormState {
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

    fun saveParams(): SaveServiceParams {
        check(editable) { "This client does not support these provider settings" }
        val options = fields.filterNot { it.sensitive }.associate { it.key to value(it.key) }.filterValues { it.isNotBlank() }
        val secrets = fields.filter { it.sensitive }.associate { it.key to secretUpdate(it.key) }
        return SaveServiceParams(setup.kind, ServiceSettings(provider, options), secrets)
    }

    fun testParams(): TestServiceParams = saveParams().let { TestServiceParams(it.kind, it.settings, secretUpdates = it.secretUpdates) }

    private fun updateSecret(key: String, update: SecretUpdate): ServiceFormState {
        val id = requireNotNull(provider)
        require(fields.any { it.key == key && it.sensitive }) { "Not a sensitive setting" }
        return copy(changes = changes + (id to (changes[id].orEmpty() + (key to update))))
    }
}
