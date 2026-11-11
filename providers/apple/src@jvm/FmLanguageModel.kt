package kinuvo.providers.apple

import kinuvo.protocol.CapabilityState
import ai.koog.prompt.Prompt
import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.protocol.ProtocolJson
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Only Apple's system CLI is used, including in launchd hosts with a minimal PATH. */
fun fmProvider(): AppleProvider? = if (System.getProperty("os.name").startsWith("Mac") && File("/usr/bin/fm").canExecute())
    FmLanguageModel().let { AppleProvider(it, Dispatchers.IO, it::estimateTokens) } else null

internal data class FmResult(val status: Int, val output: String, val error: String = "")

internal class FmLanguageModel(
    private val execute: suspend (List<String>) -> FmResult = { fmCommand(it, timeoutSeconds = if (it.first() == "respond") 120 else 10) },
) : AppleLanguageModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = ConcurrentHashMap<String, Job>()

    override fun contextSize(model: String, completion: (Int) -> Unit) = completion(if (model == SYSTEM) 4096 else 0)

    private suspend fun readiness(model: String): CapabilityState = try {
        checkReadiness(model)
    } catch (e: IOException) {
        CapabilityState.unavailable("Could not run /usr/bin/fm on the Mac running this Server. Check that Apple’s fm command is installed and executable, then restart the Server. ${e.message}", "fm_unavailable")
    }

    private suspend fun checkReadiness(model: String): CapabilityState {
        if (model != SYSTEM) return CapabilityState.unavailable("fm offers only the Apple on-device model in Kinuvo.", "unsupported_model")
        val license = execute(listOf("license", "--status"))
        if (license.status == 69 || (license.output + license.error).contains("not agreed", ignoreCase = true)) {
            return CapabilityState.unavailable(
                "On the Mac running this Server, open Terminal and run sudo fm license to review and accept Apple's terms. Acceptance applies to every user on that Mac. Then refresh models in Kinuvo.",
                "license_required")
        }
        if (license.status != 0) return CapabilityState.unavailable(
            "Could not check Apple's CLI license. Run fm license --status on the Mac running this Server. ${license.details()}", "fm_unavailable")
        val available = execute(listOf("available", "--model", SYSTEM))
        if (available.status == 0) return CapabilityState()
        return CapabilityState.unavailable(
            "Apple's on-device model is unavailable. On the Mac running this Server, enable Apple Intelligence in System Settings → Apple Intelligence & Siri and wait for the model download. Run fm available in Terminal for details, then refresh models in Kinuvo. ${available.details()}",
            "model_unavailable")
    }

    // The shared bridge uses synchronous availability callbacks; AppleProvider dispatches these to IO on JVM.
    override fun unavailableReason(model: String): String? = runBlocking { readiness(model).reason }
    override fun unavailableCode(model: String): String? = runBlocking { readiness(model).reasonCode }

    override fun generate(requestId: String, model: String, instructions: String, prompt: String, toolsJson: String,
        onText: (String) -> Unit, completion: (String?, String?) -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val state = readiness(model)
                check(state.actionable) { state.reason.orEmpty() }
                val tools = ProtocolJson.decodeFromString<List<AppleTool>>(toolsJson)
                val args = mutableListOf("respond", "--model", SYSTEM, "--no-stream", "--greedy", "--instructions")
                args += fmInstructions(instructions, tools.isNotEmpty())
                if (tools.isNotEmpty()) {
                    val schema = execute(fmSchemaCommand(tools))
                    check(schema.status == 0) { "fm could not prepare tool responses: ${schema.details()}" }
                    args += listOf("--schema", compactSchema(schema.output))
                }
                args += listOf("--text", prompt)
                val result = execute(args)
                check(result.status == 0) { "Apple's model could not finish: ${result.details()}" }
                ensureActive()
                check(result.output.isNotBlank()) { "Apple's model returned no response." }
                val turn = if (tools.isEmpty()) buildJsonObject {
                    put("tool", "reply"); put("reply", result.output.trimEnd())
                } else ProtocolJson.parseToJsonElement(result.output).jsonObject
                completion(turn.toString(), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                completion(null, e.message ?: "Apple's model could not finish.")
            } finally {
                requests.remove(requestId)
            }
        }
        requests[requestId] = job
        job.start()
    }

    suspend fun estimateTokens(prompt: Prompt, descriptors: List<ToolDescriptor>): Int {
        val tools = appleOffer(descriptors)
        val instructions = appleInstructions(prompt.messages)
        val args = mutableListOf("count-tokens", "--quiet", "--instructions", fmInstructions(instructions, tools.isNotEmpty()),
            "--text", applePrompt(prompt.messages, descriptors))
        if (tools.isNotEmpty()) {
            val schema = execute(fmSchemaCommand(tools))
            check(schema.status == 0) { "fm could not count tool context: ${schema.details()}" }
            args += listOf("--text", compactSchema(schema.output))
        }
        val result = execute(args)
        check(result.status == 0) { "fm could not count context: ${result.details()}" }
        return result.output.trim().toInt()
    }

    override fun cancel(requestId: String) { requests.remove(requestId)?.cancel() }
    override fun close() { scope.cancel(); requests.clear() }
}

private fun compactSchema(schema: String) = ProtocolJson.parseToJsonElement(schema).toString()

private fun fmInstructions(instructions: String, tools: Boolean): String = instructions + if (!tools) "" else """

Request one tool per turn: set tool to its name and fill its argument fields. The harness runs it and returns the result.
Never claim success before a successful result. To answer, set tool to reply and put your answer in reply.
"""

internal fun fmSchemaCommand(tools: List<AppleTool>): List<String> {
    val reply = AppleTool("reply", "Answer the user", listOf(AppleParameter("reply", "The answer to the user", "string", true)))
    return listOf("schema", "object", "--name", "Turn", "--anyOf") + (listOf(reply) + tools).flatMap { tool ->
        val properties = buildJsonObject {
            put("tool", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf(JsonPrimitive(tool.name)))) })
            tool.parameters.forEach { parameter -> put(parameter.name, buildJsonObject {
                put("type", parameter.type)
                put("description", parameter.description)
                parameter.values?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
                parameter.items?.let { put("items", buildJsonObject { put("type", it) }) }
            }) }
        }
        val schema = buildJsonObject {
            put("title", "call_${tool.name}")
            put("description", tool.description)
            put("type", "object")
            put("properties", properties)
            put("required", JsonArray((listOf("tool") + tool.parameters.filter { it.required }.map { it.name }).map(::JsonPrimitive)))
            put("x-order", JsonArray(properties.keys.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }
        listOf("--schema", schema.toString())
    }
}

private fun FmResult.details() = (error.ifBlank { output }).trim().replace(Regex("\u001B\\[[;\\d]*m"), "").take(1500)

internal suspend fun fmCommand(arguments: List<String>, executable: String = "/usr/bin/fm", timeoutSeconds: Long = 120): FmResult = withContext(Dispatchers.IO) {
    coroutineScope {
        val process = ProcessBuilder(listOf(executable) + arguments).start()
        process.outputStream.close()
        val output = async { process.inputStream.bufferedReader().use { it.readText() } }
        val error = async { process.errorStream.bufferedReader().use { it.readText() } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        try {
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                ensureActive()
                check(System.nanoTime() < deadline) { "fm timed out. Run fm available on the Mac running this Server to check Apple Intelligence." }
            }
            FmResult(process.exitValue(), output.await(), error.await())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
