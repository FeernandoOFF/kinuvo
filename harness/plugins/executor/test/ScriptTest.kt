package kinuvo.harness.executor

import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScriptTest {
    private fun run(code: String, limits: Limits = Limits(), tools: List<String> = listOf("echo", "fail"), log: MutableList<String> = mutableListOf()) = runBlocking {
        runScript(code, tools, limits) { name, args ->
            log += "$name $args"
            if (name == "fail") SessionEntry.ToolResult("", name, "The user denied this call.", isError = true)
            else SessionEntry.ToolResult("", name, args)
        }
    }

    @Test fun returnsValuesAndLogs() {
        val r = run("console.log('hi'); return {a: 1 + 1}")
        assertNull(r.error)
        assertEquals("{\n  \"a\": 2\n}", r.value)
        assertEquals("hi\n", r.logs)
        assertEquals("text", run("return 'text'").value)
        assertNull(run("1 + 1").value)
    }

    @Test fun bridgesToolCalls() {
        val log = mutableListOf<String>()
        val r = run("""
            const all = await Promise.all([1, 2, 3].map(n => tools.echo({n})))
            const failed = await tools.fail({}).catch(e => e.message)
            return all.map(r => r.n).join(',') + ' ' + failed
        """, log = log)
        assertEquals("1,2,3 The user denied this call.", r.value, r.error)
        assertEquals(listOf("echo {\"n\":1}", "echo {\"n\":2}", "echo {\"n\":3}", "fail {}"), log)
    }

    @Test fun uncaughtToolFailureFailsTheScript() {
        assertContains(assertNotNull(run("await tools.fail({}); return 'no'").error), "denied")
    }

    @Test fun onlyListedToolsExist() {
        assertEquals("[\"echo\",\"fail\"] undefined", run("return JSON.stringify(Object.keys(tools)) + ' ' + typeof tools.shell").value)
    }

    @Test fun limitsToolCalls() {
        val log = mutableListOf<String>()
        val r = run("for (let i = 0; i < 10; i++) await tools.echo({i})", Limits(calls = 3), log = log)
        assertContains(assertNotNull(r.error), "Too many tool calls")
        assertEquals(3, log.size)
    }

    @Test fun stopsRunawayCode() {
        assertContains(assertNotNull(run("while (true) {}", Limits(timeout = 300.milliseconds)).error), "Timed out")
        assertContains(assertNotNull(run("const f = n => f(n + 1) + 1; return f(0)").error), "stack overflow")
    }

    @Test fun capsOutput() {
        val r = run("console.log('x'.repeat(500)); return 'y'.repeat(500)", Limits(output = 100))
        assertContains(assertNotNull(r.value), "400 more characters cut")
        assertContains(r.logs, "more output cut")
    }

    @Test fun hasNoHostAccess() {
        for (code in listOf("return Java.type('java.lang.System').getenv()", "return load('/etc/hosts')", "return Polyglot.eval('js', '1')",
            "return require('fs')", "return process.env", "return fetch('https://example.com')", "return await import('os')",
            "return std.getenv('HOME')", "return os.exec(['ls'])")) {
            assertNotNull(run(code).error, code)
        }
        assertContains(assertNotNull(run("await new Promise(() => {})").error), "never finished")
        assertContains(assertNotNull(run("syntax error here").error), "SyntaxError")
    }

    @Test fun capsErrorsAndRejectsSerializationFailures() {
        val error = assertNotNull(run("throw 'x'.repeat(1000000)", Limits(output = 100)).error)
        assertTrue(error.length <= 100)
        assertContains(error, "characters cut")
        assertContains(assertNotNull(run("return 1n").error), "BigInt")
        assertContains(assertNotNull(run("return { toJSON() { throw new Error('serialize failed') } }").error), "serialize failed")
    }

    @Test fun boundsQueuedCallsAndArguments() {
        val log = mutableListOf<String>()
        val result = run("await Promise.all(Array.from({length: 10000}, () => tools.echo({})))", Limits(calls = 3), log = log)
        assertContains(assertNotNull(result.error), "Too many tool calls")
        assertTrue(log.size <= 3)
        assertContains(assertNotNull(run("await tools.echo({x: 'x'.repeat(70000)})").error), "arguments exceed")
    }

    @Test fun memoryExhaustionOnlyStopsTheScript() {
        val result = run("const a = []; while (true) a.push(new Array(1000000).fill(1));", Limits(timeout = 15.seconds))
        assertContains(assertNotNull(result.error), "memory limit")
        val buffers = run("const a = []; while (true) a.push(new ArrayBuffer(16000000));", Limits(timeout = 15.seconds))
        assertContains(assertNotNull(buffers.error), "memory limit")
        assertEquals("still alive", run("return 'still alive'").value)
    }

    @Test fun cancellationStopsExecutingJavaScript() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = async {
            runScript("await tools.echo({}); while (true) {}", listOf("echo"), Limits(timeout = 30.seconds)) { name, _ ->
                started.complete(Unit)
                SessionEntry.ToolResult("", name, "ok")
            }
        }
        withTimeout(30.seconds) { started.await() }
        delay(100)
        withTimeout(5.seconds) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test fun toolWaitDoesNotConsumeExecutionBudget() = runBlocking {
        val result = runScript("return await tools.echo({})", listOf("echo"), Limits(timeout = 2.seconds)) { name, _ ->
            delay(2200)
            SessionEntry.ToolResult("", name, "waited")
        }
        assertEquals("waited", result.value, result.error)
    }
}
