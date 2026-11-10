package kinuvo.harness.testing

import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.core.AgentModel
import kinuvo.harness.core.AgentRun
import kinuvo.harness.core.AgentTracing
import kinuvo.harness.core.Approvals
import kinuvo.harness.core.Agent
import kinuvo.harness.core.Broker
import kinuvo.harness.core.ModelProfile
import kinuvo.harness.core.routines.RoutineRun
import kinuvo.harness.core.store.Store
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.execution.api.ToolReports
import kinuvo.harness.core.tools.ToolService
import kinuvo.protocol.Attachment
import kinuvo.protocol.ModelTier
import kinuvo.protocol.RequestKind

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
    advertisedTools: List<ToolDescriptor> = tools.map { it.descriptor },
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
