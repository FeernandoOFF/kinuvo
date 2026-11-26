package kinuvo.server.jvm

import java.io.File
import kinuvo.harness.AgentDoc
import kinuvo.harness.ExecModes
import kinuvo.protocol.Api
import kinuvo.protocol.Frame
import kinuvo.protocol.WireError
import kinuvo.protocol.WireErrorCode
import kinuvo.protocol.board.TaskStatus
import kinuvo.protocol.model.ModelInfo
import kinuvo.protocol.routine.Trigger
import kinuvo.protocol.service.ServiceSettings
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.tool.ToolNames
import kinuvo.providers.koog.compatibleProviders
import kinuvo.server.Secrets
import kinuvo.server.Call
import kinuvo.server.Routes
import kinuvo.server.knowledge.KnowledgeConfig
import kinuvo.server.plugin.ModelProviderConfig
import kinuvo.server.plugin.ProviderKeys
import kinuvo.server.providers.ModelProviders
import kinuvo.server.store.AgentFiles
import kinuvo.server.store.ServerConfig
import kinuvo.server.store.updateConfig
import kotlinx.io.files.Path
import kotlinx.coroutines.runBlocking

internal object ReviewServer {
    val tools = setOf(ToolNames.MEMORY, ToolNames.TASKS, ToolNames.KNOWLEDGE, ToolNames.WEB_SEARCH, ToolNames.ASK_USER)
    const val MODEL = "openai/gpt-4.1-mini"
    private val allowedMethods = listOf(Api.Agents, Api.Agent, Api.Sessions, Api.Session, Api.Subscribe, Api.Unsubscribe, Api.History,
        Api.CreateSession, Api.UpdateSession, Api.Send, Api.CancelQueued, Api.Stop, Api.Respond, Api.Capabilities,
        Api.UploadAttachment, Api.Attachment, Api.Routines, Api.KnowledgeSearch, Api.KnowledgeNote, Api.KnowledgeChanges,
        Api.UndoKnowledgeChange, Api.TaskLists, Api.Tasks, Api.CreateTask, Api.UpdateTask, Api.TasksSettings,
        Api.KnowledgeSettings, Api.Service, Api.Providers, Api.Models, Api.Presence, Api.SurfaceCatalogs,
        Api.Skills, Api.McpServers, Api.Workflows, Api.Workflow, Api.Artifacts, Api.Artifact, Api.RotateToken,
        Api.Computers, Api.Screens, Api.ScreenWatch, Api.ScreenUnwatch, Api.ScreenAck).map { it.name }.toSet()

    fun routes(delegate: Routes): Routes = object : Routes by delegate {
        override suspend fun dispatch(call: Call, request: Frame.Request): Frame.Response =
            if (request.method in allowedMethods) delegate.dispatch(call, request)
            else Frame.Response(request.id, false, error = WireError(WireErrorCode.Forbidden, "This action is disabled on the review Server"))
    }

    fun prepare(home: Path) {
        val directory = File(home.toString())
        val marker = File(directory, ".review-server")
        require(marker.isFile || !directory.exists() || directory.list().isNullOrEmpty()) { "Review mode requires an empty dedicated home" }
        directory.mkdirs()
        marker.writeText("Kinuvo review Server\n")
        AgentFiles(home).updateConfig {
            ServerConfig(timezone = "UTC", tracing = false, liveVoiceEnabled = false,
                knowledge = KnowledgeConfig(reflection = false), notifications = ServiceSettings(push = false),
                search = ServiceSettings("review-search", mapOf("url" to "http://review-search:8080")),
                providers = mapOf("review" to ModelProviderConfig(name = "Review model", baseUrl = "https://openrouter.ai/api/v1/",
                    apiKeyEnv = "KINUVO_REVIEW_API_KEY", models = listOf(ModelInfo(MODEL, tools = true, context = 16384, input = listOf("text"), output = listOf("text"))))))
        }
    }

    fun providers(files: AgentFiles, secrets: Secrets) = compatibleProviders(ProviderKeys(files, secrets), files, null).let { endpoints ->
        ModelProviders(listOf(endpoints)) { endpoints.modelSources }
    }

    fun seed(runtime: JvmAgentRuntime) = runBlocking {
        val marker = File(runtime.files.home.toString(), ".review-seeded")
        if (marker.exists()) return@runBlocking
        val created = runtime.store.agents().firstOrNull { it.id.value == "default" } ?: runtime.store.createAgent("default")
        val agent = runtime.store.saveConfig(created, created.config.copy(provider = "review", model = MODEL, reasoning = "none",
            exec = ExecModes.NONE, tools = listOf(ToolNames.MEMORY, ToolNames.TASKS, ToolNames.KNOWLEDGE, ToolNames.WEB_FETCH, ToolNames.ASK_USER),
            workspace = emptyList(), skillDirs = emptyList(), compactAtTokens = 12_000))
        runtime.docs.write(agent.id, AgentDoc.Identity, "# Atlas\nA helpful assistant for this fictional demonstration.\n")
        runtime.docs.write(agent.id, AgentDoc.Soul, "Help Maya Chen plan her Lisbon trip and organize everyday work. All seeded people and plans are fictional. Explain that this shared review demo resets nightly at 03:00 UTC. Never ask for real credentials or sensitive personal information. You can search the web, remember preferences, manage local Tasks and Knowledge, and answer questions. The user's Tasks are all Tasks on the Board, so list them without the mine filter. Email, messaging, shell commands and other outside-world actions are disabled in this demo; say so instead of offering them.\n")
        runtime.docs.write(agent.id, AgentDoc.UserNotes, "- Maya Chen is a fictional product designer.\n- She likes walkable neighborhoods, independent cafes and vegetarian food.\n")
        runtime.docs.write(agent.id, AgentDoc.Memory, "- Maya is planning a four-day Lisbon trip with a day in Sintra.\n- Keep the trip relaxed, with no more than two major sights each day.\n- Travel budget: EUR 600 excluding flights.\n")
        val itinerary = runtime.newSession(agent)
        runtime.store.appendAll(agent.id, itinerary.id, listOf(SessionEntry.Details("A long weekend in Lisbon"),
            SessionEntry.UserMessage("Help me plan four days in Lisbon, including a day trip to Sintra."),
            SessionEntry.AssistantMessage("Let's make it relaxed: explore Alfama and Baixa on day one, Belém on day two, Sintra on day three, and leave the last morning for a cafe and a riverside walk. I've kept your EUR 600 budget in mind.")))
        val packing = runtime.newSession(agent)
        runtime.store.appendAll(agent.id, packing.id, listOf(SessionEntry.Details("Lisbon packing list"),
            SessionEntry.UserMessage("What should I pack for Lisbon?"),
            SessionEntry.AssistantMessage("Comfortable walking shoes, a light rain jacket, a reusable bottle, sunglasses and a compact day bag. The hills and cobbled streets make good shoes especially useful.")))
        val work = runtime.newSession(agent)
        runtime.store.appendAll(agent.id, work.id, listOf(SessionEntry.Details("Design week priorities"),
            SessionEntry.UserMessage("Help me organize the design work before my trip."),
            SessionEntry.AssistantMessage("Finish the onboarding prototype, collect feedback from the team, and write a short handover. Your Board has those tasks so you can track progress.")))
        runtime.tasks.create("Book the Sintra train", notes = "Choose a morning departure from Rossio; fictional travel plan.")
        runtime.tasks.create("Find vegetarian restaurants in Lisbon", notes = "Save three options near Alfama and Baixa.")
        val prototype = runtime.tasks.create("Finish the onboarding prototype", notes = "Prepare the welcome and pairing screens for feedback.")
        runtime.tasks.update(prototype.id, status = TaskStatus.InProgress)
        val handover = runtime.tasks.create("Review the design handover", notes = "Waiting for fictional teammate Alex to review the draft.")
        runtime.tasks.update(handover.id, status = TaskStatus.Waiting)
        val hotel = runtime.tasks.create("Compare Lisbon neighborhoods", notes = "Baixa is central; Alfama has character and steep streets.")
        runtime.tasks.update(hotel.id, status = TaskStatus.Done)
        runtime.knowledge.create("person", "Maya Chen", body = "A fictional product designer who enjoys slow travel, photography and vegetarian food.")
        runtime.knowledge.create("note", "Lisbon ideas", body = "Explore Alfama early, walk along the Tagus, visit Belém, and take the train to Sintra. Restaurant suggestions should be checked with a fresh web search.")
        runtime.knowledge.create("project", "Lisbon long weekend", body = "Four relaxed days in Lisbon with a day trip to Sintra. Budget: EUR 600 excluding flights. Travel with a small day bag. See [[Maya Chen]] and [[Lisbon ideas]].")
        runtime.routines.create(agent, "Morning priorities", "Summarize my open Tasks and suggest three priorities.", Trigger.Cron("0 8 * * *")) { it.copy(enabled = false) }
        runtime.routines.create(agent, "Lisbon planning check-in", "Review the Lisbon plan and identify one preparation task.", Trigger.Cron("0 18 * * 4")) { it.copy(enabled = false) }
        marker.writeText("1\n")
    }
}
