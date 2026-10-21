package kinuvo.harness.core

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.tracing.export.batchSpanProcessor
import io.opentelemetry.kotlin.tracing.export.SpanProcessor
import io.opentelemetry.kotlin.tracing.export.toOtelKotlinSpanExporter
import io.opentelemetry.exporter.logging.otlp.internal.traces.OtlpStdoutSpanExporterBuilder
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData
import io.opentelemetry.sdk.trace.export.SpanExporter
import kinuvo.harness.core.store.AgentFiles
import kinuvo.protocol.RequestKind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

actual fun localTracing(files: AgentFiles): AgentTracing = FileTracing(files)

@OptIn(io.opentelemetry.kotlin.ExperimentalApi::class)
internal class FileTracing(private val files: AgentFiles, private val limit: Int = 4 * 1024 * 1024) : AgentTracing {
    private var sink: FileHandler? = null
    private var exporter: SpanExporter? = null
    private var closed = false

    @Synchronized
    private fun exporter(): SpanExporter {
        check(!closed) { "Tracing is closed" }
        return exporter ?: run {
            val directory = Paths.get(files.traces.toString())
            if (directory.fileSystem.supportedFileAttributeViews().contains("posix")) {
                val permissions = PosixFilePermissions.fromString("rwx------")
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(permissions))
                Files.setPosixFilePermissions(directory, permissions)
            } else Files.createDirectories(directory)
            val handler = FileHandler(directory.resolve("spans-%g.jsonl").toString(), limit, 4, true).apply {
                encoding = "UTF-8"
                formatter = object : Formatter() {
                    override fun format(record: LogRecord) = record.message + "\n"
                }
                filter = java.util.logging.Filter { it.level == Level.INFO && it.thrown == null }
            }
            val logger = Logger.getAnonymousLogger().apply {
                useParentHandlers = false
                level = Level.ALL
                addHandler(handler)
            }
            sink = handler
            // The pinned SDK exposes custom output through this builder; JSON encoding stays in OTel.
            OtlpStdoutSpanExporterBuilder(logger).build().also { exporter = it }
        }
    }

    override fun install(context: GraphAIAgent.FeatureContext, agentId: String, sessionId: String, runId: String, kind: RequestKind) {
        val delegate = exporter()
        val privateExporter = object : SpanExporter {
            override fun export(spans: Collection<SpanData>) = delegate.export(spans.map { span ->
                object : SpanData by span {
                    override fun getAttributes(): Attributes = span.attributes.toBuilder().removeIf { it.key !in traceAttributes }.build()
                    override fun getStatus(): StatusData = StatusData.create(span.status.statusCode, "")
                    override fun getEvents() = emptyList<io.opentelemetry.sdk.trace.data.EventData>()
                    override fun getLinks() = emptyList<io.opentelemetry.sdk.trace.data.LinkData>()
                }
            })
            override fun flush() = delegate.flush()
            override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
        }
        context.install(OpenTelemetry) {
            setServiceInfo("kinuvo", "unknown")
            setVerbose(false)
            setShutdownOnAgentClose(true)
            addResourceAttributes(mapOf("kinuvo.agent.id" to agentId, "kinuvo.session.id" to sessionId,
                "kinuvo.run.id" to runId, "kinuvo.request.kind" to kind.name))
            addSpanProcessor {
                val processor = batchSpanProcessor(privateExporter.toOtelKotlinSpanExporter())
                object : SpanProcessor by processor {
                    // OTel Kotlin 0.3.0 cancels the batch worker at shutdown without draining it.
                    override suspend fun shutdown(): OperationResultCode = withContext(NonCancellable) {
                        try { processor.forceFlush() } finally { processor.shutdown() }
                    }
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try { exporter?.shutdown() } finally { sink?.close() }
    }
}

private val traceAttributes = setOf(
    "gen_ai.operation.name", "gen_ai.provider.name", "gen_ai.agent.id", "gen_ai.conversation.id",
    "gen_ai.request.model", "gen_ai.response.model", "gen_ai.response.finish_reasons",
    "gen_ai.usage.input_tokens", "gen_ai.usage.output_tokens", "gen_ai.output.type",
    "gen_ai.tool.name", "gen_ai.tool.type", "gen_ai.tool.call.id", "koog.tool.call.status", "error.type",
    "koog.event.id", "koog.node.id",
)
