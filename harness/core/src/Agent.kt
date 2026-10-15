package kinuvo.harness.core

import kotlinx.serialization.Serializable
import kinuvo.harness.core.tools.ToolNames
import kinuvo.protocol.SessionSettings

object ExecModes {
    const val NONE = "none"
    const val HOST = "host"
    const val CONTAINER = "container"
    const val SSH = "ssh"
}

@Serializable
data class AgentConfig(
    val provider: String = "codex",
    val model: String = "gpt-5.6-sol",
    val reasoning: String = "medium",
    /** auto | concise | detailed | none */
    val reasoningSummary: String = "auto",
    val tools: List<String> = ToolNames.DEFAULT,
    /** Extra read-only Skill directories, after the Agent's own and the shared one. */
    val skillDirs: List<String> = emptyList(),
    /** Estimated context size (tokens) above which older turns of a Session get compacted. */
    val compactAtTokens: Int = 200_000,
    /** Risk tolerance: strict | default | relaxed | full. */
    val approvals: String = "default",
    /** none | host | container | ssh: where shell and file tools run. */
    val exec: String = ExecModes.NONE,
    /** Folders the shell and file tools may touch; commands start in the first. */
    val workspace: List<String> = emptyList(),
    /** Extra environment for container/ssh commands; values support \${VAR} expansion. */
    val env: Map<String, String> = emptyMap(),
    val container: ContainerConfig = ContainerConfig(),
    val ssh: SshConfig? = null,
    val avatar: String? = null,
) {
    init {
        require(exec in setOf(ExecModes.NONE, ExecModes.HOST, ExecModes.CONTAINER, ExecModes.SSH)) { "exec must be none, host, container or ssh, got '$exec'" }
    }
}

@Serializable
data class ContainerConfig(
    val image: String = "docker.io/library/debian:stable-slim",
    val network: Boolean = false,
)

@Serializable
data class SshConfig(
    val host: String,
    val user: String? = null,
    val port: Int? = null,
    val identityFile: String? = null,
)

/** An Agent: its ID, display name (the Identity's first heading) and settings. Its documents live in [AgentDocs]. */
data class Agent(val id: String, val name: String, val config: AgentConfig)

/** The Agent as one Session runs it, with that Session's [settings] over its own. */
fun Agent.with(settings: SessionSettings) = copy(
    config = config.copy(
        provider = settings.provider ?: config.provider,
        model = settings.model ?: config.model,
        reasoning = settings.reasoning ?: config.reasoning,
        approvals = settings.approvals ?: config.approvals,
    ),
)

/** An Agent's Markdown documents: Identity, Soul, Memory, User Notes and the Heartbeat checklist. */
enum class AgentDoc { Identity, Soul, Memory, UserNotes, Heartbeat }

interface AgentDocs {
    /** The document's text; empty when it doesn't exist yet. */
    suspend fun read(agentId: String, doc: AgentDoc): String
    suspend fun write(agentId: String, doc: AgentDoc, text: String)
    /** Read-modify-write in one step, so concurrent edits don't lose each other; returns the new text. */
    suspend fun update(agentId: String, doc: AgentDoc, transform: (String) -> String): String
}

/** The name an Identity document gives: its first `# ` heading. */
fun identityName(identity: String): String? =
    identity.lineSequence().firstNotNullOfOrNull { line -> line.removePrefix("# ").takeIf { line.startsWith("# ") }?.trim() }
