package kinuvo.server

import kinuvo.server.plugin.ConfigurableProvider
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.NoPluginHost
import kinuvo.server.plugin.Notification
import kinuvo.server.plugin.NotificationProvider
import kinuvo.server.plugin.Notifier
import kinuvo.server.plugin.PluginHost
import kinuvo.server.plugin.PluginHosts
import kinuvo.server.plugin.SecretReferences
import kinuvo.server.plugin.instance
import kinuvo.server.plugin.maskedOptions
import kinuvo.server.knowledge.Knowledge
import kinuvo.server.store.AgentFiles
import kinuvo.server.plugin.createDirectories
import kinuvo.server.store.saveKnowledgeConfig
import kinuvo.server.store.saveTasksConfig
import kinuvo.server.plugin.TaskProvider
import kinuvo.server.tasks.TasksConfig
import kinuvo.protocol.model.ProviderModelRef
import kinuvo.protocol.capability.Integration
import kinuvo.protocol.knowledge.KnowledgeSettings
import kinuvo.protocol.knowledge.KnowledgeSetup
import kinuvo.protocol.knowledge.KnowledgeTestResult
import kotlinx.coroutines.CancellationException
import kinuvo.protocol.board.TaskListInfo
import kinuvo.protocol.board.TaskProviderRef
import kinuvo.protocol.board.TasksSettings
import kinuvo.protocol.board.TasksSetup
import kinuvo.server.store.FileBoard
import kinuvo.server.store.LocalTaskApp
import kinuvo.server.store.tasksConfig
import kinuvo.server.tasks.Tasks
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kinuvo.server.store.FileKnowledgeChangeLog
import kinuvo.server.store.MarkdownKnowledgeSource
import kinuvo.server.store.knowledgeConfig
import kinuvo.server.store.knowledgeConventions
import kinuvo.server.store.knowledgeRoot
import kinuvo.server.store.Store
import kinuvo.server.plugin.writeAtomically
import kinuvo.server.plugin.WebSearch
import kinuvo.protocol.service.ServiceKind
import kinuvo.protocol.service.ServiceSettings
import kinuvo.protocol.service.ServiceSetup
import kinuvo.protocol.service.ServiceTestResult
import kinuvo.protocol.service.TestServiceParams
import kinuvo.protocol.settings.SecretUpdate
import kinuvo.protocol.settings.SettingsProvider
import kinuvo.server.store.serviceConfig
import kinuvo.server.store.saveServiceConfig
import kinuvo.server.store.config
import kinuvo.server.store.updateConfig
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
import kinuvo.server.plugin.WebSearchProvider
import kinuvo.protocol.reflection.DEFAULT_REFLECTION_PROMPT
import kotlin.concurrent.Volatile

internal class FileServerSettings(
    private val files: AgentFiles,
    private val userHome: Path,
    private val store: Store,
    private val external: List<HarnessPlugin>,
    private val secrets: Secrets,
    private val hosts: PluginHosts,
    clock: () -> Long,
) : ServerSettings {
    override var liveVoiceEnabled: Boolean
        get() = files.config().liveVoiceEnabled
        set(value) { files.updateConfig { it.copy(liveVoiceEnabled = value) } }

    override var imageModel: ProviderModelRef?
        get() = files.config().imageModel
        set(value) { files.updateConfig { it.copy(imageModel = value) } }

    override var timeZone: TimeZone = files.config().timezone?.let { id -> runCatching { TimeZone.of(id) }.getOrNull() } ?: TimeZone.currentSystemDefault()
        set(value) {
            if (value == field) return
            field = value
            files.updateConfig { it.copy(timezone = value.id) }
        }
    override val reflectionModel get() = files.config().reflection
    override val reflectionEnabled get() = files.knowledgeConfig().reflection
    override val reflectionPrompt get() = files.knowledgeConfig().reflectionPrompt ?: DEFAULT_REFLECTION_PROMPT
    /** The Markdown folder and the provider picked; both change with [saveKnowledgeSettings]. */
    @Volatile private var knowledgeFolder = files.knowledgeRoot(userHome)
    @Volatile private var knowledgeProvider = files.knowledgeConfig().provider
    private val knowledgeSources = listOf(MarkdownKnowledgeSource { knowledgeFolder }) + external.flatMap { it.knowledgeSources }
    val knowledge = Knowledge(
        { knowledgeSource(knowledgeProvider) },
        FileKnowledgeChangeLog(files.knowledgeChanges),
        conventions = { files.knowledgeConventions(knowledgeProvider) },
        updateConventions = { provider, before, content ->
            check(files.knowledgeConventions(provider) == before) { "Conventions changed; review a fresh proposal" }
            writeAtomically(files.conventions(provider), content)
        },
        clock = clock,
    )
    val tasks = Tasks(
        listOf(TaskProvider.of("local", "Kinuvo (built in)", LocalTaskApp(files.localTasks))) + external.flatMap { it.taskProviders },
        FileBoard(files.board),
        config = { files.tasksConfig() },
        agentIds = { store.agents().map { it.id }.toSet() },
        today = { Clock.System.todayIn(timeZone) },
        host = { p, options -> external.firstOrNull { p in it.taskProviders }?.let { host(it, p, options) } ?: NoPluginHost },
    )
    private val searchProviders = external.flatMap { it.webSearch }.uniqueProviders()
    private val notificationProviders = external.flatMap { it.notifications }.uniqueProviders()
    override val integrations: Map<Integration, Set<String>> get() {
        val own = mapOf(
            Integration.Secrets to secrets.providers.map { it.scheme }.toSet(),
            Integration.Knowledge to knowledgeSources.map { it.id }.toSet(),
            Integration.Tasks to tasks.providers.map { it.id }.toSet(),
            Integration.Search to searchProviders.keys,
            Integration.Notifications to notificationProviders.keys,
        )
        return (own.entries + external.flatMap { it.integrations.entries }).groupBy({ it.key }, { it.value }).mapValues { (_, v) -> v.flatten().toSet() }
    }

    override fun tasksSetup(): TasksSetup {
        val c = files.tasksConfig()
        val provider = tasks.providers.firstOrNull { it.id == c.provider }
        return TasksSetup(TasksSettings(c.provider, c.lists, c.defaultList, c.startBeforeDays, c.pollMinutes, maskedOptions(provider?.settings?.fields.orEmpty(), c.options)),
            tasks.providers.map { p -> SettingsProvider(p.id, p.name, p.settings, external.firstOrNull { p in it.taskProviders }?.instance) },
            provider?.configuredSecrets(c.options).orEmpty())
    }

    /** Saves [s] once its provider accepts its options; secret options change only through [secretUpdates]. */
    override suspend fun saveTasksSettings(s: TasksSettings, secretUpdates: Map<String, SecretUpdate>): TasksSetup {
        val options = taskProvider(s.provider).merged(savedTaskOptions(s.provider), s.options, secretUpdates)
        val agents = store.agents().map { it.id }.toSet()
        s.lists.values.filter { it.isNotEmpty() }.forEach { require(it in agents) { "No agent '$it'" } }
        require(s.startBeforeDays in 0..365) { "Start before must be 0 to 365 days" }
        require(s.pollMinutes in 1..1440) { "Check every must be 1 to 1440 minutes" }
        files.saveTasksConfig(TasksConfig(s.provider, s.lists, s.defaultList?.ifBlank { null }, s.startBeforeDays, s.pollMinutes, options))
        return tasksSetup()
    }

    override suspend fun taskProviderLists(ref: TaskProviderRef): List<TaskListInfo> = tasks.providerLists(ref.provider,
        ref.options?.let { taskProvider(ref.provider).merged(savedTaskOptions(ref.provider), it, ref.secretUpdates) })

    private fun taskProvider(id: String) = tasks.providers.firstOrNull { it.id == id } ?: error("No task provider '$id'; available: ${tasks.providers.joinToString { it.id }}")

    private fun savedTaskOptions(id: String) = files.tasksConfig().takeIf { it.provider == id }?.options.orEmpty()

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
            reflectionPrompt = s.reflectionPrompt?.trim()?.takeIf { it.isNotEmpty() && it != DEFAULT_REFLECTION_PROMPT },
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

    /** [s]'s folder and options, with unchanged secret options kept from the saved ones; fails on invalid ones. */
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
        val provider = serviceProviders(kind).firstOrNull { it.id == c.provider }
        return ServiceSetup(kind, c.copy(options = maskedOptions(provider?.settings?.fields.orEmpty(), c.options)),
            serviceProviders(kind).map { p -> SettingsProvider(p.id, p.name, p.settings, owner(kind, p).instance) },
            provider?.configuredSecrets(c.options).orEmpty())
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

    override val pushNotifications get() = files.serviceConfig(ServiceKind.Notifications).push

    private fun configured(kind: ServiceKind): Any? {
        val c = files.serviceConfig(kind)
        return c.provider?.let { runCatching { configure(kind, it, c.options) }.getOrNull() }
    }

    private fun configure(kind: ServiceKind, provider: String, options: Map<String, String>): Any {
        val definition = serviceProviders(kind).firstOrNull { it.id == provider } ?: error("No provider '$provider'")
        val effective = definition.effectiveOptions(options)
        val host = host(owner(kind, definition), definition, options)
        return when (kind) {
            ServiceKind.Search -> (definition as WebSearchProvider).configured(effective, host)
            ServiceKind.Notifications -> (definition as NotificationProvider).configured(effective, host)
        }
    }

    private fun owner(kind: ServiceKind, provider: ConfigurableProvider) =
        external.single { plugin -> (if (kind == ServiceKind.Search) plugin.webSearch else plugin.notifications).any { it === provider } }

    /** [plugin]'s host, granting what [options] for its [provider] let it reach. */
    private fun host(plugin: HarnessPlugin, provider: ConfigurableProvider, options: Map<String, String>): PluginHost {
        val grant = provider.grants(plugin.instance, options)
        return hosts.bind(plugin.manifest, plugin.instance) { grant }
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
            return ServiceSettings(push = s.push)
        }
        val definition = serviceProviders(kind).firstOrNull { it.id == provider } ?: error("No provider '$provider'; available: ${providerIds(kind).joinToString()}")
        val old = files.serviceConfig(kind).takeIf { it.provider == provider }?.options.orEmpty()
        return ServiceSettings(provider, definition.merged(old, s.options, updates), s.push)
    }

    private fun <T : ConfigurableProvider> List<T>.uniqueProviders(): Map<String, T> = associateBy { it.id }.also {
        require(it.size == size) { "Duplicate service provider IDs" }
    }

    private fun masked(option: String, value: String) = if (secret(option) && value.isNotEmpty() && !SecretReferences.isReference(value)) KnowledgeSettings.SECRET else value

    private fun knowledgeSource(id: String) =
        knowledgeSources.firstOrNull { it.id == id } ?: error("No knowledge provider '$id'; available: ${knowledgeSources.joinToString { it.id }}")

    private fun secret(option: String) = option.contains("key", ignoreCase = true) || option.contains("token", ignoreCase = true)

}
