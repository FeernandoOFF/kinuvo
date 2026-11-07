package kinuvo.harness.server

import kinuvo.harness.core.agenttools.AgentManagement
import kinuvo.harness.core.agenttools.CallerScope
import kinuvo.protocol.*

/** The management tools' view of the Server: the same Admin and MCP handlers Settings uses, and the user's forms. */
internal class ServerManagement(
    private val harness: HarnessRuntime,
    private val admin: Admin,
    private val agentInfos: suspend () -> List<AgentInfo>,
    private val createAgent: suspend (CreateAgentParams) -> AgentInfo,
    private val changed: () -> Unit,
) : AgentManagement {
    override val toolGroups get() = harness.toolGroups
    override suspend fun agents() = agentInfos()
    override suspend fun details(agentId: String) = admin.details(admin.agent(agentId))
    override suspend fun update(p: UpdateAgentParams) = admin.update(p).also { changed() }
    override suspend fun create(p: CreateAgentParams) = createAgent(p).also { changed() }
    override suspend fun session(agentId: String, sessionId: String) = harness.log.session(agentId, sessionId)
    override suspend fun check(agentId: String, sessionId: String, settings: SessionSettings) { harness.checkSettings(admin.agent(agentId), sessionId, settings) }
    override suspend fun configure(agentId: String, sessionId: String, settings: SessionSettings) = harness.configure(admin.agent(agentId), sessionId, settings).also { changed() }
    override suspend fun mcpServers(agentId: String) = harness.mcp.mcpServers(admin.agent(agentId))
    override suspend fun saveMcp(p: SaveMcpParams) = harness.mcp.saveMcp(p)
    override suspend fun removeMcp(p: McpParams) = harness.mcp.removeMcp(p)
    override suspend fun testMcp(p: McpParams) = harness.mcp.testMcp(p)
    override suspend fun loginMcp(p: McpLoginParams) = harness.mcp.loginMcp(p).url
    override suspend fun completeMcpLogin(p: McpLoginCompleteParams) = harness.mcp.completeMcpLogin(p)

    override suspend fun ask(scope: CallerScope, callId: String?, title: String, questions: List<Question>): Map<String, List<String>>? {
        val sessionId = requireNotNull(scope.sessionId) { "This needs a Session" }
        val pending = harness.questions.register()
        val formId = callId ?: pending.id
        try {
            harness.log.publish(scope.agentId, sessionId, AgentEvent.QuestionsAsked(pending.id, formId, title, questions))
            return pending.await()
        } finally {
            pending.close()
            harness.log.publish(scope.agentId, sessionId, AgentEvent.QuestionsAnswered(formId))
        }
    }
}
