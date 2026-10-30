package kinuvo.harness.server

import co.touchlab.stately.concurrency.Lock
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.core.*
import kinuvo.harness.core.tools.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.datetime.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import kotlin.time.Clock

class DeviceTools(
    private val log: SessionLog,
    private val questions: () -> Broker<Map<String, List<String>>?>,
    private val zone: () -> TimeZone,
) : HarnessPlugin {
    override val definition = PluginDefinition("kinuvo.devices", "Device tools")
    internal val lock = Lock()
    var paired: () -> List<ToolDevice> = { emptyList() }
    private data class Connection(val tools: Set<DeviceToolKind>, val active: Boolean, val read: suspend (DeviceToolRequest) -> DeviceToolResult, val failed: Boolean = false)
    private val connections = MutableStateFlow(emptyMap<String, Connection>())

    fun connect(id: String, tools: Set<DeviceToolKind>, active: Boolean, read: suspend (DeviceToolRequest) -> DeviceToolResult) {
        connections.update { it + (id to Connection(tools, active, read)) }
    }

    fun disconnect(id: String, read: suspend (DeviceToolRequest) -> DeviceToolResult) {
        connections.update { if (it[id]?.read === read) it - id else it }
    }

    fun owns(id: String, read: suspend (DeviceToolRequest) -> DeviceToolResult) = connections.value[id]?.read === read

    fun devices() = paired().map { it.copy(available = connections.value[it.id]?.let { it.active && !it.failed } == true) }

    override val features = setOf(ServerFeature.DeviceTools)
    private fun catalog(run: RunContext): List<ResolvedTool> {
        if (run.sessionId == null || ToolNames.DEVICE_TOOLS !in run.agent.config.tools) return emptyList()
        return listOf(ResolvedTool("device_tools")) + listOf(DeviceToolKind.Contacts to "contacts_lookup", DeviceToolKind.Sleep to "sleep_sessions", DeviceToolKind.Workouts to "workouts_list").flatMap { (kind, name) ->
            val candidates = devices().filter { kind in it.tools }
            if (candidates.isEmpty()) listOf(ResolvedTool(name, location = ExecutionLocation.Device,
                state = CapabilityState.unavailable("Enable this tool on a paired Device", "device_not_shared")))
            else candidates.map { ResolvedTool(name, location = ExecutionLocation.Device, target = it.id,
                state = if (it.available) CapabilityState() else CapabilityState.unavailable("Open Kinuvo on ${it.name}", "device_offline")) }
        }
    }

    override suspend fun instructions(agent: Agent): InstructionSection? =
        if (ToolNames.DEVICE_TOOLS !in agent.config.tools || ToolNames.KNOWLEDGE !in agent.config.tools) null else InstructionSection(
            "Journaling",
            "When asked to prepare a daily journal, follow the user's Knowledge Conventions. If they don't define a journal layout, " +
                "use type Journal, title YYYY-MM-DD and date property YYYY-MM-DD in the user's local date. Search before creating a Note. " +
                "Ask for reflections with the form tool when available, then use the sleep and workout tools for relevant dates. " +
                "Respect Skip; unavailable or empty Health results aren't evidence of no sleep or exercise. Use only supplied or read data, " +
                "including its source and read time; label examples as sample data. Don't import Contacts into journals. " +
                "Keep generated content under ## Kinuvo between <!-- kinuvo-journal:start --> and <!-- kinuvo-journal:end -->. " +
                "Read the existing Note and replace only the exact marked block with the Knowledge rewrite tool, or append the block if absent. " +
                "Preserve all writing outside it. If markers are duplicated or incomplete, ask before changing the Note. " +
                "Refresh the block on repeat updates instead of appending another summary.",
        )

    override suspend fun prepare(run: RunContext, activate: Boolean): PluginTools {
        if (run.sessionId == null || ToolNames.DEVICE_TOOLS !in run.agent.config.tools) return PluginTools()
        return PluginTools(listOf(
            object : Tool {
                override val risk = Risk.Low
                override val descriptor = ToolDescriptor(ToolNames.DEVICE_TOOLS, "List paired Devices, enabled read tools and foreground availability. Use the id to choose a Device when several have the same tool.", emptyList())
                override suspend fun execute(args: JsonObject) = ProtocolJson.encodeToString(ListSerializer(ToolDevice.serializer()), devices())
            },
            readTool(run, DeviceToolKind.Contacts, "contacts_lookup", "Find Contacts by name, phone number or email. Returns only matching names, phone numbers and emails, up to 20 results."),
            readTool(run, DeviceToolKind.Workouts, "workouts_list", "Read individual Health workouts: type, start/end, duration, distance and energy when available. iOS only; no routes or raw measurements."),
            readTool(run, DeviceToolKind.Sleep, "sleep_sessions", "Read Health sleep sessions and stages, with sleep duration excluding in-bed/awake time and overlapping samples. iOS only. Empty results may mean no data or no read permission."),
        ), catalog = catalog(run))
    }

    private fun readTool(run: RunContext, kind: DeviceToolKind, name: String, description: String) = object : Tool {
        override val risk = Risk.Low
        override val descriptor = ToolDescriptor(
            name, "$description Requires an enabled paired Device. If it cannot answer, the run waits for it to open, or for the user to Skip. Never infer data from an unavailable result.",
            requiredParameters = if (kind == DeviceToolKind.Contacts) listOf(parameter("query", "Name, phone number or email to find")) else listOf(
                parameter("start_date", "First date, YYYY-MM-DD"), parameter("end_date", "Last date inclusive, YYYY-MM-DD; at most 31 days"),
            ),
            optionalParameters = listOf(parameter("device_id", "Id from device_tools; required when multiple Devices have this tool")) +
                if (kind == DeviceToolKind.Contacts || kind == DeviceToolKind.Workouts) listOf(ToolParameterDescriptor("limit", "Result limit (default 20, max 20)", ToolParameterType.Integer)) else emptyList(),
        )
        override suspend fun execute(args: JsonObject): String = error("Device reads need a Session tool call")
        override suspend fun execute(args: JsonObject, callId: String): String {
            val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 20)
            val request = if (kind == DeviceToolKind.Contacts) {
                val query = args.requireString("query").trim()
                require(query.length <= 200) { "Query is too long" }
                DeviceToolRequest(kind, query = query, limit = limit)
            } else {
                val start = LocalDate.parse(args.requireString("start_date"))
                val end = LocalDate.parse(args.requireString("end_date"))
                require(start <= end && start.daysUntil(end) < 31) { "Choose 1–31 days" }
                val tz = zone()
                DeviceToolRequest(kind, start = start.atStartOfDayIn(tz).toString(), end = end.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz).toString(), timeZone = tz.id, limit = limit)
            }
            val candidates = devices().filter { kind in it.tools && (args.string("device_id") == null || args.string("device_id") == it.id) }
            if (candidates.isEmpty()) return result(DeviceToolStatus.Unavailable, "No paired Device has this tool enabled. Enable it in that Device's settings.")
            require(candidates.size == 1) { "Choose device_id from device_tools; several Devices have this tool" }
            return ProtocolJson.encodeToString(DeviceToolResult.serializer(), invoke(run, callId, candidates.single(), request))
        }
    }

    private suspend fun invoke(run: RunContext, callId: String, device: ToolDevice, request: DeviceToolRequest): DeviceToolResult {
        while (true) {
            if (paired().none { it.id == device.id && request.kind in it.tools }) return DeviceToolResult(DeviceToolStatus.Denied, message = "Device tool was disabled")
            val connection = connections.value[device.id]?.takeIf { it.active && !it.failed && request.kind in it.tools }
            if (connection != null) {
                val response = try {
                    withTimeoutOrNull(60_000) { connection.read(request) }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                if (response != null && response.status != DeviceToolStatus.Unavailable) {
                    if (paired().none { it.id == device.id && request.kind in it.tools }) return DeviceToolResult(DeviceToolStatus.Denied, message = "Device tool was disabled during the read")
                    require(ProtocolJson.encodeToString(DeviceToolResult.serializer(), response).length <= 128_000) { "Device result is too large; choose a smaller range" }
                    return response.copy(deviceId = device.id, deviceName = device.name, readAt = Clock.System.now().toString())
                }
                connections.update { current -> if (current[device.id] === connection) current + (device.id to connection.copy(failed = true)) else current }
            }
            val pending = questions().register()
            try {
                log.publish(run.agent.id, run.sessionId!!, AgentEvent.QuestionsAsked(pending.id, callId, "Open Kinuvo on ${device.name} to read ${request.kind.name.lowercase()}. This continues automatically; Skip to continue without this data.", emptyList()))
                val skipped = coroutineScope {
                    val answer = async { pending.await() }
                    val ready = async { connections.first { map ->
                        map[device.id]?.let { it.active && !it.failed && request.kind in it.tools } == true || paired().none { it.id == device.id && request.kind in it.tools }
                    } }
                    try {
                        select<Boolean> {
                            answer.onAwait { answer ->
                                if (answer != null) connections.update { map -> map[device.id]?.let { map + (device.id to it.copy(failed = false)) } ?: map }
                                answer == null
                            }
                            ready.onAwait { false }
                        }
                    } finally { answer.cancel(); ready.cancel() }
                }
                if (skipped) return DeviceToolResult(DeviceToolStatus.Skipped, message = "The user skipped this Device read. Data is unavailable; do not treat it as zero or invent it.")
            } finally {
                pending.close()
                withContext(NonCancellable) { log.publish(run.agent.id, run.sessionId!!, AgentEvent.QuestionsAnswered(callId)) }
            }
        }
    }

    private fun parameter(name: String, description: String) = ToolParameterDescriptor(name, description, ToolParameterType.String)
    private fun result(status: DeviceToolStatus, message: String) = ProtocolJson.encodeToString(DeviceToolResult.serializer(), DeviceToolResult(status, message = message))
}
