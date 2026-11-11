package kinuvo.app

import kinuvo.app.agent.*
import kinuvo.protocol.agent.*
import kotlin.test.*

class AgentDraftTest {
    private val details = AgentDetails(
        AgentInfo(AgentId("a"), "Ada", "none"),
        AgentSettings("gpt", "medium", "default", "none", provider = "codex"),
        identity = "Curious", soul = "Kind",
    )
    private val agents = listOf(AgentInfo(AgentId("a"), "Ada", "none"), AgentInfo(AgentId("b"), "Bob", "none"))
    private val draft = AgentDraft.of(details)

    @Test fun ofCopiesTheAgentsDefaults() {
        assertEquals(AgentDraft("Ada", "Curious", "Kind", "codex", "gpt", "medium", "default", avatar = details.info.avatarArtboard), draft)
    }

    @Test fun unchangedDraftHasNoChanges() {
        assertEquals(UpdateAgentParams(AgentId("a")), draft.changes(details))
        assertEquals(UpdateAgentParams(AgentId("a")), draft.copy(name = " Ada ", model = " gpt ").changes(details))
    }

    @Test fun changesOnlyIncludeWhatDiffers() {
        assertEquals(UpdateAgentParams(AgentId("a"), name = "Ava"), draft.copy(name = " Ava ").changes(details))
        assertEquals(UpdateAgentParams(AgentId("a"), model = "other"), draft.copy(model = "other").changes(details))
        assertEquals(UpdateAgentParams(AgentId("a"), reasoning = "high"), draft.copy(reasoning = "high").changes(details))
        assertEquals(UpdateAgentParams(AgentId("a"), approvals = "strict"), draft.copy(approvals = "strict").changes(details))
        assertEquals(UpdateAgentParams(AgentId("a"), identity = "Bold"), draft.copy(identity = "Bold").changes(details))
        assertEquals(UpdateAgentParams(AgentId("a"), soul = "Firm"), draft.copy(soul = "Firm").changes(details))
        assertEquals(UpdateAgentParams(AgentId("a"), name = "Ava", soul = "Firm"), draft.copy(name = "Ava", soul = "Firm").changes(details))
        val other = AvatarArtboards.first { it != details.info.avatarArtboard }
        assertEquals(UpdateAgentParams(AgentId("a"), avatar = other), draft.copy(avatar = other).changes(details))
    }

    @Test fun changingTheProviderSendsTheModelWithIt() {
        assertEquals(UpdateAgentParams(AgentId("a"), model = "gpt", provider = "openai"), draft.copy(provider = "openai").changes(details))
    }

    @Test fun aValidDraftHasNoProblems() {
        assertEquals(emptyList(), draft.problems(AgentId("a"), agents))
        assertEquals(emptyList(), draft.copy(name = "x".repeat(AgentDraft.MaxName)).problems(AgentId("a"), agents))
        assertEquals(emptyList(), draft.copy(name = "ADA").problems(AgentId("a"), agents))
    }

    @Test fun nameIsRequiredAndLimited() {
        assertEquals(listOf("Name is required"), draft.copy(name = "").problems(AgentId("a"), agents))
        assertEquals(listOf("Name is required"), draft.copy(name = "   ").problems(AgentId("a"), agents))
        assertEquals(listOf("Name is at most 40 characters"), draft.copy(name = "x".repeat(41)).problems(AgentId("a"), agents))
        assertEquals(emptyList(), draft.copy(name = " " + "x".repeat(40) + " ").problems(AgentId("a"), agents))
    }

    @Test fun nameMustBeUniqueIgnoringCaseAndSpaces() {
        assertEquals(listOf("Another Agent is called bob"), draft.copy(name = " bob ").problems(AgentId("a"), agents))
        assertEquals(listOf("Another Agent is called BOB"), draft.copy(name = "BOB").problems(AgentId("a"), agents))
        assertEquals(emptyList(), draft.copy(name = "Bob").problems(AgentId("b"), agents))
    }

    @Test fun modelIsRequired() {
        assertEquals(listOf("Pick a model"), draft.copy(model = " ").problems(AgentId("a"), agents))
        assertEquals(listOf("Name is required", "Pick a model"), draft.copy(name = "", model = "").problems(AgentId("a"), agents))
    }
}
