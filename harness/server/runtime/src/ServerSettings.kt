package kinuvo.harness.server

import kinuvo.harness.core.Notifier
import kinuvo.harness.core.tools.WebSearch
import kinuvo.protocol.*
import kotlinx.datetime.TimeZone

interface ServerSettings {
    var timeZone: TimeZone
    val reflectionEnabled: Boolean
    val reflectionModel: kinuvo.harness.core.store.ReflectionConfig get() = kinuvo.harness.core.store.ReflectionConfig()
    val reflectionPrompt: String
    /** What Settings can set up on this Server, for its capabilities. */
    val integrations: Map<Integration, Set<String>> get() = emptyMap()
    fun tasksSetup(): TasksSetup
    suspend fun saveTasksSettings(s: TasksSettings): TasksSetup
    fun knowledgeSetup(): KnowledgeSetup
    fun saveKnowledgeSettings(s: KnowledgeSettings): KnowledgeSetup
    suspend fun testKnowledge(s: KnowledgeSettings): KnowledgeTestResult
    fun serviceSetup(kind: ServiceKind): ServiceSetup
    fun saveService(kind: ServiceKind, s: ServiceSettings, secretUpdates: Map<String, SecretUpdate> = emptyMap()): ServiceSetup
    suspend fun testService(p: TestServiceParams): ServiceTestResult
    fun webSearch(): WebSearch?
    fun notifier(): Notifier?
}
