package kinuvo.harness.server

import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentDoc
import kinuvo.harness.core.ExecModes
import kinuvo.harness.core.Tolerance
import kinuvo.harness.core.skills.Skill
import kinuvo.harness.core.tools.ToolNames
import kinuvo.protocol.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Changes Agents, their Skills and MCP servers on disk for clients. Runs pick the changes up when they next start. */
class Admin(private val harness: HarnessRuntime, private val runs: Runs, private val onAgentsChanged: (List<AgentInfo>) -> Unit) {
    private val store get() = harness.store
    private val docs get() = harness.docs
    /** Changes are read-modify-write on files, so they go one at a time. */
    private val lock = Mutex()

    suspend fun agents(): List<AgentInfo> = store.agents().map { it.info() }

    suspend fun agent(id: String): Agent = store.agents().firstOrNull { it.id == id } ?: error("No agent '$id'")

    suspend fun details(agent: Agent) = AgentDetails(
        agent.info(), agent.settings(), docs.read(agent.id, AgentDoc.Identity), docs.read(agent.id, AgentDoc.Soul), harness.trustRules.get(agent.id), docs.read(agent.id, AgentDoc.UserNotes),
    )

    suspend fun create(p: CreateAgentParams): AgentInfo = lock.withLock {
        val agent = store.createAgent(p.agentId, p.cloneFrom?.let { agent(it) })
        runs.reopen(agent.id)
        p.name?.let { name -> docs.update(agent.id, AgentDoc.Identity) { titled(it, name) } }
        onAgentsChanged(agents())
        return agent(agent.id).info()
    }

    suspend fun update(p: UpdateAgentParams): AgentDetails = lock.withLock {
        val agent = agent(p.agentId)
        val before = agent.info()
        val old = agent.config
        p.provider?.let { harness.requireModelProvider(it); require(!p.model.isNullOrBlank() || it == old.provider) { "Choose a model with its provider" } }
        p.approvals?.let { require(it in Tolerance.ids) { "approvals must be one of ${Tolerance.ids.joinToString()}" } }
        // A tool this Server no longer has may linger in the config; it doesn't block changes and is dropped on save.
        p.tools?.let { tools -> require(tools.all { it in TOOLS || it in old.tools }) { "tools must be among ${TOOLS.joinToString()}" } }
        p.exec?.takeIf { it != old.exec }?.let(::requireExec)
        val config = old.copy(
            avatar = p.avatar ?: old.avatar,
            provider = p.provider ?: old.provider,
            model = p.model?.trim()?.ifEmpty { null } ?: old.model,
            reasoning = p.reasoning ?: old.reasoning,
            approvals = p.approvals ?: old.approvals,
            exec = p.exec ?: old.exec,
            workspace = p.workspace ?: old.workspace,
            tools = p.tools?.filter { it in TOOLS } ?: old.tools,
            container = p.container ?: old.container,
            ssh = p.ssh ?: old.ssh,
        )
        val execChanged = config.exec != old.exec || config.workspace != old.workspace || config.container != old.container || config.ssh != old.ssh
        check(!execChanged || !runs.anyActive(sessionIds(agent))) { "Stop the agent's runs before changing where its shell runs" }
        if (config != old) store.saveConfig(agent, config)
        p.identity?.let { docs.write(agent.id, AgentDoc.Identity, it) }
        p.name?.let { name -> docs.update(agent.id, AgentDoc.Identity) { titled(it, name) } }
        p.userNotes?.let { docs.write(agent.id, AgentDoc.UserNotes, it) }
        p.soul?.let { docs.write(agent.id, AgentDoc.Soul, it) }
        p.rules?.let { rules -> harness.trustRules.update(agent.id) { rules.clean() } }
        if (execChanged) harness.release(agent)
        val updated = agent(agent.id)
        if (updated.info() != before) onAgentsChanged(agents())
        details(updated)
    }

    suspend fun serverRules() = harness.trustRules.server()

    suspend fun saveServerRules(rules: TrustRules): TrustRules {
        harness.trustRules.updateServer { rules.clean() }
        return harness.trustRules.server()
    }

    suspend fun testExec(p: TestExecParams): ExecTestResult {
        val agent = agent(p.agentId)
        val check = harness.execCheck ?: return ExecTestResult(error = "This Server can't run a shell")
        runCatching { requireExec(p.exec) }.onFailure { return ExecTestResult(error = it.message) }
        val config = runCatching { agent.config.copy(exec = p.exec, workspace = p.workspace.map { it.trim() }.filter { it.isNotEmpty() }, container = p.container, ssh = p.ssh) }
            .getOrElse { return ExecTestResult(error = it.message) }
        return check.check(agent.copy(config = config), p.command?.takeIf { it.isNotBlank() })
    }

    suspend fun delete(id: String) = lock.withLock {
        val agent = agent(id)
        check(store.agents().size > 1) { "Can't delete the only Agent" }
        check(runs.close(agent, sessionIds(agent))) { "Agent '$id' has an active run; stop it first" }
        try {
            harness.release(agent)
            store.deleteAgent(agent)
            harness.mcp.removeAgent(agent.id)
        } catch (e: Exception) {
            runs.reopen(id)
            throw e
        }
        onAgentsChanged(agents())
    }

    suspend fun skills(agent: Agent): List<SkillInfo> {
        val seen = mutableSetOf<String>()
        return harness.skills.all(agent).distinctBy { it.scope to it.name }.map { it.info(active = seen.add(it.name)) }
    }

    suspend fun skill(agent: Agent, name: String, scope: ConfigScope?): SkillDetails {
        val skill = harness.skills.all(agent).firstOrNull { it.name == name && (scope == null || it.scope == scope) }
            ?: error("No skill '$name'")
        return SkillDetails(skills(agent).first { it.name == name && it.scope == skill.scope }, harness.skills.content(skill), harness.skills.resources(skill))
    }

    suspend fun saveSkill(p: SaveSkillParams): SkillInfo = lock.withLock {
        val agent = agent(p.agentId)
        harness.skills.save(agent, p.scope, p.name, p.content, p.files)
        skills(agent).first { it.name == p.name && it.scope == p.scope }
    }

    suspend fun deleteSkill(p: SkillParams) = lock.withLock {
        val agent = agent(p.agentId)
        val scope = p.scope ?: skills(agent).firstOrNull { it.name == p.name }?.scope ?: error("No skill '${p.name}'")
        check(harness.skills.delete(agent, scope, p.name)) { "No skill '${p.name}' in ${scope.name.lowercase()} skills" }
    }

    private fun requireExec(mode: String) = require(mode == ExecModes.NONE || harness.integrations[Integration.Exec].orEmpty().contains(mode)) {
        "This Server can't run a shell on '$mode'"
    }

    private suspend fun sessionIds(agent: Agent) = store.sessions(agent).map { it.id }

    private fun Agent.info() = AgentInfo(id, name, config.exec, config.avatar)

    private fun Agent.settings() = AgentSettings(config.model, config.reasoning, Tolerance.of(config.approvals).id, config.exec, config.workspace, config.tools, config.provider, config.container, config.ssh)

    private fun TrustRules.clean(): TrustRules {
        fun List<String>.tidy() = map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return TrustRules(trusted.tidy(), shell.tidy(), DenyRules(deny.tools.tidy(), deny.shell.tidy()))
    }

    private fun Skill.info(active: Boolean) = SkillInfo(name, description, userInvocable, modelInvocable, scope, active)

    private companion object {
        val TOOLS = ToolNames.OPTIONAL

        /** [identity] with its `# Title` line set to [name]. */
        fun titled(identity: String, name: String): String {
            val lines = identity.lines()
            val i = lines.indexOfFirst { it.startsWith("# ") }
            return if (i >= 0) lines.toMutableList().apply { set(i, "# $name") }.joinToString("\n") else "# $name\n\n$identity"
        }
    }
}
