package kinuvo.harness.core

import kinuvo.harness.core.tools.Action
import kinuvo.harness.core.tools.Assessment
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.ToolScope
import kinuvo.harness.core.tools.ToolKind
import kinuvo.harness.core.tools.string
import kinuvo.harness.core.tools.policyKey
import kinuvo.harness.core.tools.withToolKey
import kinuvo.protocol.ApprovalDecision
import kinuvo.protocol.Risk
import kinuvo.protocol.TrustRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.uuid.Uuid
import kinuvo.protocol.DenyRules

/** Which risk levels run without an Approval: strict asks for everything, full skips risk-based Approvals. Deny rules apply to all. */
enum class Tolerance(val id: String, val autoUpTo: Risk?) {
    Strict("strict", null),
    Default("default", Risk.Low),
    Relaxed("relaxed", Risk.Medium),
    Full("full", Risk.High);

    companion object {
        val ids = entries.map { it.id }

        fun of(id: String) = requireNotNull(entries.firstOrNull { it.id == id }) { "Unknown Risk tolerance: $id" }
    }
}

/** Each Agent's Trust rules: what "Always allow" saved; plus the Server's, which every Agent follows. */
interface TrustRulesRepository {
    suspend fun get(agentId: String): TrustRules
    /** Read-modify-write in one step. */
    suspend fun update(agentId: String, transform: (TrustRules) -> TrustRules)
    suspend fun server(): TrustRules = TrustRules()
    suspend fun updateServer(transform: (TrustRules) -> TrustRules): Unit = error("Server rules can't be changed here")
}

/** [agentId]'s rules with the Server's added; Deny rules are checked first, so a Server block wins over any Trust rule. */
suspend fun TrustRulesRepository.effective(agentId: String): TrustRules {
    val (a, s) = get(agentId) to server()
    fun join(x: List<String>, y: List<String>) = (x + y).distinct()
    return TrustRules(join(a.trusted, s.trusted), join(a.shell, s.shell), DenyRules(join(a.deny.tools, s.deny.tools), join(a.deny.shell, s.deny.shell)))
}

/** What happens to a tool call: it runs, waits for an Approval, or is refused by a Deny rule. */
sealed interface Verdict {
    data object Run : Verdict
    /** [trustKeys] are what "Always allow" would save; empty when it can't be offered. */
    data class Ask(val assessment: Assessment, val trustKeys: List<String>) : Verdict
    data class Block(val reason: String) : Verdict
}

/**
 * Decides which tool calls of one Agent run, need an Approval or are blocked, from the call's [Assessment], the
 * risk [tolerance] and the Agent's Trust and Deny rules. Deterministic: the model has no say.
 */
class ApprovalPolicy(
    private val agent: Agent,
    private val rules: TrustRulesRepository,
    val tolerance: Tolerance = Tolerance.of(agent.config.approvals),
    private val session: SessionTrust.Scope? = null,
    private val scope: ToolScope = ToolScope.All,
) {
    suspend fun decide(tool: Tool, args: String = "{}"): Verdict {
        val key = tool.policyKey
        val rules = rules().let { r -> session?.get()?.let { s -> r.copy(trusted = (r.trusted + s.trusted).distinct(), shell = (r.shell + s.shell).distinct()) } ?: r }
        val json = runCatching { Json.parseToJsonElement(args).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        val command = if (tool.kind == ToolKind.Shell) json.string("command")?.let(ShellCommand::parse) else null
        blocked(tool, command, rules)?.let { return Verdict.Block(it) }
        if (!scope.allows(tool)) return Verdict.Block("Tool is outside this run’s scope.")
        if (tool.kind == ToolKind.AskUser) return Verdict.Run
        val assessment = tool.assess(json).let { if (tool.kind == ToolKind.Shell) it else it.withToolKey(tool.descriptor.name, key) }
        if (scope.mustAsk(tool) || assessment.actions.any { it.alwaysAsk }) return ask(assessment, emptyList())
        val limit = scope.tolerance(tolerance).autoUpTo ?: return ask(assessment, emptyList())
        val saved = if (tool.kind == ToolKind.Shell) rules.shell.map(ShellCommand::words) else rules.trusted.map { it.split(' ') }
        fun trusted(a: Action) = saved.any(a::matches)
        val pending = assessment.actions.filter { a -> a.risk > limit && !(a.floor <= limit && trusted(a)) }
        return if (pending.isEmpty()) Verdict.Run else ask(assessment, pending.takeIf { p -> p.none { !it.trustable || trusted(it) } }.orEmpty())
    }

    private fun ask(assessment: Assessment, pending: List<Action>) = Verdict.Ask(assessment, pending.map { ShellCommand.quote(it.key) }.distinct())

    private fun blocked(tool: Tool, command: ShellCommand?, rules: TrustRules): String? {
        rules.deny.blockedTool(tool.identity)
            ?.let { return "Blocked by the Deny rule for $it." }
        if (command == null) return null
        command.blocked()?.let { return "Blocked: this command $it." }
        return rules.deny.shell.firstOrNull(command::matches)?.let { "Blocked by the Deny rule `$it`." }
    }

    /** Saves [Verdict.Ask.trustKeys] so the same calls run without asking. */
    suspend fun trust(tool: Tool, keys: List<String>) {
        if (keys.isNotEmpty()) rules.update(agent.id) { it.trusting(tool, keys) }
    }

    /** Like [trust], but only for this Session; returns false when the run has no Session to remember it in. */
    fun trustForSession(tool: Tool, keys: List<String>): Boolean {
        val session = session ?: return false
        if (keys.isNotEmpty()) session.update { it.trusting(tool, keys) }
        return true
    }

    suspend fun rules(): TrustRules = rules.effective(agent.id)
}

private fun TrustRules.trusting(tool: Tool, keys: List<String>) =
    if (tool.kind == ToolKind.Shell) copy(shell = (shell + keys).distinct())
    else copy(trusted = (trusted + if (tool.kind == ToolKind.ComputerAction) keys else listOf(tool.policyKey)).distinct())

/** Trust rules from "Allow for this Session", per Session; kept in memory, so they end when the Server restarts. */
@OptIn(ExperimentalAtomicApi::class)
class SessionTrust {
    private val rules = AtomicReference(emptyMap<String, TrustRules>())

    fun scope(sessionId: String) = Scope(sessionId)

    inner class Scope internal constructor(private val sessionId: String) {
        fun get() = rules.load()[sessionId] ?: TrustRules()
        fun update(transform: (TrustRules) -> TrustRules) = rules.update { it + (sessionId to transform(it[sessionId] ?: TrustRules())) }
    }
}

/** Hands pending Approvals (or `ask_user` forms) to the UI and resumes the waiting run when the user answers. */
@OptIn(ExperimentalAtomicApi::class)
class Broker<T> {
    private val pending = AtomicReference(emptyMap<String, CompletableDeferred<T>>())

    fun register(): Pending {
        val id = Uuid.random().toString()
        val deferred = CompletableDeferred<T>()
        pending.update { it + (id to deferred) }
        return Pending(id, deferred)
    }

    inner class Pending internal constructor(val id: String, private val deferred: CompletableDeferred<T>) {
        suspend fun await(): T = deferred.await()
        fun close() = pending.update { it - id }
    }

    /** Returns false when nothing is waiting under [id] or it was already answered. */
    fun respond(id: String, answer: T): Boolean = pending.load()[id]?.complete(answer) == true
}

typealias ApprovalBroker = Broker<ApprovalDecision>

/**
 * [unattended] answers every call that needs an Approval instead of asking, e.g. denying in a Routine run. With [wait]
 * set, a call nobody answers in time is skipped and reported to [skipped] by tool name.
 */
class Approvals(
    val policy: ApprovalPolicy?,
    val broker: ApprovalBroker,
    val unattended: ApprovalDecision? = null,
    val wait: Duration? = null,
    val skipped: (String) -> Unit = {},
) {
    companion object {
        /** Everything runs without asking. */
        fun none() = Approvals(null, ApprovalBroker())
    }
}
