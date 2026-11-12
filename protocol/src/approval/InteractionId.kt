package kinuvo.protocol.approval

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class InteractionId(val value: String) {
    override fun toString() = value
}
