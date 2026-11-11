package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.session.*
import kinuvo.app.navigation.*

import androidx.lifecycle.ViewModelStore
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.protocol.agent.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.model.*
import kinuvo.protocol.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import java.util.concurrent.Executors
import kotlin.test.*

/** An external Agent's pending requests reach the Session screen, from a reopened Session and as they arrive. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalPromptsTest {
    private val wren = AgentInfo(AgentId("wren"), "Wren", "external", executionKind = ExecutionKind.Acp, connectionId = "codex")
    private val urlApproval = PendingInteraction.HarnessApproval("open", "Open a page", "Reconnect Slack", listOf(ExternalApprovalOption("accept", "Allow", "allow_once")))

    @Test fun reopenedSessionAndMultipleRequestsUseOnlySessionUpdates() = withServer(LiveState(executionPhase = ExecutionPhase.Running, pending = listOf(urlApproval))) { owner, script ->
        val vm = SessionViewModel(requireNotNull(owner.client), owner.sessions, Route.Chat(AgentId("wren"), SessionId("s1")))
        ViewModelStore().run {
            put("chat", vm)
            try {
                vm.state.first { !it.resolving }
                vm.shown()
                assertIs<PendingInteraction.HarnessApproval>(vm.state.first { it.pending.isNotEmpty() }.pending.single())
                val request = PendingInteraction.Questions("q", ToolCallId("t"), "Details", listOf(Question("when", "When?", QuestionKind.Text, default = listOf("Tomorrow"))))
                script.event(Api.Update, SessionUpdate(AgentId("wren"), SessionId("s1"), SessionChange.Event(AgentEvent.InteractionRequested(request))))
                assertEquals(listOf("open", "q"), vm.state.first { it.pending.size == 2 }.pending.map { it.id })
                script.acceptAnswer = false
                vm.answerExternal(urlApproval, approvalAnswer("accept"))
                assertTrue(vm.state.first { it.errors.isNotEmpty() }.errors.last().contains("Already answered"))
                assertEquals(2, vm.state.value.pending.size)
                script.event(Api.Update, SessionUpdate(AgentId("wren"), SessionId("s1"), SessionChange.Event(AgentEvent.InteractionResolved("open"))))
                assertEquals("q", vm.state.first { it.pending.size == 1 }.pending.single().id)
                assertEquals(0, script.snapshotReads)
            } finally { clearAndJoin() }
        }
    }

    @Test fun resumeAndCloseWaitForSessionUpdates() = withServer(LiveState(executionPhase = ExecutionPhase.Lost)) { owner, script ->
        val vm = SessionViewModel(requireNotNull(owner.client), owner.sessions, Route.Chat(AgentId("wren"), SessionId("s1")))
        ViewModelStore().run {
            put("chat", vm)
            try {
                vm.state.first { !it.resolving && it.capabilities != null }
                vm.shown()
                vm.state.first { it.phase == ExecutionPhase.Lost }
                vm.resumeExternal()
                vm.state.first { it.phase == ExecutionPhase.Idle && !it.resuming }
                vm.closeExternal()
                vm.state.first { it.phase == ExecutionPhase.Lost }
                assertEquals(listOf(Api.ResumeExecution.name, Api.CloseExecution.name), script.actions)
                assertEquals("s1", vm.state.value.route.sessionId?.value)
                assertEquals(0, script.snapshotReads)
            } finally { clearAndJoin() }
        }
    }

    @Test fun sendingToLostSessionResumesOnceBeforeSendingOnlyTheNewMessage() = withServer(LiveState(executionPhase = ExecutionPhase.Lost)) { owner, script ->
        val vm = SessionViewModel(requireNotNull(owner.client), owner.sessions, Route.Chat(AgentId("wren"), SessionId("s1")))
        ViewModelStore().run {
            put("chat", vm)
            try {
                vm.state.first { !it.resolving && it.capabilities != null }
                vm.shown()
                vm.state.first { it.phase == ExecutionPhase.Lost }
                vm.send("Continue here")
                vm.send("Continue here")
                vm.state.first { !it.sending && script.sends.isNotEmpty() }
                assertEquals(listOf(Api.ResumeExecution.name, Api.Send.name), script.dispatches)
                assertEquals("Continue here", script.sends.single().text)
                assertEquals("s1", script.sends.single().sessionId?.value)
                assertEquals(0, script.snapshotReads)
            } finally { clearAndJoin() }
        }
    }

    @Test fun failedImplicitResumeRetainsDraftAndDoesNotSendUntilRetrySucceeds() = withServer(LiveState(executionPhase = ExecutionPhase.Lost)) { owner, script ->
        val vm = SessionViewModel(requireNotNull(owner.client), owner.sessions, Route.Chat(AgentId("wren"), SessionId("s1")))
        ViewModelStore().run {
            put("chat", vm)
            try {
                vm.state.first { !it.resolving && it.capabilities != null }
                vm.shown()
                vm.state.first { it.phase == ExecutionPhase.Lost }
                script.resumeError = "Resume failed"
                vm.send("Keep this draft")
                val failed = vm.state.first { !it.sending && it.unsent == "Keep this draft" }
                assertEquals("Resume failed", failed.errors.last())
                assertFalse(failed.resuming)
                assertTrue(script.sends.isEmpty())
                script.resumeError = null
                vm.send("Keep this draft")
                vm.state.first { !it.sending && script.sends.isNotEmpty() }
                assertEquals(listOf(Api.ResumeExecution.name, Api.ResumeExecution.name, Api.Send.name), script.dispatches)
                assertEquals("Keep this draft", script.sends.single().text)
            } finally { clearAndJoin() }
        }
    }

    @Test fun attachmentOnlyRetryKeepsSubmissionIdentityAndVoiceMetadata() = withServer(LiveState()) { owner, script ->
        val vm = SessionViewModel(requireNotNull(owner.client), owner.sessions, Route.Chat(AgentId("wren"), SessionId("s1")))
        ViewModelStore().run {
            put("chat", vm)
            try {
                vm.state.first { !it.resolving && it.capabilities != null }
                vm.addAttachments(listOf(UploadAttachmentParams("note.txt", "aGk=")))
                assertEquals(1, vm.state.value.attachments.size)
                script.failSend = true
                vm.send("", voice = true)
                vm.state.first { it.unsent == "" && !it.sending }
                script.failSend = false
                vm.send("", voice = true)
                vm.state.first { !it.sending && it.unsent == null }
                assertEquals(2, script.sends.size)
                assertEquals(script.sends.first().submissionId, script.sends.last().submissionId)
                assertNotNull(script.sends.last().submissionId)
                assertEquals(listOf("file"), script.sends.last().attachments)
                assertEquals("", script.sends.last().text)
                assertTrue(script.sends.last().voice)
            } finally { clearAndJoin() }
        }
    }

    @Test fun idleAndLostNeverAnnounceSuccessfulReply() = withServer(LiveState(executionPhase = ExecutionPhase.Running, running = true)) { owner, script ->
        owner.sessions.view(AgentId("wren"), SessionId("s1")).join()
        owner.sessions.state.first { it.sessions[SessionId("s1")]?.loaded == true }
        script.event(Api.SessionChanged, SessionInfo(SessionId("s1"), AgentId("wren"), "Test", SessionStatus.Running))
        script.event(Api.Update, SessionUpdate(AgentId("wren"), SessionId("s1"), SessionChange.Event(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Lost))))
        script.event(Api.SessionChanged, SessionInfo(SessionId("s1"), AgentId("wren"), "Test", SessionStatus.Idle))
        owner.sessions.state.first { it.sessions[SessionId("s1")]?.live?.executionPhase == ExecutionPhase.Lost }
        script.event(Api.Update, SessionUpdate(AgentId("wren"), SessionId("s1"), SessionChange.Event(AgentEvent.RunFinished(RunId("lost")))))
        script.event(Api.Update, SessionUpdate(AgentId("wren"), SessionId("s1"), SessionChange.Event(AgentEvent.InteractionRequested(urlApproval))))
        owner.sessions.state.first { it.sessions[SessionId("s1")]?.live?.pending?.isNotEmpty() == true }
        assertTrue(script.notices.none { it is Notice.RunFinished })
    }

    private fun withServer(snapshot: LiveState, block: suspend (ConnectedServer, Script) -> Unit) = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            Dispatchers.setMain(main)
            try {
                withContext(main) {
                    withTimeout(15_000) {
                        val reader = object : DeviceReader {
                            override val kinds = emptyList<String>()
                            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
                            override fun read(requestJson: String, completion: (String) -> Unit) = error("Unused")
                        }
                        val script = Script(snapshot)
                        val owner = ConnectedServer(Notifications(listOf(Notifier { script.notices += it })), DeviceAccess(MemoryPreferences(), reader))
                        val client = KinuvoClient("local:test", "test", connection = { script })
                        owner.open(client, "local:test")
                        client.connect()
                        owner.activate()
                        owner.harnesses.state.first { it.catalog != null }
                        try { block(owner, script) } finally {
                            val job = owner.scope.coroutineContext.job
                            owner.close()
                            job.join()
                        }
                    }
                }
            } finally { Dispatchers.resetMain() }
        }
    }

    private inner class Script(var snapshot: LiveState) : FrameConnection {
        private val frames = Channel<Frame>(Channel.UNLIMITED)
        override val incoming = frames.receiveAsFlow()
        val actions = mutableListOf<String>()
        val dispatches = mutableListOf<String>()
        var resumeError: String? = null
        var snapshotReads = 0
        var acceptAnswer = true
        var failSend = false
        val sends = mutableListOf<SendParams>()
        val notices = mutableListOf<Notice>()
        override suspend fun send(frame: Frame) {
            val request = frame as Frame.Request
            when (request.method) {
                Api.Connect.name -> reply(request, Api.Connect, ConnectResult("token", "test", capabilities = ServerCapabilities("test"), management = true))
                Api.Presence.name -> reply(request, Api.Presence, Unit)
                Api.Agents.name -> reply(request, Api.Agents, listOf(wren))
                Api.Models.name -> reply(request, Api.Models, ModelsCatalog())
                Api.ExecutionCatalog.name -> reply(request, Api.ExecutionCatalog, ExecutionCatalog(emptyList(), listOf(AgentRegistration(AgentId("wren"), "Wren", "codex"))))
                Api.ExecutionSnapshot.name -> { snapshotReads++; error("Snapshot path must not be used") }
                Api.UploadAttachment.name -> reply(request, Api.UploadAttachment, Attachment("file", "note.txt", "text/plain", 2))
                Api.Send.name -> {
                    dispatches += request.method
                    sends += ProtocolJson.decodeFromJsonElement(Api.Send.params, request.params)
                    if (failSend) frames.send(Frame.Response(request.id, false, error = WireError(WireErrorCode.Internal, "Uncertain submission")))
                    else reply(request, Api.Send, SendResult(SessionId("s1"), RunId("run"), ""))
                }
                Api.Respond.name -> reply(request, Api.Respond, acceptAnswer)
                Api.ResumeExecution.name -> {
                    actions += request.method
                    dispatches += request.method
                    if (resumeError != null) {
                        frames.send(Frame.Response(request.id, false, error = resumeError?.let { WireError(WireErrorCode.Internal, it) }))
                        return
                    }
                    snapshot = snapshot.copy(executionPhase = ExecutionPhase.Idle)
                    reply(request, Api.ResumeExecution, ExecutionSnapshot(SessionId("s1")))
                    event(Api.Update, SessionUpdate(AgentId("wren"), SessionId("s1"), SessionChange.Live(snapshot)))
                }
                Api.CloseExecution.name -> {
                    actions += request.method
                    snapshot = snapshot.copy(executionPhase = ExecutionPhase.Lost)
                    reply(request, Api.CloseExecution, Unit)
                    event(Api.Update, SessionUpdate(AgentId("wren"), SessionId("s1"), SessionChange.Live(snapshot)))
                }
                Api.Session.name -> reply(request, Api.Session, SessionInfo(SessionId("s1"), AgentId("wren"), "Slack"))
                Api.Capabilities.name -> reply(request, Api.Capabilities, SessionCapabilities(0, ServerCapabilities("test", features = setOf(ServerFeature.Attachments)), attachments = mapOf("text/plain" to CapabilityState()), chat = if (snapshot.executionPhase == ExecutionPhase.Lost) CapabilityState.unavailable("Execution ended", "session_lost") else CapabilityState(), execution = ExecutionCapabilities(resume = true, supportedMimeTypes = setOf("text/plain"))))
                Api.CatchUp.name -> {
                    reply(request, Api.CatchUp, Unit)
                    event(Api.Update, SessionUpdate(AgentId("wren"), SessionId("s1"), SessionChange.Window(0, emptyList(), snapshot)))
                }
                else -> frames.send(Frame.Response(request.id, false, error = WireError(WireErrorCode.Internal, "Unsupported in test: ${request.method}")))
            }
        }
        suspend fun <T> event(type: EventType<T>, payload: T) = frames.send(Frame.Event(type.name, ProtocolJson.encodeToJsonElement(type.payload, payload), 1))
        private suspend fun <P, R> reply(request: Frame.Request, method: Method<P, R>, result: R) {
            frames.send(Frame.Response(request.id, true, ProtocolJson.encodeToJsonElement(method.result, result)))
        }
        override suspend fun close() = Unit
    }
}
