package kinuvo.protocol

import kotlinx.serialization.Serializable

/** Server-wide services set up in Settings: web search (`search.yaml`) and notifications (`notifications.yaml`). */
@Serializable
enum class ServiceKind { Search, Notifications }

/**
 * The [provider] in use (null: none) and its [options], whose values may hold `${…}` references. Secret options come
 * back masked; changes use [SecretUpdate].
 */
@Serializable
data class ServiceSettings(val provider: String? = null, val options: Map<String, String> = emptyMap())

/** The settings and the providers this Server has. */
@Serializable
data class ServiceSetup(
    val kind: ServiceKind,
    val settings: ServiceSettings,
    val providers: List<SettingsProvider> = emptyList(),
    val configuredSecrets: Set<String> = emptySet(),
)

@Serializable
data class ServiceRef(val kind: ServiceKind)

@Serializable
data class SaveServiceParams(val kind: ServiceKind, val settings: ServiceSettings, val secretUpdates: Map<String, SecretUpdate> = emptyMap())

/** Tries [settings] (the saved ones when null): a search for [input], or a notification saying it. */
@Serializable
data class TestServiceParams(
    val kind: ServiceKind,
    val settings: ServiceSettings? = null,
    val input: String? = null,
    val secretUpdates: Map<String, SecretUpdate> = emptyMap(),
)

@Serializable
data class ServiceTestResult(val summary: String? = null, val error: String? = null)
