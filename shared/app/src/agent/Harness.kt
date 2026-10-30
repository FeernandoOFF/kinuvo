package kinuvo.app.agent

import kinuvo.app.connection.*
import kinuvo.protocol.*

/** An external Agent's instructions, Memory, Skills and tools belong to its harness, not Kinuvo. */
val AgentInfo.external get() = executionKind != ExecutionKind.Native

fun sessionBrowserAvailable(agent: AgentInfo?, server: ServerCapabilities?) =
    agent?.executionKind == ExecutionKind.Native && server.supports(ServerFeature.Browser)

val HarnessConnectionStatus.kind get() = when (config.connection) {
    is ExecutionConnection.Acp -> ExecutionKind.Acp
    is ExecutionConnection.Remote -> ExecutionKind.Remote
}

/** The selected Server's machine as labels name it: its own name, or "this device" only when it's embedded here. */
fun serverLabel(ready: Connection.Ready): String =
    if (ready.server == Server.Embedded) "this device" else ready.client.lastConnect?.serverName ?: ready.server.label

/** Whether the Server says it runs Agents in other harnesses; older Servers don't say, and don't. */
val ServerCapabilities?.runsHarnesses get() = this?.integrations?.get(Integration.Harness.name).orEmpty().isNotEmpty()

/** The name of the harness that runs [agent]: Kinuvo, its ACP command's name, or the remote Server. */
fun harnessName(agent: AgentInfo, connections: List<HarnessConnectionStatus>?): String = when (agent.executionKind) {
    ExecutionKind.Native -> "Kinuvo"
    else -> connections?.firstOrNull { it.config.id == agent.connectionId }?.config?.name ?: if (agent.executionKind == ExecutionKind.Acp) "ACP agent" else "Kinuvo"
}

/**
 * Where [agent] runs, naming the machine: "Kinuvo on Mac mini", "Claude Code on Mac mini". [server] is the selected
 * Server's name, "this device" only when it is embedded in the Device showing it.
 */
fun runsOn(agent: AgentInfo, connections: List<HarnessConnectionStatus>?, server: String): String =
    "${harnessName(agent, connections)} on ${agent.host ?: server}"

/** The rail's second line for an external Agent, e.g. "Claude Code · Mac mini"; none for Kinuvo's own. */
fun railSubtitle(agent: AgentInfo, connections: List<HarnessConnectionStatus>?, server: String): String? = when (agent.executionKind) {
    ExecutionKind.Native -> null
    ExecutionKind.Acp -> "${harnessName(agent, connections)} · ${agent.host ?: server}"
    ExecutionKind.Remote -> agent.host ?: server
}

fun ExecutionReadiness.state(): CapabilityState = when {
    online -> CapabilityState()
    reasonCode == "unsupported" -> CapabilityState.unsupported(reason ?: "Not available on this Server")
    else -> CapabilityState.unavailable(reason ?: "Not checked yet", reasonCode ?: NotChecked)
}

private const val NotChecked = "not_checked"

enum class ReadinessTone { Ready, Neutral, Warning, Danger }
enum class ReadinessAction { CheckAgain, Edit, Remove }

/** How a harness's readiness reads: a pill, what's wrong naming the machine, and what can fix it. */
data class Readiness(val pill: String, val tone: ReadinessTone, val message: String? = null, val actions: List<ReadinessAction> = emptyList()) {
    val ready get() = tone == ReadinessTone.Ready
}

/**
 * [harness] runs on [host]; [command] is an ACP command's executable and [agent] the Agent's name. Unknown reason
 * codes show the Server's reason as it is.
 */
fun readiness(state: CapabilityState, harness: String, host: String, agent: String? = null, command: String? = null): Readiness {
    if (state.actionable) return Readiness("Ready", ReadinessTone.Ready)
    val check = listOf(ReadinessAction.CheckAgain)
    return when (state.reasonCode) {
        "archived" -> Readiness("Archived", ReadinessTone.Neutral, state.reason ?: "This Agent is archived. Saved Sessions are read-only.")
        "checking" -> Readiness("Checking…", ReadinessTone.Neutral)
        NotChecked -> Readiness("Not checked", ReadinessTone.Neutral, state.reason, check)
        "not_found" -> Readiness("Not found", ReadinessTone.Danger,
            "No ${command?.let { "\"$it\"" } ?: harness} on $host. Install it there, then check again.", check + ReadinessAction.Edit)
        "needs_sign_in" -> Readiness("Needs sign-in", ReadinessTone.Warning, "$harness on $host isn't signed in. Sign in with $harness itself on $host, then check again.", check)
        "session_lost" -> Readiness("Session ended", ReadinessTone.Warning, state.reason
            ?: "This Session's harness connection ended. Its transcript is saved, but continuing it isn't supported yet. Start a new Session to chat.")
        "host_offline" -> Readiness("Offline", ReadinessTone.Neutral, "$host is offline. ${agent ?: harness} can't reply until it's back.", check)
        "unpaired" -> Readiness("Unlinked", ReadinessTone.Danger, "$host no longer accepts this Server. Link it again.")
        "access_revoked" -> Readiness("Access revoked", ReadinessTone.Danger, "$host no longer shares Agents with this Server. Ask for a new link code there, then link again.",
            listOf(ReadinessAction.Edit))
        "agent_gone" -> Readiness("Removed", ReadinessTone.Danger, "$host no longer shares ${agent ?: "this Agent"}.", listOf(ReadinessAction.Remove))
        "incompatible" -> Readiness("Update needed", ReadinessTone.Warning,
            "$harness on $host doesn't speak the ACP version Kinuvo needs. Update it there, then check again.", check)
        "unavailable" -> Readiness("Unavailable", ReadinessTone.Neutral, state.reason, check)
        "unsupported" -> Readiness("Not on this Server", ReadinessTone.Neutral, state.reason)
        else -> Readiness("Error", ReadinessTone.Danger, state.reason, check)
    }
}

fun readiness(status: HarnessConnectionStatus, host: String) = readiness(status.readiness.state(), status.config.name, status.config.host ?: host,
    command = (status.config.connection as? ExecutionConnection.Acp)?.command?.firstOrNull())

/** Probes that leave nothing to run: a missing command, an ACP version Kinuvo can't use, or a Server that can't run commands. */
private val Unusable = setOf("not_found", "incompatible", "unsupported")

/** An ID from [name] that none of [taken] has. */
fun uniqueId(name: String, taken: Collection<String>, fallback: String = "agent"): String {
    val base = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { fallback }
    return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }
}

/**
 * An ACP command being added or edited: [command] runs as given with [args] passed literally, one per row, never through
 * a shell, and [environment] rows added to the Server's own environment. [probe] is the last check, of [probed].
 */
data class AcpForm(
    val id: String? = null,
    val name: String = "",
    val command: String = "",
    val args: List<String> = emptyList(),
    val environment: List<Pair<String, String>> = emptyList(),
    val probe: ExecutionProbe? = null,
    val probed: ExecutionConnection.Acp? = null,
    val checking: Boolean = false,
) {
    /** The command line, blank argument rows dropped. */
    val argv get() = listOf(command.trim()) + args.filter { it.isNotBlank() }
    /** The variables to add, blank rows dropped. */
    val env get() = environment.filter { (k, v) -> k.isNotBlank() || v.isNotBlank() }.associate { (k, v) -> k.trim() to v }
    val acp get() = ExecutionConnection.Acp(argv, env)
    /** The last check is of what's typed now. */
    val checked get() = probe != null && probed == acp
    /** Whether it looks like Codex, which keeps each account's sign-in under its own CODEX_HOME. */
    val codex get() = argv.any { "codex" in it.substringAfterLast('/') }

    fun problems(connections: List<HarnessConnectionStatus>): List<String> = buildList {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) add("Name is required")
        else if (trimmed.length > AgentDraft.MaxName) add("Name is at most ${AgentDraft.MaxName} characters")
        else if (connections.any { it.config.id != id && it.config.name.trim().equals(trimmed, ignoreCase = true) }) add("Another harness is called $trimmed")
        val cmd = command.trim()
        if (cmd.isEmpty()) add("Command is required")
        else if (cmd.any(Char::isWhitespace)) add("Put each argument on its own row under Arguments")
        else if ('/' in cmd && !isAbsolute(cmd)) add("Use a command on the Server's PATH or an absolute path")
        addAll(envProblems(environment))
    }

    /** Saving needs a check of this command that found something it can run; signing in can come later. */
    fun saveable(connections: List<HarnessConnectionStatus>) = problems(connections).isEmpty() && checked && probe?.readiness?.reasonCode !in Unusable

    fun connection(connections: List<HarnessConnectionStatus>) = HarnessConnection(
        id ?: uniqueId(name, connections.map { it.config.id }, "harness"), name.trim(), acp,
        connections.firstOrNull { it.config.id == id }?.config?.host,
    )

    companion object {
        fun of(status: HarnessConnectionStatus): AcpForm {
            val acp = status.config.connection as? ExecutionConnection.Acp
            val argv = acp?.command.orEmpty()
            return AcpForm(status.config.id, status.config.name, argv.firstOrNull().orEmpty(), argv.drop(1), acp?.environment.orEmpty().toList())
        }
    }
}

private val EnvName = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

/** Variable names must be plain, each once; folders like CODEX_HOME must be absolute paths on the harness's machine. */
fun envProblems(rows: List<Pair<String, String>>): List<String> = buildList {
    val named = rows.filter { (k, v) -> k.isNotBlank() || v.isNotBlank() }.map { (k, v) -> k.trim() to v }
    named.forEach { (k, v) ->
        when {
            k.isEmpty() -> add("Each environment value needs a variable name")
            !EnvName.matches(k) -> add("$k isn't a variable name: use letters, digits and _")
            k == "CODEX_HOME" && !isAbsolute(v) -> add("$k must be an absolute path, e.g. /Users/you/.codex-work")
        }
    }
    named.groupBy { it.first }.filter { it.key.isNotEmpty() && it.value.size > 1 }.keys.forEach { add("$it is set more than once") }
}

/** A working folder must be an absolute path on the machine the harness runs on. */
fun isAbsolute(path: String) = path.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(path)

fun cwdProblem(cwd: String): String? = when {
    cwd.isBlank() -> "Working folder is required"
    !isAbsolute(cwd.trim()) -> "Use an absolute path, e.g. /Users/you/project"
    else -> null
}

/**
 * Another Kinuvo Server to run Agents from: [name] is this Server's name for it, [token] the link code its Settings ›
 * Harnesses › Share Agents made. The code is kept privately by this Server and never shown again.
 */
data class LinkForm(val name: String = "", val url: String = "", val token: String = "", val linking: Boolean = false) {
    /** The address as the Server connects to it, or null when it isn't one. */
    val address get() = runCatching { normalizeUrl(url, allowCleartext = true) }.getOrNull()

    /** Whether the link code would cross a public network unencrypted. */
    val cleartext get() = address?.let { it.startsWith("ws://") && !privateNetworkHost(it.substringAfter("://").substringBefore('/').let { a -> if (a.startsWith('[')) a.substringBefore(']') + "]" else a.substringBefore(':') }) } == true

    fun problems(connections: List<HarnessConnectionStatus>): List<String> = buildList {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) add("Name is required")
        else if (trimmed.length > AgentDraft.MaxName) add("Name is at most ${AgentDraft.MaxName} characters")
        else if (connections.any { it.config.name.trim().equals(trimmed, ignoreCase = true) }) add("Another harness is called $trimmed")
        if (url.isBlank()) add("Address is required")
        else if (address == null) add("Use the other Server's http(s) address")
        if (token.isBlank()) add("Link code is required")
    }

    fun connection(connections: List<HarnessConnectionStatus>): HarnessConnection {
        val id = uniqueId(name, connections.map { it.config.id }, "server")
        return HarnessConnection(id, name.trim(), ExecutionConnection.Remote(id), name.trim())
    }
}

/** Agents this Server shares with another one, as one link code; [token] is shown once, after it's made. */
data class ShareForm(val name: String = "", val agents: Set<String> = emptySet(), val token: String? = null, val id: String? = null) {
    fun problems(): List<String> = buildList {
        if (name.isBlank()) add("Say who it's for, e.g. the other Server's name")
        if (agents.isEmpty()) add("Pick at least one Agent")
    }
}

/** Agents another Server may run from this one: Kinuvo's own and ACP agents, never ones this Server runs from elsewhere. */
fun shareable(agents: List<AgentInfo>) = agents.filter { it.executionKind != ExecutionKind.Remote }

/** Where a new Agent runs: Kinuvo itself, an ACP command on the selected Server's machine, or another Kinuvo Server. */
sealed interface HarnessChoice {
    data object Kinuvo : HarnessChoice
    data class Acp(val connectionId: String) : HarnessChoice
    data class Remote(val connectionId: String) : HarnessChoice
}

/** The new-Agent steps leading with where it runs; ACP Agents skip the Soul, Remote ones pick from what the other Server shares. */
fun agentSteps(choice: HarnessChoice) = when (choice) {
    HarnessChoice.Kinuvo -> listOf(HarnessStep, 4, 5)
    is HarnessChoice.Acp -> listOf(HarnessStep, 4)
    is HarnessChoice.Remote -> listOf(HarnessStep, PickStep)
}

/** Whether a harness can be picked for a new Agent: one that can't run anything here can't, one needing sign-in can. */
fun selectable(status: HarnessConnectionStatus) = status.readiness.reasonCode !in setOf("not_found", "unsupported", "unpaired")

/** A remote Agent's name here: its own, or with [host] when an Agent here already has it. */
fun remoteName(name: String, host: String, agents: List<AgentInfo>) =
    if (agents.any { it.name.trim().equals(name.trim(), ignoreCase = true) }) "$name ($host)" else name

enum class ApprovalOptionStyle { Primary, Outlined, Destructive }

/** ACP's permission option kinds: allowing once leads, always allowing is secondary, rejecting is destructive. */
fun approvalOptionStyle(kind: String) = when {
    kind == "allow_once" -> ApprovalOptionStyle.Primary
    kind.startsWith("reject") -> ApprovalOptionStyle.Destructive
    else -> ApprovalOptionStyle.Outlined
}
