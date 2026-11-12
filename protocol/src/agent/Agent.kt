package kinuvo.protocol.agent

import kinuvo.protocol.approval.TrustRules
import kinuvo.protocol.execution.ExecutionKind
import kinuvo.protocol.execution.ExecutionReadiness
import kotlinx.serialization.Serializable

@Serializable
data class AgentInfo(val id: AgentId, val name: String, val exec: String, val avatar: String? = null, val executionKind: ExecutionKind = ExecutionKind.Native, val connectionId: String? = null, val host: String? = null, val readiness: ExecutionReadiness = ExecutionReadiness(true, true))

@Serializable
data class AgentRef(val agentId: AgentId)

/** What the user can change about an Agent; [tools] are the built-in tool groups it may use, [approvals] its risk tolerance (strict | default | relaxed | full). */
@Serializable
data class AgentSettings(
    val model: String,
    val reasoning: String,
    val approvals: String,
    val exec: String,
    val workspace: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val provider: String = "codex",
    val container: ContainerSettings = ContainerSettings(),
    val ssh: SshSettings? = null,
)

/** The container the shell runs in when `exec` is container. */
@Serializable
data class ContainerSettings(val image: String = "docker.io/library/debian:stable-slim", val network: Boolean = false)

/** The machine the shell reaches when `exec` is ssh; [identityFile] is a key path on the Server. */
@Serializable
data class SshSettings(val host: String, val user: String? = null, val port: Int? = null, val identityFile: String? = null)

/** Exec settings to try without saving them; [command] runs in the first Workspace folder. */
@Serializable
data class TestExecParams(
    val agentId: AgentId,
    val exec: String,
    val workspace: List<String>,
    val container: ContainerSettings = ContainerSettings(),
    val ssh: SshSettings? = null,
    val command: String? = null,
)

/** Where the shell ran, the first Workspace folder's entries and [command]'s output; [error] when it couldn't connect. */
@Serializable
data class ExecTestResult(
    val backend: String? = null,
    val files: List<String> = emptyList(),
    val command: String? = null,
    val output: String? = null,
    val exitCode: Int? = null,
    val error: String? = null,
)

/** An Agent with its settings, the text of its IDENTITY.md and SOUL.md, and its Trust and Deny rules. */
@Serializable
data class AgentDetails(val info: AgentInfo, val settings: AgentSettings, val identity: String, val soul: String, val rules: TrustRules = TrustRules(), val userNotes: String = "", val managed: Boolean = false, val cwd: String? = null, val remoteAgentId: String? = null)

/** A new Agent from the default template, or a copy of [cloneFrom]'s settings, Identity, Soul and Skills. */
@Serializable
data class CreateAgentParams(val agentId: AgentId, val name: String? = null, val cloneFrom: AgentId? = null)

/** Null fields stay as they are. [name] replaces the title of IDENTITY.md. */
@Serializable
data class UpdateAgentParams(
    val agentId: AgentId,
    val name: String? = null,
    val model: String? = null,
    val reasoning: String? = null,
    val approvals: String? = null,
    val exec: String? = null,
    val workspace: List<String>? = null,
    val tools: List<String>? = null,
    val identity: String? = null,
    val soul: String? = null,
    val rules: TrustRules? = null,
    val provider: String? = null,
    val avatar: String? = null,
    val userNotes: String? = null,
    val container: ContainerSettings? = null,
    val ssh: SshSettings? = null,
)

/** Where a Skill or MCP server is defined: the Agent's own folder, shared by every Agent, or an extra read-only Skill dir. */
@Serializable
enum class ConfigScope { Agent, Shared, External }
