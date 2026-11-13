package kinuvo.server.jvm

import kinuvo.server.plugin.ProcessOutput
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
                    "Web" to buildJsonObject { putJsonObject("mac-mini.tail123.ts.net:$port") { putJsonObject("Handlers") {
                        handlers(port).forEach { (path, handler) -> put(path, handler) }
                        putJsonObject("/") { put("Proxy", args.last()) }
                    } } },
                ))
                ProcessOutput(0, "", "")
            }
            args.takeLast(2) == listOf("--set-path=/", "off") -> {
                val port = args.first { it.startsWith("--https=") }.substringAfter('=')
                val remaining = JsonObject(handlers(port) - "/")
                config = JsonObject(config + mapOf(
                    "TCP" to JsonObject(config["TCP"]!!.jsonObject.filterKeys { it != port || remaining.isNotEmpty() }),
                    "Web" to if (remaining.isEmpty()) JsonObject(config["Web"]!!.jsonObject.filterKeys { !it.endsWith(":$port") })
                    else buildJsonObject { putJsonObject("mac-mini.tail123.ts.net:$port") { put("Handlers", remaining) } },
                ))
                ProcessOutput(0, "", "")
            }
            else -> error("Unexpected Tailscale command")
        }
    }

    private fun handlers(port: String) =
        config["Web"]?.jsonObject?.get("mac-mini.tail123.ts.net:$port")?.jsonObject?.get("Handlers")?.jsonObject.orEmpty()

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
        assertContains(calls, listOf("serve", "--https=8443", "--set-path=/", "off"))
        assertTrue("8787" in config["TCP"]!!.jsonObject)
    }

    @Test fun keepsOtherPathsOnTheSameHttpsPort() = runBlocking {
        val installer = """{"/install/ios/":{"Proxy":"http://127.0.0.1:8790"}}"""
        config = Json.parseToJsonElement("""{"TCP":{"443":{"HTTPS":true}},"Web":{"mac-mini.tail123.ts.net:443":{"Handlers":$installer}}}""").jsonObject
        val route = tailscale.prepare(8788)
        route.enable()
        assertEquals(setOf("/", "/install/ios/"), handlers("443").keys)
        tailscale.prepare(8788).enable()
        assertEquals(1, calls.count { "--bg" in it })
        route.rollback()
        assertEquals(Json.parseToJsonElement(installer), JsonObject(handlers("443")))
        assertTrue("443" in config["TCP"]!!.jsonObject)
    }

    @Test fun refusesOccupiedPortsOtherRootRoutesAndPublicFunnelRoutes() = runBlocking {
        val original = config
        assertFailsWith<ServeException> { tailscale.prepare(8788, 8787) }
        assertEquals(original, config)
        val route = tailscale.prepare(8788)
        route.enable()
        val valid = config
        for (bad in listOf(
            valid.toString().replace("http://127.0.0.1:8788", "http://127.0.0.1:9999"),
            valid.toString().replace("\"Handlers\":{", "\"Handlers\":{\"/install/ios/\":{\"Proxy\":\"http://127.0.0.1:8790\"},")
                .replace("http://127.0.0.1:8788", "http://127.0.0.1:9999"),
            valid.toString().dropLast(1) + ",\"AllowFunnel\":{\"mac-mini.tail123.ts.net:443\":true}}",
        )) {
            config = Json.parseToJsonElement(bad).jsonObject
            assertFailsWith<ServeException> { tailscale.prepare(8788) }
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
            assertFailsWith<ServeException> { tailscale.prepare(8788) }
        }
        assertTrue(calls.none { it.first() == "serve" })
        assertFailsWith<IllegalArgumentException> { tailscale.prepare(0) }
        assertFailsWith<IllegalArgumentException> { Tailscale.requireLoopback("0.0.0.0") }
    }

    @Test fun commandFailuresDoNotExposeRawTailscaleOutput() = runBlocking {
        val failed = Tailscale("tailscale") { ProcessOutput(1, "private output", "tskey-sensitive") }
        val error = assertFailsWith<ServeException> { failed.prepare(8788) }
        assertContains(error.message.orEmpty(), "tailscale status")
        assertFalse(error.message.orEmpty().contains("sensitive"))
    }
}
