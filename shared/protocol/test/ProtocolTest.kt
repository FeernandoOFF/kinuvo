package kinuvo.protocol

import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ApprovalDecision
import kinuvo.protocol.SessionEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolTest {
    @Test
    fun serverPlatformRoundTripsAndOlderServersDefaultToUnknown() {
        Platform.entries.forEach { platform ->
            val capabilities = ServerCapabilities("server", platform = platform)
            val encoded = ProtocolJson.encodeToString(ServerCapabilities.serializer(), capabilities)
            assertEquals(capabilities, ProtocolJson.decodeFromString(ServerCapabilities.serializer(), encoded))
        }
        assertEquals(Platform.Unknown, ProtocolJson.decodeFromString(ServerCapabilities.serializer(), """{"serverId":"old"}""").platform)
    }

    @Test
    fun requestMeasurementsAndContextRoundTripWithUnknownCacheFields() {
        val metrics = RequestMetrics("m1", 123, RequestKind.Reflection, "codex", "model", "high", 500, 100, 2000, 200, cachedTokens = 0)
        for (entry in listOf(SessionEntry.Context("runtime context"), SessionEntry.Measurement(metrics), SessionEntry.Outcome(report = true, summary = "Rent is due"),
            SessionEntry.Reflection("r1", 123, ReflectionResult(measurements = listOf(metrics))))) {
            assertEquals(entry, ProtocolJson.decodeFromString(SessionEntry.serializer(), ProtocolJson.encodeToString(SessionEntry.serializer(), entry)))
        }
        val empty = ProtocolJson.decodeFromString(ReflectionResult.serializer(), "{}")
        assertEquals(emptyList(), empty.measurements)
        assertEquals(null, metrics.copy(cachedTokens = null).cachedTokens)
    }

    @Test
    fun framesAndPayloadsRoundTrip() {
        val event = SessionUpdate("default", "s1", SessionChange.Event(AgentEvent.ApprovalRequested("a1", "c1", "shell", "{}", "git status")))
        val frame = Frame.Event(Api.Update.name, ProtocolJson.encodeToJsonElement(Api.Update.payload, event), 7)
        val text = ProtocolJson.encodeToString(Frame.serializer(), frame)
        val decoded = ProtocolJson.decodeFromString(Frame.serializer(), text) as Frame.Event
        assertEquals(event, ProtocolJson.decodeFromJsonElement(Api.Update.payload, decoded.payload))
        assertEquals(7, decoded.seq)

        val params = ApprovalParams("c1", ApprovalDecision.Deny("no"))
        assertEquals(params, ProtocolJson.decodeFromString(ApprovalParams.serializer(), ProtocolJson.encodeToString(ApprovalParams.serializer(), params)))
        val entry = SessionUpdate("default", "s1", SessionChange.Entry(3, SessionEntry.Activation("tdd", "x")))
        assertEquals(entry, ProtocolJson.decodeFromJsonElement(Api.Update.payload, ProtocolJson.encodeToJsonElement(Api.Update.payload, entry)))
        val usage = Usage("codex", listOf(Usage.Window("primary", 42.0, 300)))
        assertEquals(usage, ProtocolJson.decodeFromJsonElement(Api.Usage.payload, ProtocolJson.encodeToJsonElement(Api.Usage.payload, usage)))
        val routines = RoutinesChanged("default", listOf(
            Routine("r1", "default", "Brief", "Summarize", Trigger.Cron("0 9 * * *"), "Europe/Dublin", activeHours = ActiveHours("08:00", "22:00")),
            Routine("r2", "default", "Call", "Remind", Trigger.Once(1_790_000_000_000), "UTC", sessionId = "s1", lastStatus = RunStatus.Error, lastError = "x"),
        ))
        assertEquals(routines, ProtocolJson.decodeFromJsonElement(Api.RoutinesChanged.payload, ProtocolJson.encodeToJsonElement(Api.RoutinesChanged.payload, routines)))
    }

    @Test
    fun reflectionPromptSettingsRoundTripAndUnsetPromptsUseTheDefault() {
        val settings = KnowledgeSettings(reflectionPrompt = "Remember lasting preferences.")
        assertEquals(settings, ProtocolJson.decodeFromString(KnowledgeSettings.serializer(), ProtocolJson.encodeToString(KnowledgeSettings.serializer(), settings)))
        assertEquals(null, ProtocolJson.decodeFromString(KnowledgeSettings.serializer(), "{}").reflectionPrompt)
    }

    @Test
    fun openFormMakesTheSessionWait() {
        val asked = AgentEvent.QuestionsAsked("q1", "c1", null, listOf(Question("a", "A")))
        val live = LiveState().on(AgentEvent.RunStarted("r1")).on(asked)
        assertEquals(SessionStatus.Waiting, live.status)
        assertEquals(SessionStatus.Running, live.on(AgentEvent.QuestionsAnswered("c1")).status)
        assertEquals(null, live.on(AgentEvent.RunFinished("r1")).questions)
    }

    @Test
    fun liveStateFoldsDeltasAndClearsAsEntriesLand() {
        val approval = AgentEvent.ApprovalRequested("a1", "c1", "shell", "{}", null)
        val live = listOf(
            AgentEvent.RunStarted("r1"),
            AgentEvent.ThinkingContent("m1", "hm"),
            AgentEvent.TextMessageContent("m1", "he"),
            AgentEvent.TextMessageContent("m1", "llo"),
            approval,
        ).fold(LiveState()) { s, e -> s.on(e) }
        assertEquals(LiveState(running = true, messageId = "m1", text = "hello", reasoning = "hm", approval = approval), live)
        assertEquals(LiveState(running = true, messageId = "m1", approval = approval), live.on(SessionEntry.Reasoning("hm")).on(SessionEntry.AssistantMessage("hello")))
        assertEquals(LiveState(messageId = "m1", text = "hello", reasoning = "hm"), live.on(AgentEvent.RunFinished("r1")))
    }

    @Test
    fun reflectionRunsIndependentlyAndRoundTrips() {
        val started = AgentEvent.ReflectionStarted("reflection-1")
        assertEquals(started, ProtocolJson.decodeFromString(AgentEvent.serializer(), ProtocolJson.encodeToString(AgentEvent.serializer(), started)))
        val live = LiveState().on(started)
        assertEquals(SessionStatus.Idle, live.status)
        val chatting = live.on(AgentEvent.RunStarted("r1"))
        assertEquals("reflection-1", chatting.reflectionId)
        assertEquals("reflection-1", chatting.on(AgentEvent.RunFinished("r1")).reflectionId)
        val entry = SessionEntry.Reflection("reflection-1", 123, ReflectionResult(knowledge = 1, memory = 2, userNotes = 3))
        assertEquals(entry, ProtocolJson.decodeFromString(SessionEntry.serializer(), ProtocolJson.encodeToString(SessionEntry.serializer(), entry)))
        assertEquals(6, entry.result.total)
        assertEquals(null, chatting.on(entry).reflectionId)
        assertEquals(SessionStatus.Running, chatting.on(entry).status)
    }

    @Test
    fun pairLinksRoundTrip() {
        assertEquals(PairLink("http://192.168.1.5:8787", "123456"), Links.parsePair(Links.pair("http://192.168.1.5:8787", "123456")))
        assertEquals(PairLink("https://kinuvo.ts.net", "123456"), Links.parsePair(Links.webPair("https://kinuvo.ts.net/", "123456")))
        assertEquals(PairLink("http://10.0.0.2:8787", "654321"), Links.parsePair("http://10.0.0.2:8787/index.html#pair=654321"))
        assertEquals(null, Links.parsePair("kinuvo://pair?server=x&code=12;rm"))
        assertEquals(null, Links.parsePair("http://10.0.0.2:8787/"))
        assertEquals(null, Links.parsePair("kinuvo://chat/kai"))
    }

    @Test
    fun chatLinksRoundTrip() {
        assertEquals("kai" to "20261005-1200-ab12", Links.parseChat(Links.chat("kai", "20261005-1200-ab12")))
        assertEquals("kai" to null, Links.parseChat("kinuvo://chat/kai"))
        assertEquals(null, Links.parseChat("kinuvo://chat/../etc"))
        assertEquals(null, Links.parseChat("https://chat/kai"))
    }
}
