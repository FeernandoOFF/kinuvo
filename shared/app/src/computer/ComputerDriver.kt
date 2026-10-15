package kinuvo.app.computer

import kinuvo.protocol.ComputerRequest
import kinuvo.protocol.ComputerResult

/** Lets Agents see and control this Device's screen: Android's Accessibility service, or cua-driver on desktops. */
interface ComputerDriver {
    /** What the OS needs before Kinuvo can control the screen, shown in This Device's tools. */
    val requirement: String
    /** Whether the OS currently lets Kinuvo control the screen. */
    val ready: Boolean
    suspend fun perform(request: ComputerRequest): ComputerResult
}
