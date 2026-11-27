package kinuvo.providers.codex

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kinuvo.server.plugin.Keychain
import kinuvo.server.plugin.LiveCallEvent
import kinuvo.server.plugin.LiveCallException
import kinuvo.server.plugin.LiveCallUnavailable
import kinuvo.server.plugin.VoiceContext
import kinuvo.server.plugin.VoiceMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class CodexLiveCallsTest {
    private val auth = CodexAuth(store = object : Keychain {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":${(Clock.System.now() + 1.hours).epochSeconds}}""".toByteArray())
        var token: String? = """{"auth_mode":"chatgpt","tokens":{"id_token":"id","access_token":"h.$payload.s","refresh_token":"r","account_id":"acct"}}"""
        override val name = "test"
        override suspend fun read() = token
        override suspend fun write(token: String) { this.token = token }
        override suspend fun delete() { token = null }
    })
    private val context = VoiceContext("Speak for Ada.", listOf(VoiceMessage(true, "hello"), VoiceMessage(false, "hi")))

    private class Requests {
        val seen = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val bodies = mutableListOf<String>()
    }

    private fun provider(requests: Requests, status: HttpStatusCode = HttpStatusCode.Created, body: String = "answer-sdp", location: String? = "https://chatgpt.com/backend-api/codex/realtime/calls/rtc_123") =
        CodexLiveCalls(auth, HttpClient(MockEngine { request ->
            requests.seen += request
            requests.bodies += String(request.body.toByteArray())
            respond(body, status, if (location != null) headersOf(HttpHeaders.Location, location) else headersOf())
        }))

    @Test fun startCreatesTheCallFromTheDevicesOffer() = runTest {
        val requests = Requests()
        val start = provider(requests).start(context, "device-offer")
        assertEquals("answer-sdp", start.answerSdp)
        val request = requests.seen.single()
        assertEquals("https://chatgpt.com/backend-api/codex/realtime/calls?intent=quicksilver&architecture=avas", request.url.toString())
        assertTrue(request.headers[HttpHeaders.Authorization]!!.startsWith("Bearer h."))
        assertEquals("acct", request.headers["chatgpt-account-id"])
        assertEquals("quicksilver=v2", request.headers["OpenAI-Alpha"])
        assertEquals("kinuvo", request.headers["originator"])
        assertEquals(request.headers["session-id"], request.headers["x-session-id"])
        val body = Json.parseToJsonElement(requests.bodies.single()).jsonObject
        assertEquals("device-offer", body.getValue("sdp").jsonPrimitive.content)
        val session = body.getValue("session").jsonObject
        assertEquals("gpt-live-1-codex", session.getValue("model").jsonPrimitive.content)
        assertEquals("Speak for Ada.", session.getValue("instructions").jsonPrimitive.content)
        assertEquals("cove", session.getValue("audio").jsonObject.getValue("output").jsonObject.getValue("voice").jsonPrimitive.content)
        assertEquals("client", session.getValue("delegation").jsonObject.getValue("type").jsonPrimitive.content)
        val items = session.getValue("initial_items").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant"), items.map { it.getValue("role").jsonPrimitive.content })
        assertEquals(listOf("input_text", "output_text"), items.map { it.getValue("content").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content })
    }

    @Test fun theCallIdComesFromTheLocationHeader() {
        assertEquals("rtc_123", CodexLiveCalls.callId("https://chatgpt.com/backend-api/codex/realtime/calls/rtc_123"))
        assertEquals("rtc_123", CodexLiveCalls.callId("/v1/realtime/calls/rtc_123/?x=1"))
        assertNull(CodexLiveCalls.callId(null))
        assertNull(CodexLiveCalls.callId("/"))
    }

    @Test fun startFailsWithoutALocation() = runTest {
        assertFailsWith<LiveCallException> { provider(Requests(), location = null).start(context, "offer") }
    }

    @Test fun startFailuresAreWordedForTheDevice() = runTest {
        suspend fun message(status: HttpStatusCode, body: String) = assertFailsWith<LiveCallException> { provider(Requests(), status, body).start(context, "offer") }.message
        assertEquals("ChatGPT's usage limit is reached. It resets in 49 minutes.", message(HttpStatusCode.TooManyRequests, """{"error":{"type":"usage_limit_reached","resets_in_seconds":2940}}"""))
        assertEquals("ChatGPT is busy. Try again in a moment.", message(HttpStatusCode.TooManyRequests, "{}"))
        assertEquals("Couldn't start the call (HTTP 500).", message(HttpStatusCode.InternalServerError, "<html>"))
    }

    @Test fun signedOutOwnersAreNotAvailable() = runTest {
        val signedOut = CodexAuth(store = object : Keychain {
            override val name = "empty"
            override suspend fun read(): String? = null
            override suspend fun write(token: String) {}
            override suspend fun delete() {}
        })
        assertEquals(false, CodexLiveCalls(signedOut, HttpClient(MockEngine { respond("") })).available())
        assertEquals(true, provider(Requests()).available())
    }

    @Test fun onlyTheCodexLoginOffersCallsAndABrowserSignInSaysWhichSignInFixesIt() = runTest {
        val codex = provider(Requests())
        assertEquals(true, codex.available())
        assertNull(codex.unavailable())

        val browser = CodexLiveCalls(CodexAuth(store = keychain("""{"siwc":{"selected":true,"access_token":"siwc-access","refresh_token":"r","expires_at":4102444800,"scope":"chatgpt.tokens.use.direct"}}""")), HttpClient(MockEngine { respond("") }))
        assertEquals(false, browser.available())
        assertEquals(LiveCallUnavailable(DEVICE_CODE_REASON, "codex"), browser.unavailable())

        val signedOut = CodexLiveCalls(CodexAuth(store = keychain(null)), HttpClient(MockEngine { respond("") }))
        assertEquals(false, signedOut.available())
        assertNull(signedOut.unavailable())
    }

    @Test fun anAccessEnforcementRejectionPointsAtTheDeviceCodeSignIn() = runTest {
        val requests = Requests()
        val message = assertFailsWith<LiveCallException> {
            provider(requests, HttpStatusCode.Unauthorized, """{"error":{"type":"rejected_by_access_enforcement","code":"no_matching_rule"}}""", null).start(context, "offer")
        }.message
        assertEquals(NEEDS_DEVICE_CODE, message)
        assertEquals(1, requests.seen.size)
    }

    private fun keychain(text: String?) = object : Keychain {
        override val name = "test"
        override suspend fun read() = text
        override suspend fun write(token: String) {}
        override suspend fun delete() {}
    }

    private class FakeSideband : Sidebands {
        val frames = Channel<String>(Channel.UNLIMITED)
        val sent = Channel<String>(Channel.UNLIMITED)
        var url: String? = null
        var tokens: CodexTokens? = null
        override suspend fun connect(url: String, tokens: CodexTokens, session: String, block: suspend (Sideband) -> Unit) {
            this.url = url; this.tokens = tokens
            block(object : Sideband {
                override val incoming: Flow<String> = frames.receiveAsFlow()
                override suspend fun send(text: String) { sent.send(text) }
            })
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.running(fake: FakeSideband, events: MutableList<LiveCallEvent>, delegations: CompletableDeferred<Unit>? = null): Pair<CodexLiveCall, kotlinx.coroutines.Job> {
        val call = CodexLiveCall("rtc_1", "session", auth, "wss://sideband/rtc_1", fake)
        val job = launch { call.run { events += it; if (it is LiveCallEvent.Delegation) delegations?.complete(Unit) } }
        return call to job
    }

    @Test fun sidebandEventsBecomeCallEvents() = runTest {
        val fake = FakeSideband()
        val events = mutableListOf<LiveCallEvent>()
        val (_, job) = running(fake, events)
        fake.frames.send("""{"type":"session.input_audio.append","audio":"AAAA"}""")
        fake.frames.send("""{"type":"session.output_audio.delta","delta":"AAAA"}""")
        fake.frames.send("""{"type":"turn.created","turn":{"id":"t1","role":"user"}}""")
        fake.frames.send("""{"type":"input_transcript.added","item":{"text":"Hello "}}""")
        fake.frames.send("""{"type":"input_transcript.added","item":{"text":"there"}}""")
        fake.frames.send("""{"type":"turn.done","turn":{"id":"t1","role":"user","transcript":" Hello there "}}""")
        fake.frames.send("""{"type":"turn.created","turn":{"id":"t2","role":"assistant"}}""")
        fake.frames.send("""{"type":"output_transcript.added","item":{"text":"Hi"}}""")
        fake.frames.send("""{"type":"turn.done","turn":{"id":"t2","role":"assistant","transcript":"Hi!"}}""")
        fake.frames.send("""{"type":"delegation.created","item":{"id":"d1","content":[{"type":"input_text","text":"check "},{"type":"input_text","text":"the weather"}]}}""")
        fake.frames.send("""{"type":"delegation.created","item":{"id":"d2","target":"server","content":[{"type":"input_text","text":"ignored"}]}}""")
        fake.frames.send("not json")
        fake.frames.send("""{"type":"session.closed"}""")
        job.join()
        assertEquals(listOf(
            LiveCallEvent.Ready,
            LiveCallEvent.Listening,
            LiveCallEvent.Transcript("t1", "Hello", user = true, final = false),
            LiveCallEvent.Transcript("t1", "Hello there", user = true, final = false),
            LiveCallEvent.Transcript("t1", "Hello there", user = true),
            LiveCallEvent.Speaking,
            LiveCallEvent.Transcript("t2", "Hi", user = false, final = false),
            LiveCallEvent.Transcript("t2", "Hi!", user = false),
            LiveCallEvent.Listening,
            LiveCallEvent.Delegation("d1", "check the weather"),
        ), events)
        assertEquals("wss://sideband/rtc_1", fake.url)
    }

    @Test fun controlsAreSentOverTheSideband() = runTest {
        val fake = FakeSideband()
        val (call, job) = running(fake, mutableListOf())
        call.reply("d1", "Checking the forecast", final = false)
        call.reply("d1", "Sunny", final = true)
        call.notify("The build finished")
        call.pause()
        call.resume()
        call.end()
        suspend fun next(): JsonObject = Json.parseToJsonElement(fake.sent.receive()).jsonObject
        assertEquals("""{"type":"delegation.context.append","delegation_item_id":"d1","channel":"commentary","content":[{"type":"input_text","text":"Checking the forecast"}]}""", next().toString())
        assertEquals("""{"type":"delegation.context.append","delegation_item_id":"d1","channel":"speakable","content":[{"type":"input_text","text":"Sunny"}]}""", next().toString())
        assertEquals("""{"type":"session.context.append","channel":"speakable","content":[{"type":"input_text","text":"The build finished"}]}""", next().toString())
        assertEquals("input_audio.pause", next().getValue("type").jsonPrimitive.content)
        assertEquals("input_audio.resume", next().getValue("type").jsonPrimitive.content)
        assertEquals("session.close", next().getValue("type").jsonPrimitive.content)
        fake.frames.send("""{"type":"session.closed"}""")
        job.join()
    }

    @Test fun longRepliesAreSplitUnderTheContextLimit() = runTest {
        val fake = FakeSideband()
        val (call, job) = running(fake, mutableListOf())
        call.reply("d1", "a".repeat(1200), final = true)
        val texts = List(3) { Json.parseToJsonElement(fake.sent.receive()).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content }
        assertEquals(listOf(500, 500, 200), texts.map { it.length })
        fake.frames.send("""{"type":"session.closed"}""")
        job.join()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.connected(fake: FakeSideband): Pair<CodexLiveCall, kotlinx.coroutines.Job> {
        val call = CodexLiveCall("rtc_1", "session", auth, "wss://sideband/rtc_1", fake, holdMillis = 1_000, nudgeMillis = 500)
        val job = launch { call.run {} }
        runCurrent()
        return call to job
    }

    private fun FakeSideband.drain(): List<JsonObject> = buildList { while (true) add(Json.parseToJsonElement(sent.tryReceive().getOrNull() ?: break).jsonObject) }

    private fun JsonObject.channel() = getValue("channel").jsonPrimitive.content

    private suspend fun FakeSideband.close(job: kotlinx.coroutines.Job) { frames.send("""{"type":"session.closed"}"""); job.join() }

    @Test fun anAnswerWaitsForAnOpenTurnToEndAndIsThenSpoken() = runTest {
        val fake = FakeSideband()
        val (call, job) = connected(fake)
        fake.frames.send("""{"type":"turn.created","turn":{"id":"t1","role":"assistant"}}""")
        runCurrent()
        call.reply("d1", "Sunny", final = true)
        runCurrent()
        assertEquals(emptyList(), fake.drain())
        fake.frames.send("""{"type":"turn.done","turn":{"id":"t1","role":"assistant","transcript":"Let me check"}}""")
        runCurrent()
        val sent = fake.drain().single()
        assertEquals("delegation.context.append", sent.getValue("type").jsonPrimitive.content)
        assertEquals("speakable", sent.channel())
        fake.frames.send("""{"type":"turn.created","turn":{"id":"t2","role":"assistant"}}""")
        fake.close(job)
    }

    @Test fun anAnswerWaitsForTheUsersTurnToo() = runTest {
        val fake = FakeSideband()
        val (call, job) = connected(fake)
        fake.frames.send("""{"type":"turn.created","turn":{"id":"u1","role":"user"}}""")
        runCurrent()
        call.reply("d1", "one", final = true)
        call.reply("d2", "two", final = true)
        runCurrent()
        assertEquals(emptyList(), fake.drain())
        fake.frames.send("""{"type":"turn.done","turn":{"id":"u1","role":"user","transcript":"hm"}}""")
        runCurrent()
        assertEquals(listOf("d1", "d2"), fake.drain().map { it.getValue("delegation_item_id").jsonPrimitive.content })
        fake.close(job)
    }

    @Test fun aTurnThatNeverEndsDoesNotHoldTheAnswerBackForever() = runTest {
        val fake = FakeSideband()
        val (call, job) = connected(fake)
        fake.frames.send("""{"type":"turn.created","turn":{"id":"t1","role":"assistant"}}""")
        runCurrent()
        call.reply("d1", "Sunny", final = true)
        advanceTimeBy(999)
        assertEquals(emptyList(), fake.drain())
        advanceTimeBy(2)
        assertEquals(listOf("speakable"), fake.drain().map { it.channel() })
        fake.close(job)
    }

    @Test fun aModelThatStaysSilentAfterTheAnswerIsNudgedOnce() = runTest {
        val fake = FakeSideband()
        val (call, job) = connected(fake)
        call.reply("d1", "Sunny", final = true)
        runCurrent()
        assertEquals(listOf("delegation.context.append"), fake.drain().map { it.getValue("type").jsonPrimitive.content })
        advanceTimeBy(499)
        assertEquals(emptyList(), fake.drain())
        advanceTimeBy(2)
        val nudge = fake.drain().single()
        assertEquals("session.context.append", nudge.getValue("type").jsonPrimitive.content)
        assertEquals("speakable", nudge.channel())
        advanceTimeBy(60_000)
        assertEquals(emptyList(), fake.drain())
        fake.close(job)
    }

    @Test fun aModelThatSpeaksTheAnswerIsNotNudged() = runTest {
        val fake = FakeSideband()
        val (call, job) = connected(fake)
        call.reply("d1", "Sunny", final = true)
        runCurrent()
        fake.drain()
        advanceTimeBy(300)
        fake.frames.send("""{"type":"turn.created","turn":{"id":"t3","role":"assistant"}}""")
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals(emptyList(), fake.drain())
        fake.close(job)
    }

    @Test fun progressIsCommentaryEvenWhileATurnIsOpen() = runTest {
        val fake = FakeSideband()
        val (call, job) = connected(fake)
        fake.frames.send("""{"type":"turn.created","turn":{"id":"t1","role":"assistant"}}""")
        runCurrent()
        call.reply("d1", "Still going", final = false)
        runCurrent()
        assertEquals(listOf("commentary"), fake.drain().map { it.channel() })
        advanceTimeBy(60_000)
        assertEquals(emptyList(), fake.drain())
        fake.close(job)
    }

    @Test fun aLongAnswerGoesOutInOrderOnTheSpeakableChannel() = runTest {
        val fake = FakeSideband()
        val (call, job) = connected(fake)
        call.reply("d1", "a".repeat(500) + "b".repeat(500) + "c", final = true)
        runCurrent()
        val chunks = fake.drain()
        assertEquals(listOf("a", "b", "c"), chunks.map { it.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content.first().toString() })
        assertEquals(setOf("speakable"), chunks.map { it.channel() }.toSet())
        fake.close(job)
    }

    @Test fun aSidebandThatClosesWithoutAnEndIsADroppedCall() = runTest {
        val fake = FakeSideband()
        val call = CodexLiveCall("rtc_1", "session", auth, "wss://sideband/rtc_1", fake)
        fake.frames.close()
        assertEquals("The call dropped.", assertFailsWith<LiveCallException> { call.run {} }.message)
    }

    @Test fun anEndRequestedByUsEndsTheCallCleanly() = runTest {
        val fake = FakeSideband()
        val call = CodexLiveCall("rtc_1", "session", auth, "wss://sideband/rtc_1", fake)
        call.end()
        fake.frames.close()
        call.run {}
    }

    @Test fun onlyErrorsThatEndTheSessionFailTheCall() = runTest {
        val fake = FakeSideband()
        val call = CodexLiveCall("rtc_1", "session", auth, "wss://sideband/rtc_1", fake)
        fake.frames.send("""{"type":"error","error":{"type":"invalid_request_error","code":"bad_event","message":"nope"}}""")
        fake.frames.send("""{"type":"error","error":{"type":"server_error","message":"Overloaded"}}""")
        assertEquals("Overloaded", assertFailsWith<LiveCallException> { call.run {} }.message)
    }

    @Test fun audioEchoesAreNotParsed() = runTest {
        val fake = FakeSideband()
        val events = mutableListOf<LiveCallEvent>()
        val (_, job) = running(fake, events)
        repeat(1000) { fake.frames.send("""{"type":"session.output_audio.delta","delta":"${"A".repeat(2000)}"}""") }
        fake.frames.send("""{"type":"session.closed"}""")
        job.join()
        assertEquals(listOf<LiveCallEvent>(LiveCallEvent.Ready), events)
    }
}
