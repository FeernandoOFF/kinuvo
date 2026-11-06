package kinuvo.harness.server

import kinuvo.harness.core.plugin.Notifier
import kinuvo.harness.core.tools.WebSearch
import kinuvo.protocol.*
import kotlinx.datetime.TimeZone
import kinuvo.harness.core.store.ReflectionConfig

interface ServerSettings {
    var liveVoiceEnabled: Boolean
        get() = false
        set(value) { error("Live voice settings are unavailable") }
    var imageModel: ProviderModelRef?
        get() = null
        set(value) { error("Image model settings are unavailable") }
    var timeZone: TimeZone
    val reflectionEnabled: Boolean
    val reflectionModel: ReflectionConfig get() = ReflectionConfig()
    val reflectionPrompt: String
    /** What Settings can set up on this Server, for its capabilities. */
    val integrations: Map<Integration, Set<String>> get() = emptyMap()
    fun tasksSetup(): TasksSetup
    suspend fun saveTasksSettings(s: TasksSettings, secretUpdates: Map<String, SecretUpdate> = emptyMap()): TasksSetup
    /** Every list of [ref]'s provider, with its draft settings when given. */
    suspend fun taskProviderLists(ref: TaskProviderRef): List<TaskListInfo>
    fun knowledgeSetup(): KnowledgeSetup
    fun saveKnowledgeSettings(s: KnowledgeSettings): KnowledgeSetup
    suspend fun testKnowledge(s: KnowledgeSettings): KnowledgeTestResult
    fun serviceSetup(kind: ServiceKind): ServiceSetup
    fun saveService(kind: ServiceKind, s: ServiceSettings, secretUpdates: Map<String, SecretUpdate> = emptyMap()): ServiceSetup
    suspend fun testService(p: TestServiceParams): ServiceTestResult
    fun webSearch(): WebSearch?
    fun notifier(): Notifier?
    /** Whether Notifications are also pushed to paired Devices. */
    val pushNotifications: Boolean get() = true
}
