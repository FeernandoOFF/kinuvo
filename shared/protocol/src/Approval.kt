package kinuvo.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ApprovalDecision {
    @Serializable @SerialName("allow_once")
    data object AllowOnce : ApprovalDecision

    @Serializable @SerialName("always_allow")
    data object AlwaysAllow : ApprovalDecision

    @Serializable @SerialName("deny")
    data class Deny(val note: String? = null) : ApprovalDecision
}

/** How risky a tool call is; the Agent's risk tolerance decides which levels run without an Approval. */
@Serializable
enum class Risk { Low, Medium, High }

/** An Agent's `approvals.yaml`: what "Always allow" saved, and Deny rules that block calls before anything else. */
@Serializable
data class TrustRules(
    /** Stable tool keys, or a tool key followed by a per-app scope. */
    val trusted: List<String> = emptyList(),
    /** Commands (e.g. "git status") that `shell` may run with any further arguments; High-risk ones only exactly. */
    val shell: List<String> = emptyList(),
    val deny: DenyRules = DenyRules(),
)

/** [tools] holds stable tool keys or local names (all instances); [shell] globs match commands and the whole line. */
@Serializable
data class DenyRules(val tools: List<String> = emptyList(), val shell: List<String> = emptyList()) {
    fun blockedTool(identity: ToolIdentity?): String? = tools.firstOrNull { it == identity?.key || it == identity?.localId }
}
