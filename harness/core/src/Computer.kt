package kinuvo.harness.core

import kinuvo.protocol.ComputerRequest
import kinuvo.protocol.ComputerResult

/** A screen an Agent can see and control: a paired Device that allowed it, e.g. a phone or a desktop. */
interface Computer {
    val id: String
    val name: String
    /** Android, macOS, Windows or Linux, once it has said. */
    val platform: String
    val available: Boolean
    suspend fun perform(request: ComputerRequest): ComputerResult
}
