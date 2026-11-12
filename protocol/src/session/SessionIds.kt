package kinuvo.protocol.session

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class SessionId(val value: String) {
    override fun toString() = value
}

@Serializable
@JvmInline
value class RunId(val value: String) {
    override fun toString() = value
}

@Serializable
@JvmInline
value class ToolCallId(val value: String) {
    override fun toString() = value
}
