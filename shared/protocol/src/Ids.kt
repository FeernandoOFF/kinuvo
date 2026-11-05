package kinuvo.protocol

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class AgentId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class SessionId(val value: String) {
    override fun toString(): String = value
}
