package kinuvo.harness.reminders

import kinuvo.harness.core.tasks.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneId

class RemindersCli(private val command: String = executable(), private val hostApp: String? = launchingApp()) : TaskProvider {
    override val id = "reminders"

    override suspend fun authorize() {
        val result = call("authorize").jsonObject
        check(result["authorized"]?.jsonPrimitive?.boolean == true) {
            accessError(result["status"]?.jsonPrimitive?.content ?: "denied")
        }
    }

    override suspend fun lists() = call("list").jsonArray.map {
        val list = it.jsonObject
        TaskList(list.text("id"), list.text("title"))
    }

    override suspend fun tasks(listId: String, includeDone: Boolean) =
        call("show", if (includeDone) "all" else "open", "--list-id", listId).jsonArray.map { task(it.jsonObject) }

    override suspend fun task(id: String): ProviderTask? =
        call("show", "all").jsonArray.firstOrNull { it.jsonObject.text("id") == id }?.jsonObject?.let(::task)

    override suspend fun create(listId: String, title: String, notes: String, due: LocalDate?): ProviderTask =
        task(call(*(listOf("add", "--title", title, "--list-id", listId, "--notes", notes) +
            due?.let { listOf("--due", it.toString()) }.orEmpty()).toTypedArray()).jsonObject)

    override suspend fun update(taskId: String, change: TaskChange): ProviderTask {
        val args = mutableListOf("edit", taskId)
        change.title?.let { args += listOf("--title", it) }
        change.notes?.let { args += listOf("--notes", it) }
        if (change.clearDue) args += "--clear-due" else change.due?.let { args += listOf("--due", it.toString()) }
        change.done?.let { args += if (it) "--complete" else "--incomplete" }
        return task(call(*args.toTypedArray()).jsonObject)
    }

    private fun task(item: JsonObject) = ProviderTask(
        item.text("id"), item.text("listID"), item.text("title"), item["notes"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        item["dueDate"]?.jsonPrimitive?.contentOrNull?.let {
            LocalDate.parse(OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate().toString())
        }, item["isCompleted"]?.jsonPrimitive?.booleanOrNull ?: false,
    )

    private suspend fun call(vararg args: String): JsonElement = withContext(Dispatchers.IO) {
        withTimeout(120_000) {
            coroutineScope {
                val process = try { ProcessBuilder(listOf(command) + args + "--json").start() } catch (e: IOException) {
                    throw IllegalStateException("Install Apple Reminders support on the Server: brew install steipete/tap/remindctl; then run remindctl authorize", e)
                }
                try {
                    process.outputStream.close()
                    val out = async { runInterruptible { process.inputStream.bufferedReader().use { it.readText() } } }
                    val err = async { runInterruptible { process.errorStream.bufferedReader().use { it.readText() } } }
                    val code = runInterruptible { process.waitFor() }
                    val stdout = out.await()
                    val stderr = err.await()
                    val response = runCatching { Json.parseToJsonElement(stdout) }.getOrNull()
                    val status = (response as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull
                    val error = stderr.trim().ifBlank { stdout.trim().ifBlank { "remindctl failed ($code)" } }
                    check(code == 0) {
                        if (status in listOf("denied", "restricted", "write-only", "not-determined") || error.contains("Reminders access denied", ignoreCase = true)) accessError(status ?: "denied") else error
                    }
                    requireNotNull(response) { "remindctl returned invalid JSON" }
                } finally {
                    if (process.isAlive) {
                        process.descendants().forEach { it.destroyForcibly() }
                        process.destroyForcibly()
                    }
                }
            }
        }
    }

    private fun accessError(status: String): String {
        val app = hostApp ?: "the app that launched the Server"
        return "Reminders access is $status for $app. Authorizing remindctl in another terminal does not grant this Server access. " +
            "Enable $app in System Settings → Privacy & Security → Reminders, then click Request access / Retry. " +
            "If the app isn’t listed, launch the Server from the terminal where remindctl is already authorized."
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    companion object {
        private fun launchingApp(): String? = runCatching {
            var process: ProcessHandle? = ProcessHandle.current()
            while (process != null) {
                val path = process.info().command().orElse("")
                if (".app/" in path) return@runCatching path.substringBeforeLast(".app/").substringAfterLast('/')
                process = process.parent().orElse(null)
            }
            null
        }.getOrNull()

        private fun executable() = listOf("/opt/homebrew/bin/remindctl", "/usr/local/bin/remindctl")
            .firstOrNull { File(it).canExecute() } ?: "remindctl"
    }
}
