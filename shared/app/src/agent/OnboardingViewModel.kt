package kinuvo.app.agent

import kinuvo.app.connection.*
import kinuvo.app.navigation.SettingsSection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

val PersonalityChoices = listOf(
    "Relationship" to listOf("Companion", "Coworker"),
    "Voice" to listOf("Gen Z", "Boomer"),
    "Initiative" to listOf("Independent", "Collaborative"),
    "Energy" to listOf("Playful", "Serious"),
    "Expression" to listOf("Polite", "Unfiltered"),
)

fun personalityPrompt(choices: List<Int>): String = choices.mapIndexed { index, choice ->
    when (PersonalityChoices[index].second[choice]) {
        "Companion" -> "Be an attentive companion: listen, and help the user think things through."
        "Coworker" -> "Be a practical coworker: lead with results and clear next steps."
        "Gen Z" -> "Write casually, like a text from a friend. Slang is fine when it reads naturally."
        "Boomer" -> "Write in plain, complete sentences. No slang."
        "Independent" -> "Take initiative on the user's goals. Make reasonable choices and say which; ask before consequential actions."
        "Collaborative" -> "Check in at decisions that matter; don't interrupt every small step."
        "Playful" -> "Light humor is welcome when the mood allows it."
        "Serious" -> "Keep a focused, measured tone."
        "Polite" -> "Be tactful: honest without being harsh."
        else -> "Be blunt. Don't sugarcoat, but stay respectful."
    }
}.joinToString("\n") { "- $it" } + "\n" + """
    - Match the length of a reply to the weight of the ask. Finished work gets a short report of what changed and what's left.
    - No filler, no restating the request, no narrating tool calls. No emoji unless the user asks.
    - When unsure, say so. Agree because it's right, not because the user said it.
""".trimIndent()

/** First-run steps: welcome, Server, model, user, Identity, Soul, then [ConnectStep]. */
val OnboardingSteps = (0..6).toList()
/** A new Agent skips the Server, model and user steps: its Identity and Soul. */
val AgentSteps = listOf(4, 5)
const val ConnectStep = 6
/** What the Connect step offers to set up, in order. */
val ConnectSections = listOf(SettingsSection.Secrets, SettingsSection.Knowledge, SettingsSection.Tasks, SettingsSection.Notifications,
    SettingsSection.Search, SettingsSection.Workspace, SettingsSection.Mcp)
val NameIdeas = listOf("Milo", "Juniper", "Pip", "Otto", "Nova", "Wren", "Biscuit", "Sol", "Mochi", "Atlas")

data class OnboardingState(
    val step: Int = 0,
    val steps: List<Int> = OnboardingSteps,
    val details: AgentDetails? = null,
    val catalog: ModelsCatalog = ModelsCatalog(),
    val providers: List<ProviderInfo> = emptyList(),
    val provider: String = "",
    val model: String = "",
    val userName: String = "",
    val occupation: String = "",
    val agentName: String = "Kinuvo",
    val avatar: Int = 0,
    val choices: List<Int> = listOf(0, 0, 1, 0, 0),
    val prompt: String = personalityPrompt(listOf(0, 0, 1, 0, 0)),
    val busy: Boolean = false,
    val error: String? = null,
    val deviceCode: DeviceCode? = null,
    val apiKey: String = "",
    /** Whether each of [ConnectSections] is set up; missing until loaded. */
    val connected: Map<SettingsSection, Boolean> = emptyMap(),
    /** The section being set up from the Connect step. */
    val setup: SettingsSection? = null,
) {
    fun moved(delta: Int) = copy(step = steps.getOrElse(steps.indexOf(step) + delta) { step }, error = null)

    val modelReady: Boolean get() {
        val account = providers.firstOrNull { it.id == provider }
        return details != null && provider.isNotBlank() && model.isNotBlank() &&
            account?.apiKey?.configured != false && account?.account?.signedIn != false &&
            catalog.model(provider, model)?.state?.actionable != false
    }
}

class OnboardingViewModel(private val config: ConfigRepository) : ViewModel() {
    val state: StateFlow<OnboardingState>
        field = MutableStateFlow(OnboardingState())
    private var loading: Job? = null

    init { if (config.connection.client != null) config.connection.bind(viewModelScope) }

    fun edit(change: (OnboardingState) -> OnboardingState) { state.update(change) }

    /** Starts a new Agent with [from]'s model, a name and look the [others] don't use. */
    fun newAgent(from: AgentInfo, others: List<AgentInfo>) {
        val looks = others.map { it.avatarArtboard }
        edit { OnboardingState(step = AgentSteps.first(), steps = AgentSteps,
            agentName = NameIdeas.shuffled().firstOrNull { name -> others.none { it.name == name } } ?: NameIdeas.random(),
            avatar = AvatarArtboards.indices.firstOrNull { AvatarArtboards[it] !in looks } ?: 0) }
        load(from, identity = false)
    }

    fun load(agent: AgentInfo?, identity: Boolean = true) {
        loading?.cancel()
        edit { it.copy(details = null, catalog = ModelsCatalog(), providers = emptyList(), deviceCode = null) }
        if (agent == null) return
        loading = attempt {
            val details = config.details(agent.id)
            val catalog = config.models()
            val providers = config.providerAccounts()
            edit { it.copy(details = details, catalog = catalog, providers = providers, provider = details.settings.provider,
                model = details.settings.model, agentName = if (identity) details.info.name else it.agentName,
                avatar = if (identity) AvatarArtboards.indexOf(details.info.avatarArtboard).coerceAtLeast(0) else it.avatar) }
        }
    }

    fun selectProvider(id: String) = edit { it.copy(provider = id,
        model = it.catalog.providers.firstOrNull { group -> group.id == id }?.models?.firstOrNull()?.id.orEmpty(), deviceCode = null, apiKey = "") }

    fun choose(index: Int, choice: Int) = edit {
        val choices = it.choices.toMutableList().apply { set(index, choice) }
        it.copy(choices = choices, prompt = personalityPrompt(choices))
    }

    /** Checks what's set up on the Server, e.g. on reaching the Connect step or after setting one up. */
    fun loadConnected() = viewModelScope.launch {
        val agentId = state.value.details?.info?.id ?: return@launch
        suspend fun check(section: SettingsSection, configured: suspend () -> Boolean) {
            val on = try { config.connection.run { configured() } } catch (e: CancellationException) { throw e } catch (_: Exception) { return }
            edit { it.copy(connected = it.connected + (section to on)) }
        }
        check(SettingsSection.Secrets) { config.secretProviders().any { it.connected } }
        check(SettingsSection.Knowledge) { config.knowledgeSetup().settings.let { it.path != null || it.provider != KnowledgeSettings().provider } }
        check(SettingsSection.Tasks) { config.tasksSetup().settings.provider != TasksSettings().provider }
        check(SettingsSection.Notifications) { config.service(ServiceKind.Notifications).settings.provider != null }
        check(SettingsSection.Search) { config.service(ServiceKind.Search).settings.provider != null }
        check(SettingsSection.Workspace) { config.details(agentId).settings.exec != "none" }
        check(SettingsSection.Mcp) { config.mcpServers(agentId).any { it.active } }
    }

    fun setUp(section: SettingsSection?) {
        edit { it.copy(setup = section) }
        if (section == null) loadConnected()
    }

    fun refresh() = attempt {
        val catalog = config.models(refresh = true)
        val providers = config.providerAccounts()
        edit { it.copy(catalog = catalog, providers = providers) }
    }

    fun login(): Job {
        val provider = state.value.provider
        return attempt {
            val code = config.loginProvider(provider)
            edit { if (it.provider == provider) it.copy(deviceCode = code) else it }
        }
    }

    fun saveKey(value: String): Job {
        val provider = state.value.provider
        return attempt {
            config.saveProviderApiKey(provider, value)
            val catalog = config.models(refresh = true)
            val providers = config.providerAccounts()
            edit { it.copy(catalog = catalog, providers = providers, apiKey = "") }
        }
    }

    fun finish(onDone: () -> Unit) = attempt {
        val s = state.value
        val details = requireNotNull(s.details) { "Connect to a Server first." }
        require(s.agentName.isNotBlank() && s.modelReady) { "Choose a model and name your Agent." }
        val notes = listOfNotNull(s.userName.trim().takeIf { it.isNotEmpty() }?.let { "User's name: $it" },
            s.occupation.trim().takeIf { it.isNotEmpty() }?.let { "Occupation: $it" }).joinToString("\n")
        config.updateAgent(update(details.info.id, s).copy(
            userNotes = notes.takeIf { it.isNotEmpty() }?.let { listOf(details.userNotes.trim(), it).filter(String::isNotEmpty).joinToString("\n\n") }))
        onDone()
    }

    /** Creates the Agent, with an ID from its name that none of [taken] has, and opens it with [onCreated]. */
    fun create(taken: List<String>, onCreated: (String) -> Unit) = attempt {
        val s = state.value
        require(s.details != null && s.agentName.isNotBlank()) { "Name your Agent." }
        val base = s.agentName.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "agent" }
        val id = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }
        config.createAgent(CreateAgentParams(id, s.agentName.trim()))
        config.updateAgent(update(id, s))
        onCreated(id)
    }

    private fun update(id: String, s: OnboardingState) = UpdateAgentParams(id, name = s.agentName.trim(), provider = s.provider, model = s.model.trim(),
        reasoning = s.catalog.model(s.provider, s.model)?.reasoningFor(s.details?.settings?.reasoning.orEmpty()), avatar = AvatarArtboards[s.avatar], soul = s.prompt)

    private fun attempt(block: suspend () -> Unit): Job = viewModelScope.launch {
        val lifetime = config.connection.generation
        edit { it.copy(busy = true, error = null) }
        try { config.connection.run { block() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { edit { it.copy(error = e.message ?: "Couldn't save. Try again.") } }
        finally { if (config.connection.generation == lifetime) edit { it.copy(busy = false) } }
    }
}
