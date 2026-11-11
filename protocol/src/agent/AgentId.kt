package kinuvo.protocol.agent

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class AgentId(val value: String) {
    override fun toString() = value
}
