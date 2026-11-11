package kinuvo.testing.fixture

import kinuvo.testing.server.Scenario
import kinuvo.testing.server.TestServer
import kinuvo.testing.server.showcase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

private const val DEFAULT_PORT = 18081

/** What the scripted model answers; Maestro flows assert it. */
const val FIXTURE_REPLY = "Hello from the fixture Server"

private val SCENARIOS: Map<String, suspend Scenario.() -> Unit> = mapOf(
    "chat" to {
        agent("e2e", "E2E Agent")
        withoutDefaultAgent()
        task("Plan mobile tests")
    },
    "showcase" to {
        showcase(agent("e2e", "E2E Agent"))
        withoutDefaultAgent()
    },
)

/** Usage: `fixture-server [scenario] [--port=N]`; the port defaults to `KINUVO_FIXTURE_PORT`, then 18081. */
fun main(args: Array<String>): Unit = runBlocking {
    val name = args.firstOrNull { !it.startsWith("--") } ?: "chat"
    val scenario = requireNotNull(SCENARIOS[name]) { "Unknown scenario '$name'; choose one of ${SCENARIOS.keys.joinToString()}" }
    val port = args.firstOrNull { it.startsWith("--port=") }?.substringAfter('=')?.toInt()
        ?: System.getenv("KINUVO_FIXTURE_PORT")?.toInt() ?: DEFAULT_PORT
    val server = TestServer.start(script = { reply(FIXTURE_REPLY) }, scenario = scenario)
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    val url = server.listen(port)
    println("Fixture Server '$name' listening on $url")
    var shown = ""
    while (true) {
        if (server.pairingCode != shown) { shown = server.pairingCode; println("Pairing code: $shown") }
        delay(5_000)
    }
}
