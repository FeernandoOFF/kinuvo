package kinuvo.harness.server

import kinuvo.harness.core.*
import kinuvo.harness.core.knowledge.Knowledge
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.createDirectories
import kinuvo.harness.core.store.saveKnowledgeConfig
import kinuvo.harness.core.store.saveTasksConfig
import kinuvo.harness.core.tasks.TasksConfig
import kinuvo.protocol.KnowledgeSettings
import kinuvo.protocol.KnowledgeSetup
import kinuvo.protocol.KnowledgeTestResult
import kotlinx.coroutines.CancellationException
import kinuvo.protocol.TasksSettings
import kinuvo.protocol.TasksSetup
import kinuvo.harness.core.store.FileBoard
import kinuvo.harness.core.store.LocalTaskProvider
import kinuvo.harness.core.store.tasksConfig
import kinuvo.harness.core.tasks.Tasks
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kinuvo.harness.core.store.FileKnowledgeChangeLog
import kinuvo.harness.core.store.MarkdownKnowledgeSource
import kinuvo.harness.core.store.knowledgeConfig
import kinuvo.harness.core.store.knowledgeConventions
import kinuvo.harness.core.store.knowledgeRoot
import kinuvo.harness.core.store.Store
import kinuvo.harness.core.store.readTextOrNull
import kinuvo.harness.core.store.writeAtomically
import kinuvo.harness.core.tools.WebSearch
import kinuvo.protocol.ServiceKind
import kinuvo.protocol.ServiceSettings
import kinuvo.protocol.ServiceSetup
import kinuvo.protocol.ServiceTestResult
import kinuvo.protocol.TestServiceParams
import kinuvo.protocol.SecretUpdate
import kinuvo.protocol.SettingsProvider
import kinuvo.harness.core.store.serviceConfig
import kinuvo.harness.core.store.saveServiceConfig
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path

internal class FileServerSettings(
    private val files: AgentFiles,
    private val userHome: Path,
    private val store: Store,
    private val external: List<HarnessPlugin>,
    private val secrets: Secrets,
    private val hosts: PluginHosts,
    clock: () -> Long,
) : ServerSettings {
    private val zoneFile = Path(files.home, "timezone")
    override var timeZone: TimeZone = zoneFile.readTextOrNull()?.trim()?.let { id -> runCatching { TimeZone.of(id) }.getOrNull() } ?: TimeZone.currentSystemDefault()
        set(value) {
            if (value == field) return
            field = value
            writeAtomically(zoneFile, value.id)
        }
    override val reflectionEnabled get() = files.knowledgeConfig().reflection
    override val reflectionPrompt get() = files.knowledgeConfig().reflectionPrompt ?: kinuvo.protocol.DEFAULT_REFLECTION_PROMPT
    /** The Markdown folder and the provider picked; both change with [saveKnowledgeSettings]. */
    @kotlin.concurrent.Volatile private var knowledgeFolder = files.knowledgeRoot(userHome)
    @kotlin.concurrent.Volatile private var knowledgeProvider = files.knowledgeConfig().provider
    private val knowledgeSources = listOf(MarkdownKnowledgeSource { knowledgeFolder }) + external.flatMap { it.knowledgeSources }
    val knowledge = Knowledge(
        { knowledgeSource(knowledgeProvider) },
        FileKnowledgeChangeLog(files.knowledgeChanges),
        conventions = { files.knowledgeConventions(knowledgeProvider) },
        clock = clock,
    )
    val tasks = Tasks(
        listOf(LocalTaskProvider(files.localTasks)) + external.flatMap { it.taskProviders },
        FileBoard(files.board),
        config = { files.tasksConfig() },
        agentIds = { store.agents().map { it.id }.toSet() },
        today = { Clock.System.todayIn(timeZone) },
    )
    private val searchProviders = external.flatMap { it.webSearch }.uniqueProviders()
    private val notificationProviders = external.flatMap { it.notifications }.uniqueProviders()
    override fun tasksSetup(): TasksSetup {
        val c = files.tasksConfig()
        return TasksSetup(TasksSettings(c.provider, c.lists, c.defaultList, c.startBeforeDays, c.pollMinutes), tasks.providerIds)
    }

    override suspend fun saveTasksSettings(s: TasksSettings): TasksSetup {
        require(s.provider in tasks.providerIds) { "No task provider '${s.provider}'; available: ${tasks.providerIds.joinToString()}" }
        val agents = store.agents().map { it.id }.toSet()
        s.lists.values.filter { it.isNotEmpty() }.forEach { require(it in agents) { "No agent '$it'" } }
        require(s.startBeforeDays in 0..365) { "Start before must be 0 to 365 days" }
        require(s.pollMinutes in 1..1440) { "Check every must be 1 to 1440 minutes" }
        files.saveTasksConfig(TasksConfig(s.provider, s.lists, s.defaultList?.ifBlank { null }, s.startBeforeDays, s.pollMinutes))
        return tasksSetup()
    }

    override fun knowledgeSetup(): KnowledgeSetup {
        val c = files.knowledgeConfig()
        val options = c.options.mapValues { (k, v) -> masked(k, v) }
        val conventions = knowledgeSources.associate { it.id to files.knowledgeConventions(it.id).trim() }
        return KnowledgeSetup(KnowledgeSettings(c.path, c.reflection, c.provider, options, conventions, c.reflectionPrompt), knowledgeFolder.toString(), knowledgeSources.map { it.id })
    }

    /** Switches provider or Markdown folder (created if missing) from now on; what's already saved stays where it is. */
    override fun saveKnowledgeSettings(s: KnowledgeSettings): KnowledgeSetup {
        val (path, options) = checked(s)
        s.conventions.keys.forEach(::knowledgeSource)
        files.saveKnowledgeConfig(files.knowledgeConfig().copy(
            path = path, reflection = s.reflection, provider = s.provider, options = options,
            reflectionPrompt = s.reflectionPrompt?.trim()?.takeIf { it.isNotEmpty() && it != kinuvo.protocol.DEFAULT_REFLECTION_PROMPT },
        ))
        s.conventions.forEach { (provider, text) -> writeAtomically(files.conventions(provider), text.trim().let { if (it.isEmpty()) "" else "$it\n" }) }
        knowledgeFolder = files.knowledgeRoot(userHome).also { it.createDirectories() }
        knowledgeProvider = s.provider
        return knowledgeSetup()
    }

    /** Reads the schema of the Knowledge [s] points at, without saving [s]. */
    override suspend fun testKnowledge(s: KnowledgeSettings): KnowledgeTestResult = try {
        val (path, options) = checked(s)
        val schema = knowledgeSource(s.provider).configured(options, files.knowledgeRoot(userHome, path)).schema()
        KnowledgeTestResult("${schema.types.size} types, ${schema.properties.size} properties")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        KnowledgeTestResult(error = secrets.redact(e.message ?: "${e::class.simpleName} (no details)"))
    }

    /** [s]'s folder and options, with unchanged secret options kept from knowledge.yaml; fails on invalid ones. */
    private fun checked(s: KnowledgeSettings): Pair<String?, Map<String, String>> {
        val path = s.path?.trim()?.ifEmpty { null }
        require(path == null || path.startsWith("/") || path.startsWith("~/")) { "Give an absolute folder, or one starting with ~/" }
        val old = files.knowledgeConfig().options
        val options = s.options.mapValues { (k, v) -> if (v == KnowledgeSettings.SECRET) old[k].orEmpty() else v.trim() }.filterValues { it.isNotEmpty() }
        knowledgeSource(s.provider).checkOptions(options)
        return path to options
    }

    override fun serviceSetup(kind: ServiceKind): ServiceSetup {
        val c = files.serviceConfig(kind)
        val fields = serviceProviders(kind).firstOrNull { it.id == c.provider }?.settings?.fields.orEmpty().associateBy { it.key }
        val options = c.options.mapValues { (key, value) ->
            if (fields[key]?.sensitive != false && value.isNotEmpty() && !Secrets.isReference(value)) KnowledgeSettings.SECRET else value
        }
        return ServiceSetup(kind, c.copy(options = options),
            serviceProviders(kind).map { provider -> SettingsProvider(provider.id, provider.name, provider.settings,
                external.firstOrNull { plugin -> (if (kind == ServiceKind.Search) plugin.webSearch else plugin.notifications).any { it === provider } }?.instance) },
            c.options.keys.filter { fields[it]?.sensitive == true && !c.options[it].isNullOrBlank() }.toSet())
    }

    /** Saves [s] once its provider accepts its options; unchanged secret options are kept. */
    override fun saveService(kind: ServiceKind, s: ServiceSettings, secretUpdates: Map<String, SecretUpdate>): ServiceSetup {
        val settings = checked(kind, s, secretUpdates)
        files.saveServiceConfig(kind, settings)
        return serviceSetup(kind)
    }

    /** Tries [p]'s settings (the saved ones when null) without saving them. */
    override suspend fun testService(p: TestServiceParams): ServiceTestResult = try {
        val saved = p.settings ?: files.serviceConfig(p.kind)
        val updates = if (p.settings == null) {
            val fields = serviceProviders(p.kind).firstOrNull { it.id == saved.provider }?.settings?.fields.orEmpty()
            fields.filter { it.sensitive && it.key in saved.options }.associate { it.key to SecretUpdate.Keep } + p.secretUpdates
        } else p.secretUpdates
        val draft = if (p.settings == null) saved.copy(options = saved.options - updates.keys) else saved
        val s = checked(p.kind, draft, updates)
        val provider = s.provider ?: error("Pick a provider first")
        when (p.kind) {
            ServiceKind.Search -> {
                val query = p.input?.ifBlank { null } ?: "Kinuvo personal assistant"
                val results = (configure(p.kind, provider, s.options) as WebSearch).search(query, 3, news = false)
                ServiceTestResult("${results.size} results for \"$query\"" + (results.firstOrNull()?.let { ", first: ${it.title}" } ?: ""))
            }
            ServiceKind.Notifications -> {
                (configure(p.kind, provider, s.options) as Notifier).send(Notification("Kinuvo", p.input?.ifBlank { null } ?: "Notifications from Kinuvo work."))
                ServiceTestResult("Sent")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ServiceTestResult(error = secrets.redact(e.message ?: "${e::class.simpleName} (no details)"))
    }

    /** The saved web search, or null when none is set up. */
    override fun webSearch(): WebSearch? = configured(ServiceKind.Search) as WebSearch?

    /** The saved notifier, or null when none is set up. */
    override fun notifier(): Notifier? = configured(ServiceKind.Notifications) as Notifier?

    private fun configured(kind: ServiceKind): Any? {
        val c = files.serviceConfig(kind)
        return c.provider?.let { runCatching { configure(kind, it, c.options) }.getOrNull() }
    }

    private fun configure(kind: ServiceKind, provider: String, options: Map<String, String>): Any {
        val definition = serviceProviders(kind).firstOrNull { it.id == provider } ?: error("No provider '$provider'")
        val plugin = external.single { plugin -> (if (kind == ServiceKind.Search) plugin.webSearch else plugin.notifications).any { it === definition } }
        val effective = definition.effectiveOptions(options)
        val access = definition.hostAccess
        val grant = PluginGrants(plugin.instance,
            network = effective.filterKeys { it in access.network },
            secrets = effective.filterKeys { it in access.secrets })
        val host = hosts.bind(plugin.manifest, plugin.instance) { grant }
        return when (kind) {
            ServiceKind.Search -> (definition as kinuvo.harness.core.tools.WebSearchProvider).configured(effective, host)
            ServiceKind.Notifications -> (definition as NotificationProvider).configured(effective, host)
        }
    }

    private fun serviceProviders(kind: ServiceKind): List<ConfigurableProvider> = when (kind) {
        ServiceKind.Search -> searchProviders.values.toList()
        ServiceKind.Notifications -> notificationProviders.values.toList()
    }

    private fun providerIds(kind: ServiceKind) = serviceProviders(kind).map { it.id }

    private fun checked(kind: ServiceKind, s: ServiceSettings, updates: Map<String, SecretUpdate>): ServiceSettings {
        val provider = s.provider?.ifBlank { null }
        if (provider == null) {
            require(s.options.isEmpty() && updates.isEmpty()) { "Pick a provider before configuring settings" }
            return ServiceSettings()
        }
        val definition = serviceProviders(kind).firstOrNull { it.id == provider } ?: error("No provider '$provider'; available: ${providerIds(kind).joinToString()}")
        val fields = definition.settings.fields.associateBy { it.key }
        require(updates.keys.all { fields[it]?.sensitive == true }) { "Secret updates must name a sensitive setting" }
        require(updates.keys.none { it in s.options }) { "Give a setting value or a secret update, not both" }
        val old = files.serviceConfig(kind).takeIf { it.provider == provider }?.options.orEmpty()
        require(s.options.keys.none { fields[it]?.sensitive == true }) { "Use a secret update for sensitive settings" }
        val options = s.options.mapValues { (_, value) -> value.trim() }.toMutableMap()
        updates.forEach { (key, update) ->
            when (update) {
                SecretUpdate.Keep -> options[key] = old[key].orEmpty()
                SecretUpdate.Remove -> options.remove(key)
                is SecretUpdate.Replace -> {
                    require(update.value.isNotBlank() && update.value != KnowledgeSettings.SECRET) { "Give a replacement for ${fields.getValue(key).label}" }
                    options[key] = update.value.trim()
                }
            }
        }
        val values = options.filterValues { it.isNotEmpty() }
        definition.checkOptions(values)
        return ServiceSettings(provider, values)
    }

    private fun <T : ConfigurableProvider> List<T>.uniqueProviders(): Map<String, T> = associateBy { it.id }.also {
        require(it.size == size) { "Duplicate service provider IDs" }
    }

    private fun masked(option: String, value: String) = if (secret(option) && value.isNotEmpty() && !Secrets.isReference(value)) KnowledgeSettings.SECRET else value

    private fun knowledgeSource(id: String) =
        knowledgeSources.firstOrNull { it.id == id } ?: error("No knowledge provider '$id'; available: ${knowledgeSources.joinToString { it.id }}")

    private fun secret(option: String) = option.contains("key", ignoreCase = true) || option.contains("token", ignoreCase = true)

}
