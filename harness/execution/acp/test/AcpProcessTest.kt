@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package kinuvo.execution.acp

import com.agentclientprotocol.client.v2.ElicitationHandler
import com.agentclientprotocol.model.v2.CreateElicitationRequest
import com.agentclientprotocol.model.v2.CreateElicitationResponse
import kinuvo.protocol.ExecutionConnection
import kinuvo.protocol.HarnessConnection
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.Test

class AcpProcessTest {
    @Test fun probeDeadlineReturnsReadinessAndReapsUnresponsiveProcess() = runBlocking {
        val dir = Files.createTempDirectory("kinuvo-acp-timeout")
        val script = dir.resolve("agent.py")
        val pid = dir.resolve("pid")
        Files.writeString(script, """
            import os, signal, time
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            with open(r'${pid}', 'w') as f: f.write(str(os.getpid()))
            time.sleep(600)
        """.trimIndent())
        try {
            val result = withTimeout(10_000) {
                AcpExecutionFactory(probeTimeoutMillis = 1_000).probe(HarnessConnection("c", "Hung", ExecutionConnection.Acp(listOf("/usr/bin/env", "python3", script.toString()))))
            }
            assertFalse(result.readiness.online)
            assertEquals("unavailable", result.readiness.reasonCode)
            val processId = Files.readString(pid).toLong()
            assertFalse(ProcessHandle.of(processId).map { it.isAlive }.orElse(false))
        } finally {
            Files.deleteIfExists(script)
            Files.deleteIfExists(pid)
            Files.deleteIfExists(dir)
        }
    }

    @Test fun sdkNegotiatesV2OverProcessAndReportsExit() = runBlocking {
        val dir = Files.createTempDirectory("kinuvo-acp-mock")
        val script = dir.resolve("agent.py")
        Files.writeString(script, """
            import sys, json, os
            assert os.environ["CODEX_HOME"] == "/temporary-account"
            for line in sys.stdin:
                r = json.loads(line)
                if r.get('method') == 'initialize':
                    print(json.dumps({'jsonrpc':'2.0','id':r['id'],'result':{'protocolVersion':r['params']['protocolVersion'],'info':{'name':'mock','version':'1'},'capabilities':{}}}), flush=True)
        """.trimIndent())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lost = CompletableDeferred<Unit>()
        try {
            val connection = withTimeout(10000) {
                AcpConnection.open(scope, ExecutionConnection.Acp(listOf("/usr/bin/env", "python3", script.toString()), mapOf("CODEX_HOME" to "/temporary-account")),
                    object : ElicitationHandler {
                        override suspend fun createElicitation(request: CreateElicitationRequest): CreateElicitationResponse = error("Unexpected elicitation")
                    }, { lost.complete(Unit) })
            }
            connection.close()
            withTimeout(10000) { lost.await() }
        } finally {
            scope.cancel()
            Files.deleteIfExists(script)
            Files.deleteIfExists(dir)
        }
    }
}
