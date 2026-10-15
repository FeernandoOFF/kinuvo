package kinuvo.cli

import com.github.ajalt.clikt.core.CliktError
import kinuvo.harness.core.ProcessOutput
import kinuvo.harness.core.runProcess
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI

internal class Tailscale(
    private val executable: String = executable(),
    private val run: suspend (List<String>) -> ProcessOutput = { runProcess(it) },
) {
    suspend fun prepare(localPort: Int, httpsPort: Int = 443): Route {
        require(localPort in 1..65535 && httpsPort in 1..65535) { "Tailscale needs fixed ports between 1 and 65535" }
        val status = json("status", "--json")
        if (status["BackendState"]?.jsonPrimitive?.content != "Running")
            throw CliktError("Tailscale is not connected. Run `tailscale up`, then try again.")
        val dns = status["Self"]?.jsonObject?.get("DNSName")?.jsonPrimitive?.contentOrNull?.trim()?.removeSuffix(".")
            ?.takeIf { it.isNotEmpty() } ?: throw CliktError("Enable MagicDNS and HTTPS certificates in the Tailscale admin console, then try again.")
        val url = URI("https", null, dns, if (httpsPort == 443) -1 else httpsPort, null, null, null).toASCIIString()
        if (status["CertDomains"] is JsonArray && status.getValue("CertDomains").jsonArray.none { it.jsonPrimitive.content == dns })
            throw CliktError("Enable HTTPS certificates at https://login.tailscale.com/admin/dns, then try again.")
        return Route(url, "$dns:$httpsPort", localPort, httpsPort).also { it.exists() }
    }

    inner class Route(val url: String, private val authority: String, localPort: Int, private val httpsPort: Int) {
        private val target = "http://127.0.0.1:$localPort"
        private var created = false

        internal suspend fun exists(): Boolean {
            val config = json("serve", "status", "--json")
            if (config["AllowFunnel"]?.jsonObject?.get(authority)?.jsonPrimitive?.booleanOrNull == true)
                throw CliktError("Tailscale port $httpsPort is exposed through Funnel. Choose a private HTTPS port with --tailscale-port.")
            val tcp = config["TCP"]?.jsonObject?.get(httpsPort.toString())?.jsonObject
            val web = config["Web"]?.jsonObject?.filterKeys { it.endsWith(":$httpsPort") }.orEmpty()
            if (tcp == null && web.isEmpty()) return false
            val handlers = web[authority]?.jsonObject?.get("Handlers")?.jsonObject
            if (tcp?.get("HTTPS")?.jsonPrimitive?.booleanOrNull != true || tcp["TCPForward"] != null ||
                web.size != 1 || handlers?.keys != setOf("/") || handlers["/"]?.jsonObject?.get("Proxy")?.jsonPrimitive?.contentOrNull != target)
                throw CliktError("Tailscale port $httpsPort already serves another route. Choose a free port with --tailscale-port; existing routes were kept.")
            return true
        }

        suspend fun enable() {
            if (exists()) return
            command("serve", "--bg", "--https=$httpsPort", target)
            created = true
            check(exists()) { "Tailscale did not configure the HTTPS route" }
        }

        suspend fun rollback() {
            if (created && exists()) command("serve", "--https=$httpsPort", "off")
            created = false
        }
    }

    private suspend fun json(vararg args: String): JsonObject = try {
        Json.parseToJsonElement(command(*args)).jsonObject
    } catch (e: IllegalArgumentException) {
        throw CliktError("Tailscale returned invalid status data. Update Tailscale and try again.")
    }

    private suspend fun command(vararg args: String): String {
        val result = try {
            withTimeoutOrNull(15_000) { run(listOf(executable) + args) }
        } catch (e: java.io.IOException) {
            throw CliktError("Tailscale CLI is unavailable. Install Tailscale and make its command available on PATH.")
        } ?: throw CliktError("Tailscale ${args.first()} timed out. Check `tailscale status` and enable HTTPS in the Tailscale admin console.")
        if (!result.ok) throw CliktError("Tailscale ${args.first()} failed (${result.code}). Check `tailscale status`, HTTPS certificates and your permission to run `tailscale serve`.")
        return result.out
    }

    companion object {
        fun requireLoopback(host: String) = require(host in setOf("127.0.0.1", "localhost")) { "--tailscale listens on 127.0.0.1; omit --host or use --host 127.0.0.1" }

        private fun executable(): String {
            val name = if (System.getProperty("os.name").startsWith("Windows")) "tailscale.exe" else "tailscale"
            return (System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) } +
                listOf(File("/opt/homebrew/bin/tailscale"), File("/Applications/Tailscale.app/Contents/MacOS/Tailscale")))
                .firstOrNull { it.isFile && it.canExecute() }?.path ?: name
        }
    }
}
