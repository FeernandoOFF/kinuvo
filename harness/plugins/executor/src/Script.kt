package kinuvo.harness.executor

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** [timeout] counts only time running JavaScript, not waiting on tools and Approvals. [memory] caps the JavaScript heap in bytes. */
data class Limits(val timeout: Duration = 10.seconds, val memory: Long = 64L * 1024 * 1024, val calls: Int = 50, val output: Int = 20_000)

data class ScriptResult(val value: String?, val logs: String, val error: String? = null)

internal const val MAX_ARGUMENTS = 65_536
internal const val MAX_TOOL_RESULT = 262_144

private class Request(val name: String, val args: String, val reply: CompletableDeferred<String>)

/**
 * Runs JavaScript in its own QuickJS runtime with no host access. Tool calls run one at a time on the caller's
 * coroutine, so they emit their events and wait on Approvals like the model's own calls.
 */
suspend fun runScript(code: String, tools: List<String>, limits: Limits = Limits(), call: suspend (String, String) -> SessionEntry.ToolResult): ScriptResult {
    require(limits.output in 1..100_000 && limits.calls in 0..1000 && limits.memory > 0 && limits.timeout > Duration.ZERO)
    if (code.length > MAX_TOOL_RESULT) return ScriptResult(null, "", "Script source is too large.")
    val logs = CappedText(limits.output)
    var value: String? = null
    var error: String? = null
    var failure: String? = null
    var finished = false
    var calls = 0
    val requests = Channel<Request>(Channel.UNLIMITED)
    val js = QuickJs.create(Dispatchers.Default.limitedParallelism(1))
    try {
        js.memoryLimit = limits.memory
        js.evaluationTimeoutMillis = limits.timeout.inWholeMilliseconds
        js.defineBinding("__log", FunctionBinding<Any?> { a -> logs.append(a[0] as String); null })
        js.defineBinding("__done", FunctionBinding<Any?> { a -> value = (a[0] as String?)?.let { capped(it, limits.output) }; finished = true; null })
        js.defineBinding("__fail", FunctionBinding<Any?> { a -> error = capped(a[0] as String, limits.output); finished = true; null })
        // Replies start with 1 (ok) or 0 (error) so the Script rejects with exactly the tool's message.
        js.defineBinding("__call", AsyncFunctionBinding<String> { a ->
            val name = a[0] as String
            val args = a[1] as String
            when {
                failure != null -> "0$failure"
                ++calls > limits.calls -> "0Too many tool calls; the limit is ${limits.calls}.".also { failure = it.drop(1) }
                args.length > MAX_ARGUMENTS -> "0Tool arguments exceed the Script bridge limit ($MAX_ARGUMENTS characters).".also { failure = it.drop(1) }
                name !in tools -> "0Unknown tool '$name'."
                else -> Request(name, args, CompletableDeferred()).also { requests.send(it) }.reply.await()
            }
        })
        coroutineScope {
            val evaluation = async { js.evaluate<Any?>(script(tools, code), "script.js") }
            while (true) {
                val request = select<Request?> { evaluation.onAwait { null }; requests.onReceive { it } } ?: break
                val result = call(request.name, request.args)
                request.reply.complete(when {
                    result.output.length > MAX_TOOL_RESULT -> "0Tool result exceeds the Script bridge limit ($MAX_TOOL_RESULT characters)."
                    result.isError -> "0" + result.output
                    else -> "1" + result.output
                })
            }
        }
        if (!finished) error = "The script awaited something that never finished; only tools.* calls can be awaited."
    } catch (e: QuickJsInterruptedException) {
        error = "Timed out running JavaScript (execution budget ${limits.timeout})."
    } catch (e: CancellationException) {
        throw e
    } catch (e: QuickJsException) {
        error = capped(e.message ?: e.toString(), limits.output)
    } finally {
        js.close()
    }
    error = failure ?: error?.let {
        when {
            it.startsWith("InternalError: interrupted") -> "Timed out running JavaScript (execution budget ${limits.timeout})."
            "out of memory" in it -> "Stopped: the script ran out of memory (memory limit ${limits.memory / 1024 / 1024} MB)."
            else -> it
        }
    }
    return ScriptResult(value.takeIf { error == null }, logs.text(), error)
}

private fun script(tools: List<String>, code: String) =
    HEAD.replace("TOOLS", Json.encodeToString(tools)) + "\n" + code + "\n" + TAIL

private val HEAD = """
    (() => {
      const call = __call, done = __done, fail = __fail, log = __log
      for (const name of ['__call', '__done', '__fail', '__log']) delete globalThis[name]
      const text = v => { if (typeof v === 'string') return v; try { return JSON.stringify(v) ?? String(v) } catch { return String(v) } }
      const print = (...a) => log(a.map(text).join(' ') + '\n')
      globalThis.console = Object.freeze({ log: print, info: print, warn: print, error: print, debug: print })
      const tool = name => async (args = {}) => {
        const r = await call(name, JSON.stringify(args))
        if (r[0] === '0') throw new Error(r.slice(1))
        try { return JSON.parse(r.slice(1)) } catch { return r.slice(1) }
      }
      globalThis.tools = Object.freeze(Object.fromEntries(TOOLS.map(n => [n, tool(n)])))
      ;(async () => {
""".trimIndent()

private val TAIL = """
      })().then(v => done(v === undefined ? null : typeof v === 'string' ? v : JSON.stringify(v, null, 2) ?? null))
        .catch(e => fail(e instanceof Error ? String(e) + (e.stack ? '\n' + e.stack : '') : String(e)))
    })()
""".trimIndent()

private class CappedText(private val max: Int) {
    private val text = StringBuilder()
    private var dropped = false
    fun append(s: String) {
        val room = (max - text.length).coerceAtLeast(0)
        text.append(s, 0, minOf(s.length, room))
        if (s.length > room) dropped = true
    }
    fun text(): String {
        val suffix = "\n… [more output cut]"
        return if (dropped) text.take((max - suffix.length).coerceAtLeast(0)).toString() + suffix.take(max) else text.toString()
    }
}

internal fun capped(text: String, max: Int): String {
    if (text.length <= max) return text
    val suffix = "\n… [${text.length - max} more characters cut]"
    return text.take((max - suffix.length).coerceAtLeast(0)) + suffix.take(max)
}
