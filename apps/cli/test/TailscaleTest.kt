package kinuvo.cli

import com.github.ajalt.clikt.core.CliktError
import kinuvo.harness.core.ProcessOutput
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class TailscaleTest {
    private var status = """{"BackendState":"Running","Self":{"DNSName":"mac-mini.tail123.ts.net."},"CertDomains":["mac-mini.tail123.ts.net"]}"""
    private var config = Json.parseToJsonElement("""{"TCP":{"8787":{"TCPForward":"127.0.0.1:8787"}}}""").jsonObject
    private val calls = mutableListOf<List<String>>()
    private val tailscale = Tailscale("tailscale") { command ->
        val args = command.drop(1)
        calls += args
        when {
            args == listOf("status", "--json") -> ProcessOutput(0, status, "")
            args == listOf("serve", "status", "--json") -> ProcessOutput(0, config.toString(), "")
            args.contains("--bg") -> {
                val port = args.first { it.startsWith("--https=") }.substringAfter('=')
                config = JsonObject(config + mapOf(
                    "TCP" to JsonObject(config["TCP"]!!.jsonObject + (port to buildJsonObject { put("HTTPS", true) })),
                    "Web" to Json.parseToJsonElement("""{"mac-mini.tail123.ts.net:$port":{"Handlers":{"/":{"Proxy":"${args.last()}"}}}}"""),
                ))
                ProcessOutput(0, "", "")
            }
            args.last() == "off" -> {
                val port = args.first { it.startsWith("--https=") }.substringAfter('=')
                config = JsonObject(config + mapOf(
                    "TCP" to JsonObject(config["TCP"]!!.jsonObject.filterKeys { it != port }),
                    "Web" to JsonObject(config["Web"]!!.jsonObject.filterKeys { !it.endsWith(":$port") }),
                ))
                ProcessOutput(0, "", "")
            }
            else -> error("Unexpected Tailscale command")
        }
    }

    @Test fun configuresPrivateHttpsWithoutReplacingOtherPortsAndReusesItAfterRestart() = runBlocking {
        val route = tailscale.prepare(8788)
        assertEquals("https://mac-mini.tail123.ts.net", route.url)
        assertTrue(calls.none { "--bg" in it })
        route.enable()
        assertEquals("127.0.0.1:8787", config["TCP"]!!.jsonObject["8787"]!!.jsonObject["TCPForward"]!!.jsonPrimitive.content)
        assertContains(calls, listOf("serve", "--bg", "--https=443", "http://127.0.0.1:8788"))
        val restarted = tailscale.prepare(8788)
        restarted.enable()
        restarted.rollback()
        assertEquals(1, calls.count { "--bg" in it })
        assertTrue(calls.none { it.last() == "off" })
    }

    @Test fun customHttpsPortIsIncludedInTheAddressAndNewRoutesCanBeRolledBack() = runBlocking {
        val route = tailscale.prepare(8788, 8443)
        assertEquals("https://mac-mini.tail123.ts.net:8443", route.url)
        route.enable()
        route.rollback()
        assertContains(calls, listOf("serve", "--https=8443", "off"))
        assertTrue("8787" in config["TCP"]!!.jsonObject)
    }

    @Test fun refusesOccupiedPortsAdditionalPathsAndPublicFunnelRoutes() = runBlocking {
        val original = config
        assertFailsWith<CliktError> { tailscale.prepare(8788, 8787) }
        assertEquals(original, config)
        val route = tailscale.prepare(8788)
        route.enable()
        val valid = config
        for (bad in listOf(
            valid.toString().replace("http://127.0.0.1:8788", "http://127.0.0.1:9999"),
            valid.toString().replace("\"Handlers\":{", "\"Handlers\":{\"/another\":{\"Proxy\":\"http://127.0.0.1:9999\"},"),
            valid.toString().dropLast(1) + ",\"AllowFunnel\":{\"mac-mini.tail123.ts.net:443\":true}}",
        )) {
            config = Json.parseToJsonElement(bad).jsonObject
            assertFailsWith<CliktError> { tailscale.prepare(8788) }
            assertEquals(bad, config.toString())
        }
        assertEquals(1, calls.count { "--bg" in it })
    }

    @Test fun failsBeforeSetupWhenTailscaleOrHttpsIsUnavailable(): Unit = runBlocking {
        for (bad in listOf(
            """{"BackendState":"NeedsLogin"}""",
            """{"BackendState":"Running","Self":{}}""",
            """{"BackendState":"Running","Self":{"DNSName":"mac-mini.tail123.ts.net"},"CertDomains":[]}""",
        )) {
            status = bad
            assertFailsWith<CliktError> { tailscale.prepare(8788) }
        }
        assertTrue(calls.none { it.first() == "serve" })
        assertFailsWith<IllegalArgumentException> { tailscale.prepare(0) }
        assertFailsWith<IllegalArgumentException> { Tailscale.requireLoopback("0.0.0.0") }
    }

    @Test fun commandFailuresDoNotExposeRawTailscaleOutput() = runBlocking {
        val failed = Tailscale("tailscale") { ProcessOutput(1, "private output", "tskey-sensitive") }
        val error = assertFailsWith<CliktError> { failed.prepare(8788) }
        assertContains(error.message.orEmpty(), "tailscale status")
        assertFalse(error.message.orEmpty().contains("sensitive"))
    }
}
