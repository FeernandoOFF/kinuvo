package kinuvo.harness

import kinuvo.protocol.execution.RemoteExecutionResult

/** Another Kinuvo Server used as a harness; it lists the Agents its linked Server exposes. */
interface RemoteHarness : ExecutionFactory {
    suspend fun discover(linkId: String): RemoteExecutionResult

    /** Whether a Server can be linked through a private-tunnel invite from here. */
    val tunnelSupported: Boolean get() = false

    /** Pairs this Server, named [device], with the one that made [invite]; fails when [invite] is not a Server link invite. */
    suspend fun join(invite: String, device: String): RemoteTunnel = error("Linking through Tailcat is unavailable")
}

/** What pairing through an invite leaves: the secret [link] that reopens the tunnel, and the link code [token] sent through it. */
class RemoteTunnel(val link: String, val token: String) {
    override fun toString() = "RemoteTunnel()"
}
