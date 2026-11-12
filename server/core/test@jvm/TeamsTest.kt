package kinuvo.server

import kinuvo.protocol.Api
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.*
import kinuvo.protocol.team.*
import kinuvo.testing.server.TestServer
import kinuvo.testing.server.FakeHarness
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.*

class TeamsTest {
    private val a = AgentId("first")
    private val b = AgentId("second")
    private suspend fun await(server: TestServer, id: TeamId, predicate: (TeamSnapshot) -> Boolean): TeamSnapshot = withTimeout(10000) {
        while (true) {
            val snapshot = throwing<TeamError, _> { server.harness.teams!!.get(id) }
            if (predicate(snapshot)) return@withTimeout snapshot
            yield()
        }
        error("unreachable")
    }
    private suspend fun idle(server: TestServer, id: TeamId, replies: Int) = await(server, id) { it.runningMember == null && it.team.log.count { it.kind == TeamEntryKind.Reply } >= replies }

    @Test fun routingContextAndDurability() = runBlocking {
        val home = Files.createTempDirectory("teams").toFile()
        lateinit var saved: Team
        try {
            TestServer.start(home = Path(home.path), script = { reply("member reply") }, scenario = {
                agent("first") { it.copy(approvals = "full") }; agent("second") { it.copy(approvals = "full") }
            }).use { server ->
                val client = server.client(token = server.auth.sessionToken()); client.connect()
                val team = client.call(Api.CreateTeam, TeamSpec("Work", listOf(a, b))).team
                client.call(Api.TeamSend, TeamSendParams(team.id, "hello"))
                assertEquals(a, idle(server, team.id, 1).team.log.last().agentId)
                client.call(Api.TeamSend, TeamSendParams(team.id, "@second next"))
                assertEquals(b, idle(server, team.id, 2).team.log.last().agentId)
                assertContains(server.prompts.inputs.last().text, "hello")
                assertContains(server.prompts.inputs.last().text, "member reply")
                client.call(Api.TeamSend, TeamSendParams(team.id, "@first @second together"))
                saved = idle(server, team.id, 4).team
                assertEquals(listOf(a, b), saved.log.filter { it.kind == TeamEntryKind.Reply }.takeLast(2).map { it.agentId })
                assertTrue(saved.members.all { it.cursor > 0 })
                assertTrue(server.harness.log.page(a, null, 100).sessions.none { s -> team.members.any { it.sessionId == s.id } })
            }
            TestServer.start(home = Path(home.path)).use { server -> assertEquals(saved, throwing<TeamError, _> { server.harness.teams!!.get(saved.id).team }) }
        } finally { home.deleteRecursively() }
    }

    @Test fun handoffIsBoundedAndDoesNotShareTools() = runBlocking {
        TestServer.start(script = {
            on("[Handoff second -> first]") { toolCall("team_handoff", """{"agent_id":"second","note":"go"}"""); reply("A again") }
            on("[Handoff first -> second]") { toolCall("team_handoff", """{"agent_id":"first","note":"back"}"""); reply("B answer") }
            on("start") { toolCall("team_handoff", """{"agent_id":"second","note":"go"}"""); reply("A answer") }
        }, scenario = {
            agent("first") { it.copy(approvals = "full") }; agent("second") { it.copy(approvals = "full") }
        }).use { server ->
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            val team = client.call(Api.CreateTeam, TeamSpec("Work", listOf(a, b))).team
            client.call(Api.TeamSend, TeamSendParams(team.id, "start"))
            val result = idle(server, team.id, 5)
            assertEquals(4, result.team.log.count { it.kind == TeamEntryKind.Handoff })
            assertContains(server.prompts.inputs[1].text, "go")
            assertContains(server.prompts.inputs[1].text, "A answer")
            assertFalse(server.prompts.inputs[1].text.contains("team_handoff"))
        }
    }

    @Test fun approvalAttributionAnswerAndCancel() = runBlocking {
        TestServer.start(script = {
            on("hold") { toolCall("team_handoff", """{"agent_id":"second","note":"continue"}"""); reply("done") }
        }, scenario = {
            agent("first") { it.copy(approvals = "strict") }; agent("second")
        }).use { server ->
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            val team = client.call(Api.CreateTeam, TeamSpec("Work", listOf(a, b))).team
            client.call(Api.TeamSend, TeamSendParams(team.id, "hold"))
            val pending = await(server, team.id) { it.pending.isNotEmpty() }.pending.single()
            assertEquals(a, pending.agentId)
            assertIs<PendingInteraction.Approval>(pending.interaction)
            assertTrue(client.call(Api.TeamRespond, TeamRespondParams(team.id, pending.interaction.id, InteractionAnswer.Approval(ApprovalDecision.Deny()))))
            idle(server, team.id, 1)
            client.call(Api.TeamSend, TeamSendParams(team.id, "hold again"))
            await(server, team.id) { it.pending.isNotEmpty() }
            client.call(Api.TeamStop, team.id)
            val stopped = await(server, team.id) { it.runningMember == null }
            assertTrue(stopped.pending.isEmpty())
        }
    }

    @Test fun externalMembersAndCrudUseTheHostHarnessRegistry() = runBlocking {
        val external = FakeHarness()
        TestServer.start(harnesses = listOf(external), scenario = {
            agent("first"); externalAgent(external, "second", "External")
        }).use { server ->
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            val team = client.call(Api.CreateTeam, TeamSpec("Work", listOf(a, b))).team
            client.call(Api.UpdateTeam, UpdateTeamParams(team.id, TeamSpec("Renamed", listOf(a, b), b)))
            client.call(Api.TeamSend, TeamSendParams(team.id, "hello external"))
            val result = idle(server, team.id, 1)
            assertEquals(b, result.team.log.last().agentId)
            assertEquals("Fake harness replied", result.team.log.last().text)
            client.call(Api.DeleteTeam, team.id)
            assertTrue(client.call(Api.Teams, Unit).isEmpty())
        }
    }

    @Test fun activationBudgetIsSharedAndExhaustionStopsTheTeam() = runBlocking {
        TestServer.start(script = {
            on("second wait") { toolCall("ask_user", """{"questions":[{"id":"ok","label":"Wait","kind":"text"}]}""") }
            on("hold") {
                toolCall("ask_user", """{"questions":[{"id":"ok","label":"Wait","kind":"text"}]}""")
                toolCall("team_handoff", """{"agent_id":"second","note":"second wait"}""")
                reply("first done")
            }
        }, scenario = {
            agent("first") { it.copy(approvals = "full") }; agent("second") { it.copy(approvals = "full") }
        }).use { server ->
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            val team = client.call(Api.CreateTeam, TeamSpec("Work", listOf(a, b))).team
            client.call(Api.TeamSend, TeamSendParams(team.id, "hold"))
            val first = await(server, team.id) { it.pending.isNotEmpty() }.pending.single()
            repeat(60) { server.harness.subAgents!!.consume(team.members.first().sessionId) }
            client.call(Api.TeamRespond, TeamRespondParams(team.id, first.interaction.id, InteractionAnswer.Questions(mapOf("ok" to listOf("yes")))))
            await(server, team.id) { it.pending.any { p -> p.agentId == b } }
            try { repeat(5) { server.harness.subAgents!!.consume(team.members.last().sessionId) }; fail("Budget must stop the Team") }
            catch (_: CancellationException) { }
            await(server, team.id) { it.runningMember == null }
            Unit
        }
    }
}
