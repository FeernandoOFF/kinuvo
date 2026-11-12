package kinuvo.server.plugin

import kinuvo.protocol.ComputerRequest
import kinuvo.protocol.ComputerResult
import kinuvo.protocol.ComputerActionKind

/** A screen an Agent can see and control: a paired Device that allowed it, e.g. a phone or a desktop. */
interface Computer {
    val id: String
    val name: String
    /** Android, macOS, Windows or Linux, once it has said. */
    val platform: String
    val actions: Set<ComputerActionKind> get() = ComputerActionKind.basic
    val available: Boolean
    suspend fun perform(request: ComputerRequest): ComputerResult
}
