package kinuvo.app

import kinuvo.app.agent.*
import kinuvo.protocol.*
import kotlin.test.*

class AgentDraftTest {
    private val details = AgentDetails(
        AgentInfo("a", "Ada", "none"),
        AgentSettings("gpt", "medium", "default", "none", provider = "codex"),
        identity = "Curious", soul = "Kind",
    )
    private val agents = listOf(AgentInfo("a", "Ada", "none"), AgentInfo("b", "Bob", "none"))
    private val draft = AgentDraft.of(details)

    @Test fun ofCopiesTheAgentsDefaults() {
        assertEquals(AgentDraft("Ada", "Curious", "Kind", "codex", "gpt", "medium", "default"), draft)
    }

    @Test fun unchangedDraftHasNoChanges() {
        assertEquals(UpdateAgentParams("a"), draft.changes(details))
        assertEquals(UpdateAgentParams("a"), draft.copy(name = " Ada ", model = " gpt ").changes(details))
    }

    @Test fun changesOnlyIncludeWhatDiffers() {
        assertEquals(UpdateAgentParams("a", name = "Ava"), draft.copy(name = " Ava ").changes(details))
        assertEquals(UpdateAgentParams("a", model = "other"), draft.copy(model = "other").changes(details))
        assertEquals(UpdateAgentParams("a", reasoning = "high"), draft.copy(reasoning = "high").changes(details))
        assertEquals(UpdateAgentParams("a", approvals = "strict"), draft.copy(approvals = "strict").changes(details))
        assertEquals(UpdateAgentParams("a", identity = "Bold"), draft.copy(identity = "Bold").changes(details))
        assertEquals(UpdateAgentParams("a", soul = "Firm"), draft.copy(soul = "Firm").changes(details))
        assertEquals(UpdateAgentParams("a", name = "Ava", soul = "Firm"), draft.copy(name = "Ava", soul = "Firm").changes(details))
    }

    @Test fun changingTheProviderSendsTheModelWithIt() {
        assertEquals(UpdateAgentParams("a", model = "gpt", provider = "openai"), draft.copy(provider = "openai").changes(details))
    }

    @Test fun aValidDraftHasNoProblems() {
        assertEquals(emptyList(), draft.problems("a", agents))
        assertEquals(emptyList(), draft.copy(name = "x".repeat(AgentDraft.MaxName)).problems("a", agents))
        assertEquals(emptyList(), draft.copy(name = "ADA").problems("a", agents))
    }

    @Test fun nameIsRequiredAndLimited() {
        assertEquals(listOf("Name is required"), draft.copy(name = "").problems("a", agents))
        assertEquals(listOf("Name is required"), draft.copy(name = "   ").problems("a", agents))
        assertEquals(listOf("Name is at most 40 characters"), draft.copy(name = "x".repeat(41)).problems("a", agents))
        assertEquals(emptyList(), draft.copy(name = " " + "x".repeat(40) + " ").problems("a", agents))
    }

    @Test fun nameMustBeUniqueIgnoringCaseAndSpaces() {
        assertEquals(listOf("Another Agent is called bob"), draft.copy(name = " bob ").problems("a", agents))
        assertEquals(listOf("Another Agent is called BOB"), draft.copy(name = "BOB").problems("a", agents))
        assertEquals(emptyList(), draft.copy(name = "Bob").problems("b", agents))
    }

    @Test fun modelIsRequired() {
        assertEquals(listOf("Pick a model"), draft.copy(model = " ").problems("a", agents))
        assertEquals(listOf("Name is required", "Pick a model"), draft.copy(name = "", model = "").problems("a", agents))
    }
}
