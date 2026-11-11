package kinuvo.harness

import kinuvo.protocol.execution.RemoteExecutionResult

/** Another Kinuvo Server used as a harness; it lists the Agents its linked Server exposes. */
interface RemoteHarness : ExecutionFactory {
    suspend fun discover(linkId: String): RemoteExecutionResult
}
