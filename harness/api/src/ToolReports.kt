package kinuvo.harness

import kinuvo.protocol.session.SessionEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Server-owned work survives the tool call and reports once to its Session. */
class ToolReports(private val scope: CoroutineScope, private val completed: suspend (SessionEntry.ToolReport) -> Unit) {
    fun start(name: String, callId: String?, work: suspend () -> String) {
        scope.launch {
            val content = try { work() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { "Failed: ${e.message ?: "Background work failed"}" }
            completed(SessionEntry.ToolReport(name, callId, content))
        }
    }
}
