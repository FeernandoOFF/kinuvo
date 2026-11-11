package kinuvo.testing.server

import kinuvo.harness.builtin.AgentModel
import kinuvo.harness.builtin.AgentRun
import kinuvo.harness.builtin.AgentTracing
import kinuvo.server.Approvals
import kinuvo.harness.Agent
import kinuvo.server.Broker
import kinuvo.server.routines.RoutineRun
import kinuvo.server.store.Store
import kinuvo.server.plugin.Tool
import kinuvo.harness.ToolReports
import kinuvo.harness.ToolSpec
import kinuvo.server.tools.ToolService
import kinuvo.protocol.Attachment
import kinuvo.protocol.ModelTier
import kinuvo.protocol.RequestKind
import kinuvo.harness.ModelProfile

/** An [AgentRun] over a [ToolService] built from [tools] and the run's policy, for tests that exercise the whole path. */
fun AgentRun(
    agent: Agent,
    store: Store,
    model: AgentModel,
    tools: List<Tool>,
    warnings: List<String> = emptyList(),
    context: String = "",
    approvals: Approvals = Approvals.none(),
    questions: Broker<Map<String, List<String>>?>? = null,
    advertisedTools: List<ToolSpec> = tools.map { it.descriptor },
    contextTokens: Int? = null,
    requestKind: RequestKind = RequestKind.Chat,
    attach: ((String, ByteArray) -> Attachment)? = null,
    tracing: AgentTracing? = null,
    traceSessionId: String? = null,
    routine: RoutineRun? = null,
    reports: ToolReports? = null,
    profile: ModelProfile = ModelProfile.of(ModelTier.Frontier),
): AgentRun {
    val service = ToolService(tools, advertisedTools, approvals, questions, attach, profile.resultLimits.maxItems, profile.tools.callsPerTurn, reports)
    return AgentRun(agent, store, model, service, service, warnings, context, contextTokens, requestKind, tracing, traceSessionId, routine, profile)
}
