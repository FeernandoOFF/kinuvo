package kinuvo.harness.litert

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import kinuvo.harness.core.AgentConfig
import kinuvo.models.ModelFiles
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Runs a real model; set KINUVO_TEST_LITERT_MODEL to a downloaded Qwen3-0.6B `.litertlm` file. */
class LiteRtModelTest {
    @Test fun callsAToolThenAnswersWithItsResult() = runBlocking {
        val file = System.getenv("KINUVO_TEST_LITERT_MODEL")?.let(::File) ?: return@runBlocking
        val home = Files.createTempDirectory("kinuvo-litert").toFile()
        try {
            val model = localModels.single { it.id == "qwen3-0.6b" }
            val folder = File(home, "local-models/${model.id}").apply { mkdirs() }
            Files.createLink(File(folder, model.file).toPath(), file.toPath())
            File(folder, ".verified").writeText(ModelFiles(File(home, "local-models")).receipt(model.download, folder))
            val provider = LiteRtProvider(home)
            assertTrue(provider.availability(model.id).actionable)
            val agent = provider.model(AgentConfig(provider = "local", model = model.id))
            val weather = ToolDescriptor("get_weather", "Gets the current weather for a city",
                requiredParameters = listOf(ToolParameterDescriptor("city", "City name", ToolParameterType.String)))
            val asked = prompt("weather") { system("You are a helpful assistant. Use tools when useful."); user("What's the weather in Monterrey right now?") }
            val call = agent.executor.execute(asked, agent.model, listOf(weather)).parts.filterIsInstance<MessagePart.Tool.Call>().single()
            assertEquals("get_weather", call.tool)
            val answered = asked.withMessages { it + Message.Assistant(listOf(call), ai.koog.prompt.message.ResponseMetaInfo.Empty) +
                Message.User(listOf(MessagePart.Tool.Result(call.id, call.tool, """{"celsius":31,"sky":"sunny"}""")), RequestMetaInfo.Empty) }
            val reply = agent.executor.execute(answered, agent.model, listOf(weather)).textContent()
            println("LiteRT reply: $reply")
            assertContains(reply, "31")
            provider.close()
        } finally { home.deleteRecursively() }
    }
}
