package kinuvo.app

import kinuvo.app.agent.*
import kinuvo.app.session.*

import kinuvo.protocol.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarnessTest {
    private val claude = HarnessConnectionStatus(HarnessConnection("claude", "Claude Code", ExecutionConnection.Acp(listOf("claude-code-acp"))), ExecutionReadiness(true, true))
    private val acpAgent = AgentInfo("reviewer", "Reviewer", "external", executionKind = ExecutionKind.Acp, connectionId = "claude", host = "Mac mini")

    @Test fun newAgentStepsBranchByWhereItRuns() {
        val s = OnboardingState(step = HarnessStep, steps = agentSteps(HarnessChoice.Kinuvo))
        assertEquals(listOf(HarnessStep, 4, 5), s.steps)
        assertEquals(4, s.moved(1).step)
        val acp = s.choose(HarnessChoice.Acp("claude"))
        assertEquals(listOf(HarnessStep, 4), acp.steps)
        assertEquals(4, acp.moved(1).moved(1).step)
        assertEquals(listOf(HarnessStep, PickStep), s.choose(HarnessChoice.Remote("studio")).steps)
        assertEquals(HarnessStep, acp.moved(1).moved(-1).step)
        // First-run onboarding never gets the step.
        assertTrue(HarnessStep !in OnboardingSteps)
    }

    @Test fun acpCommandIsLiteralArgvWithoutShellSplitting() {
        val form = AcpForm(name = "Codex", command = "codex-acp", args = listOf("--profile", "my work", ""))
        assertEquals(listOf("codex-acp", "--profile", "my work"), form.argv)
        assertEquals(ExecutionConnection.Acp(listOf("codex-acp", "--profile", "my work")), form.connection(listOf(claude)).connection)
        assertEquals(listOf("Put each argument on its own row under Arguments"), form.copy(command = "codex --acp").problems(emptyList()))
        assertEquals(listOf("Use a command on the Server's PATH or an absolute path"), form.copy(command = "./bin/acp").problems(emptyList()))
        assertTrue(form.copy(command = "/opt/homebrew/bin/codex-acp").problems(emptyList()).isEmpty())
    }

    @Test fun acpFormNeedsUniqueNameAndAUsableCheckOfWhatIsTyped() {
        val form = AcpForm(name = "claude code", command = "claude-code-acp")
        assertEquals(listOf("Another harness is called claude code"), form.problems(listOf(claude)))
        assertTrue(AcpForm.of(claude).problems(listOf(claude)).isEmpty())
        val ok = AcpForm(name = "Codex", command = "codex-acp")
        assertFalse(ok.saveable(listOf(claude)))
        val signIn = ok.copy(probe = ExecutionProbe(ExecutionReadiness(true, false, "Sign in", "needs_sign_in")), probed = ok.acp)
        assertTrue(signIn.saveable(listOf(claude)))
        assertFalse(signIn.copy(args = listOf("--x")).saveable(listOf(claude)))
        assertFalse(signIn.copy(probe = ExecutionProbe(ExecutionReadiness(true, false, "No such file", "not_found"))).saveable(listOf(claude)))
        assertFalse(signIn.copy(probe = ExecutionProbe(ExecutionReadiness(true, false, "v1", "incompatible"))).saveable(listOf(claude)))
        assertEquals("codex", ok.connection(listOf(claude)).id)
        assertEquals("claude-2", AcpForm(name = "Claude").connection(listOf(claude)).id)
    }

    @Test fun environmentIsPartOfTheCheckedCommandAndSurvivesEditing() {
        val form = AcpForm(name = "Codex work", command = "/usr/bin/env", args = listOf("codex-acp"),
            environment = listOf("CODEX_HOME" to "/Users/test/.codex-work", "API_KEY" to "literal value"))
        assertTrue(form.problems(emptyList()).isEmpty())
        val checked = form.copy(probe = ExecutionProbe(ExecutionReadiness(true, true)), probed = form.acp)
        assertTrue(checked.saveable(emptyList()))
        assertFalse(checked.copy(environment = listOf("CODEX_HOME" to "/other")).checked)
        val restored = AcpForm.of(HarnessConnectionStatus(form.connection(emptyList()), ExecutionReadiness(true, true)))
        assertEquals(form.acp, restored.acp)
        assertTrue(envProblems(listOf("CODEX_HOME" to "~/.codex")).isNotEmpty())
        assertTrue(envProblems(listOf("CODEX_HOME" to "")).isNotEmpty())
        assertTrue(envProblems(listOf("KEY" to "a", "KEY" to "b")).isNotEmpty())
        assertTrue(envProblems(listOf("BAD=KEY" to "value")).isNotEmpty())
        assertFalse(envProblems(listOf("" to "secret-value")).joinToString().contains("secret-value"))
    }

    @Test fun endedSessionsKeepTranscriptAndOfferResumeAccordingToSupport() {
        assertNull(SessionEnd.of(ExecutionPhase.Running, null, true))
        assertEquals(SessionEnd(false, true), SessionEnd.of(ExecutionPhase.Lost, null, true))
        assertEquals(SessionEnd(true, false), SessionEnd.of(null, CapabilityState.unavailable("Closed", "session_closed"), false))
        assertTrue(SessionEnd(false, false).blocked.contains("new Session"))
        assertTrue(SessionEnd(false, null).message("Codex", "Studio").contains("if it can"))
    }

    @Test fun readinessNamesTheMachineAndOffersItsFix() {
        val missing = readiness(CapabilityState.unavailable("gone", "not_found"), "Codex", "Mac mini", command = "codex-acp")
        assertEquals("Not found", missing.pill)
        assertEquals("No \"codex-acp\" on Mac mini. Install it there, then check again.", missing.message)
        assertEquals(listOf(ReadinessAction.CheckAgain, ReadinessAction.Edit), missing.actions)
        assertTrue("Mac mini" in readiness(CapabilityState.unavailable("x", "needs_sign_in"), "Codex", "Mac mini").message!!)
        assertEquals("Studio is offline. Ada can't reply until it's back.", readiness(CapabilityState.unavailable("x", "host_offline"), "Kinuvo", "Studio", "Ada").message)
        val lost = readiness(CapabilityState.unavailable("Start a new Session to chat.", "session_lost"), "Codex", "Mac mini", "Ada")
        assertEquals("Session ended", lost.pill)
        assertEquals("Start a new Session to chat.", lost.message)
        assertFalse("offline" in lost.message!!)
        assertTrue(lost.actions.isEmpty())
        assertEquals("Strange failure", readiness(CapabilityState.unavailable("Strange failure", "weird"), "Codex", "Mac mini").message)
        assertTrue(readiness(ExecutionReadiness(true, true).state(), "Codex", "Mac mini").ready)
        assertEquals("Not checked", readiness(ExecutionReadiness(true, false, "Not connected").state(), "Codex", "Mac mini").pill)
        assertEquals("Not on this Server", readiness(ExecutionReadiness(true, false, "No ACP", "unsupported").state(), "Codex", "Mac mini").pill)
    }

    @Test fun labelsNameTheMachineThatRunsTheAgent() {
        val native = AgentInfo("main", "Kinuvo", "none")
        assertEquals("Kinuvo on Mac mini", runsOn(native, null, "Mac mini"))
        assertNull(railSubtitle(native, null, "Mac mini"))
        assertEquals("Claude Code on Mac mini", runsOn(acpAgent, listOf(claude), "Phone"))
        assertEquals("Claude Code · Mac mini", railSubtitle(acpAgent, listOf(claude), "Phone"))
        assertEquals("ACP agent on Mac mini", runsOn(acpAgent, null, "Phone"))
        assertEquals("Researcher (Studio)", remoteName("Researcher", "Studio", listOf(AgentInfo("r", "researcher", "none"))))
        assertEquals("Ada", remoteName("Ada", "Studio", listOf(AgentInfo("r", "researcher", "none"))))
    }

    @Test fun externalDraftSavesItsRegistrationNotNativeSettings() {
        val details = AgentDetails(acpAgent, AgentSettings("", "", "", "external"), "", "", managed = true, cwd = "/Users/me/app")
        val draft = AgentDraft.of(details)
        assertEquals(ExecutionKind.Acp, draft.kind)
        assertTrue(draft.problems("reviewer", listOf(acpAgent)).isEmpty())
        assertNull(draft.registration(details))
        val moved = draft.copy(cwd = "/Users/me/other", name = "Critic")
        assertEquals(UpdateAgentParams("reviewer"), moved.changes(details))
        assertEquals(AgentRegistration("reviewer", "Critic", "claude", cwd = "/Users/me/other"), moved.registration(details))
        assertEquals(listOf("Use an absolute path, e.g. /Users/you/project"), draft.copy(cwd = "app").problems("reviewer", listOf(acpAgent)))
        val native = AgentDetails(AgentInfo("main", "Kinuvo", "none"), AgentSettings("m", "medium", "default", "none"), "", "")
        assertNull(AgentDraft.of(native).copy(name = "Other").registration(native))
    }

    @Test fun approvalOptionsKeepHarnessLabelsAndAnswerWithTheirIds() {
        val request = ExecutionRequest("p1", ExecutionRequestKind.Approval, buildJsonObject { put("title", "Run ./kotlin build") },
            listOf(ExternalApprovalOption("once", "Allow once", "allow_once"), ExternalApprovalOption("always", "Always allow here", "allow_always"), ExternalApprovalOption("no", "Reject", "reject_once")))
        val prompt = assertIs<ExternalPrompt.Approval>(request.prompt())
        assertEquals("Run ./kotlin build", prompt.title)
        assertEquals(listOf(ApprovalOptionStyle.Primary, ApprovalOptionStyle.Outlined, ApprovalOptionStyle.Destructive), prompt.options.map { approvalOptionStyle(it.kind) })
        assertEquals("""{"outcome":{"outcome":"selected","optionId":"always"}}""", approvalAnswer("always").toString())
        assertEquals("""{"outcome":{"outcome":"cancelled"}}""", approvalAnswer(null).toString())
    }

    @Test fun linkedApprovalArgumentsHideUrlCredentials() {
        val request = ExecutionRequest("a", ExecutionRequestKind.Approval, buildJsonObject {
            put("toolCallName", "connect")
            put("args", "Open https://user:password@example.com/auth?token=secret")
        })
        val prompt = assertIs<ExternalPrompt.Approval>(request.prompt())
        assertEquals("Open example.com", prompt.description)
    }

    @Test fun acpApprovalShowsItsSubjectWithoutTheAddress() {
        val request = ExecutionRequest("p2", ExecutionRequestKind.Approval, Json.parseToJsonElement("""{"title":"MCP server requests to open a URL","subject":{"type":"tool_call","toolCall":{
            "toolCallId":"t","kind":"fetch","status":"pending","content":[{"type":"content","content":{"type":"text","text":"Reconnect Slack at https://chatgpt.com/c?token=s3cret"}}],
            "rawInput":{"serverName":"codex_apps","url":"https://me:pw@chatgpt.com:443/connect?token=s3cret#x"}}}}"""), emptyList())
        val prompt = assertIs<ExternalPrompt.Approval>(request.prompt())
        assertEquals("Reconnect Slack at chatgpt.com", prompt.description)
        assertEquals("chatgpt.com" to "codex_apps", prompt.site to prompt.server)
        assertEquals("Opens chatgpt.com · from codex_apps", prompt.origin)
        assertFalse("s3cret" in prompt.toString())
        val described = ExecutionRequest("p3", ExecutionRequestKind.Approval, Json.parseToJsonElement("""{"toolCall":{"title":"Edit README.md","rawInput":{"description":"Adds a section"}}}"""), emptyList())
        assertEquals(ExternalPrompt.Approval("p3", "Edit README.md", "Adds a section", emptyList()), described.prompt())
        assertNull((described.prompt() as ExternalPrompt.Approval).origin)
    }

    @Test fun formRequestsAnswerInTheirSchemaTypes() {
        val payload = Json.parseToJsonElement("""{"mode":"form","message":"Deploy where?","requestedSchema":{"type":"object","required":["env"],"properties":{
            "env":{"type":"string","title":"Environment","oneOf":[{"const":"prod","title":"Production"},{"const":"stage","title":"Staging"}]},
            "dry":{"type":"boolean","title":"Dry run","default":true},
            "count":{"type":"integer","title":"Replicas"},
            "tags":{"type":"array","items":{"type":"string","enum":["a","b"]}}}}}""")
        val prompt = assertIs<ExternalPrompt.Questions>(ExecutionRequest("q1", ExecutionRequestKind.Question, payload).prompt())
        val (env, dry, count, tags) = prompt.asked.questions
        assertEquals(QuestionKind.Single to listOf("Production", "Staging"), env.kind to env.options)
        assertTrue(env.required)
        assertEquals(QuestionKind.YesNo to listOf("Yes"), dry.kind to dry.default)
        assertEquals(QuestionKind.Number, count.kind)
        assertEquals(QuestionKind.Multi to listOf("a", "b"), tags.kind to tags.options)
        val answer = questionsAnswer(prompt, mapOf("env" to listOf("Staging"), "dry" to listOf("No"), "count" to listOf("3"), "tags" to listOf("b")))
        assertEquals("""{"action":"accept","content":{"env":"stage","dry":false,"count":3,"tags":["b"]}}""", answer.toString())
        assertEquals("""{"action":"decline"}""", questionsAnswer(prompt, null).toString())
        val url = ExecutionRequest("u", ExecutionRequestKind.Question, Json.parseToJsonElement("""{"mode":"url","url":"https://x","message":"Open the page"}"""))
        assertEquals(ExternalPrompt.OpenLink("u", "Open the page", "https://x", "x"), url.prompt())
    }

    @Test fun linkingNeedsASecureAddressANameAndACode() {
        assertEquals(listOf("Name is required", "Address is required", "Link code is required"), LinkForm().problems(emptyList()))
        val form = LinkForm("Studio", "studio.example.com", "code")
        assertEquals("wss://studio.example.com:443/ws", form.address)
        assertTrue(form.problems(listOf(claude)).isEmpty())
        assertEquals(listOf("Use the other Server's https:// address"), form.copy(url = "http://studio.example.com").problems(emptyList()))
        val connection = form.connection(listOf(claude))
        assertEquals(HarnessConnection("studio", "Studio", ExecutionConnection.Remote("studio"), "Studio"), connection)
    }

    @Test fun sharingPicksAgentsExplicitlyAndNeverRelaysRemoteOnes() {
        assertEquals(listOf("Say who it's for, e.g. the other Server's name", "Pick at least one Agent"), ShareForm().problems())
        val remote = AgentInfo("ada", "Ada", "external", executionKind = ExecutionKind.Remote, connectionId = "studio")
        assertEquals(listOf("main", "reviewer"), shareable(listOf(AgentInfo("main", "Kinuvo", "none"), acpAgent, remote)).map { it.id })
        val s = OnboardingState().choose(HarnessChoice.Remote("studio"))
        assertEquals("milo-here", s.added("milo", listOf(AgentRegistration("milo-here", "Milo", "studio", remoteAgentId = "milo"))))
        assertNull(s.added("milo", listOf(AgentRegistration("milo-here", "Milo", "other", remoteAgentId = "milo"))))
        assertEquals("Not checked", readiness(CapabilityState.unavailable("x", "not_checked"), "Studio", "Studio").pill)
        assertEquals(listOf(ReadinessAction.Edit), readiness(CapabilityState.unavailable("x", "access_revoked"), "Studio", "Studio").actions)
    }

    @Test fun aLinkedKinuvoServersFormsAndApprovalsAnswerAsKinuvoDoes() {
        val asked = AgentEvent.QuestionsAsked("q7", "call-1", "Trip details", listOf(Question("when", "When?", QuestionKind.Date, required = true), Question("seat", "Seat", QuestionKind.Single, listOf("Aisle", "Window"))))
        val request = ExecutionRequest("q7", ExecutionRequestKind.Question, ProtocolJson.encodeToJsonElement(AgentEvent.QuestionsAsked.serializer(), asked))
        val prompt = assertIs<ExternalPrompt.Questions>(request.prompt())
        assertTrue(prompt.native)
        assertEquals(asked.questions, prompt.asked.questions)
        val answers = mapOf("when" to listOf("2026-11-02"), "seat" to listOf("Aisle"))
        assertEquals(QuestionsParams("q7", answers), ProtocolJson.decodeFromJsonElement(QuestionsParams.serializer(), questionsAnswer(prompt, answers)))
        assertEquals(QuestionsParams("q7", null), ProtocolJson.decodeFromJsonElement(QuestionsParams.serializer(), questionsAnswer(prompt, null)))
        val approval = ExecutionRequest("a1", ExecutionRequestKind.Approval, ProtocolJson.encodeToJsonElement(AgentEvent.ApprovalRequested.serializer(),
            AgentEvent.ApprovalRequested("a1", "t1", "shell", """{"command":"git push"}""", reason = "Pushes to a remote")),
            listOf(ExternalApprovalOption("allow_once", "Allow once", "allow_once"), ExternalApprovalOption("deny", "Deny", "reject_once")))
        val shown = assertIs<ExternalPrompt.Approval>(approval.prompt())
        assertEquals("shell" to "Pushes to a remote", shown.title to shown.description)
        assertEquals("""{"outcome":{"outcome":"selected","optionId":"deny"}}""", approvalAnswer("deny").toString())
    }

    @Test fun theServersCapabilityLeadsANewAgentWithWhereItRuns() {
        assertTrue(ServerCapabilities("s", integrations = mapOf("Harness" to setOf("acp"))).runsHarnesses)
        assertFalse(ServerCapabilities("s", integrations = null).runsHarnesses)
        assertFalse(ServerCapabilities("s", integrations = mapOf("Exec" to setOf("host"))).runsHarnesses)
        assertFalse((null as ServerCapabilities?).runsHarnesses)
    }
}
