package kinuvo.cli

import io.github.oshai.kotlinlogging.KotlinLogging
import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.types.int
import kinuvo.client.KinuvoClient
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.server.jvmProviders
import kinuvo.harness.server.kinuvoHome
import kinuvo.harness.server.Harness
import kinuvo.harness.server.HarnessServer
import kinuvo.harness.server.NetworkSecurity
import kinuvo.harness.server.LocalServer
import kinuvo.protocol.Links
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone

private val log by lazy { KotlinLogging.logger("kinuvo.cli") }

/** `kinuvo [serve] [--host 0.0.0.0] [--port 8787]`, `kinuvo login`, or a management command (see `--help`). */
fun main(args: Array<String>): Unit = runBlocking {
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    Kinuvo(LocalAccess, listOf(Serve(), Login(), ServiceCommands()), default = { serve("127.0.0.1", HarnessServer.DEFAULT_PORT) }).main(args)
}

private class Serve : CoreSuspendingCliktCommand("serve") {
    private val host by option("--host", help = "Interface to listen on").default("127.0.0.1")
    private val port by option("--port").int().default(HarnessServer.DEFAULT_PORT)
    private val publicUrl by option("--public-url", help = "HTTPS origin advertised by the trusted reverse proxy")
    private val trustedProxy by option("--trusted-proxy", help = "Comma-separated trusted proxy IPs; defaults to loopback")
    private val computer by option("--computer", help = "Expose this desktop as a Computer through cua-driver").flag()
    private val tailscale by option("--tailscale", help = "Provide private HTTPS through Tailscale Serve (requires Tailscale with HTTPS enabled)").flag()
    private val tailscalePort by option("--tailscale-port", help = "Tailscale HTTPS port").int().default(443)

    override fun help(context: Context) = "Run the Server (the default command)."

    override suspend fun run() = serve(host, port, publicUrl, trustedProxy, computer, tailscale, tailscalePort)
}

private class Login : CoreSuspendingCliktCommand("login") {
    private val provider by argument("PROVIDER", help = "Model provider ID; defaults to the only one that signs in").optional()

    override fun help(context: Context) = "Sign in to a model provider with a device code."

    override suspend fun run() {
        val providers = jvmProviders(AgentFiles(kinuvoHome())).flatMap { it.modelProviders }.filter { it.auth != null }
        val target = provider?.let { id -> providers.firstOrNull { it.id == id } ?: throw UsageError("No provider '$id' to sign in to; available: ${providers.joinToString { it.id }}") }
            ?: providers.singleOrNull() ?: throw UsageError("Name a provider: ${providers.joinToString { it.id }}")
        target.auth!!.login { echo("Open ${it.url} and enter code ${it.code} (expires in 15 minutes)") }
        echo("Signed in to ${target.name}.")
    }
}

private suspend fun serve(host: String, port: Int, publicUrl: String? = null, trustedProxy: String? = null, computer: Boolean = false, tailscale: Boolean = false, tailscalePort: Int = 443) {
    require(!tailscale || publicUrl == null) { "Use --tailscale or --public-url, not both" }
    val route = if (tailscale) {
        Tailscale.requireLoopback(host)
        Tailscale().prepare(port, tailscalePort)
    } else null
    val bind = if (tailscale) "127.0.0.1" else host
    val network = NetworkSecurity(route?.url ?: publicUrl ?: System.getenv("KINUVO_PUBLIC_URL"),
        (trustedProxy ?: System.getenv("KINUVO_TRUSTED_PROXIES") ?: "127.0.0.1,::1").split(',').map(String::trim).toSet())
    val server = HarnessServer(Harness(), network = network)
    var desktop: CliComputer? = null
    var ready = false
    val shutdown = Thread { try { server.stop() } finally { desktop?.close() } }
    try {
        val bound = server.start(bind, port)
        route?.enable()
        if (computer) {
            desktop = CliComputer(server, bind, bound)
            desktop.connect()
        }
        server.advertise()
        log.info { "Server listening at $bind:$bound${network.publicUrl?.let { "; HTTPS address $it" }.orEmpty()}" }
        if (route != null) log.info { "Tailscale HTTPS: ${route.url} (private to your tailnet; the Serve route persists until disabled)" }
        if (computer) log.info { "Computer use enabled: Server desktop (cua-driver)." }
        log.info { "Pairing is closed. Run `kinuvo pair --manage` for a management Device or `kinuvo pair` for ordinary access." }
        Runtime.getRuntime().addShutdownHook(shutdown)
        ready = true
        awaitCancellation()
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
        try { server.stop() } finally {
            try { desktop?.close() } finally {
                if (!ready) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { route?.rollback() }.onFailure { log.warn(it) { "Could not remove the new Tailscale route; check `tailscale serve status`." } }
                }
            }
        }
    }
}

/** The Server running on this machine, or else one started just for the command, without firing Routines. */
private object LocalAccess : ServerAccess {
    override suspend fun <T> useRunning(block: suspend (KinuvoClient) -> T): T {
        val local = LocalServer.read(kinuvoHome())?.takeIf { it.alive } ?: error("No Server running; start `kinuvo serve` or the desktop app first")
        val client = connect(local.host, local.port, local.token) ?: error("The running Server isn't reachable at ${local.host}:${local.port}")
        return client.use(block)
    }

    override suspend fun <T> use(block: suspend (KinuvoClient) -> T): T {
        val home = kinuvoHome()
        LocalServer.read(home)?.takeIf { it.alive }?.let { local ->
            val client = connect(local.host, local.port, local.token) ?: error("A Kinuvo server is running (pid ${local.pid}) but isn't reachable at ${local.host}:${local.port}; if it isn't Kinuvo, delete ${LocalServer.file(home)}")
            return client.use(block)
        }
        val server = HarnessServer(Harness(home)).apply { auth.pairable = false }
        try {
            val port = server.start("127.0.0.1", 0, schedule = false)
            return (connect("127.0.0.1", port, server.auth.sessionToken()) ?: error("Couldn't start a local server")).use(block)
        } finally {
            server.stop()
        }
    }

    private suspend fun connect(host: String, port: Int, token: String): KinuvoClient? {
        val client = KinuvoClient("ws://${if (':' in host) "[$host]" else host}:$port/ws", "cli", token, timeZone = TimeZone.getDefault().id)
        return withTimeoutOrNull(3_000) { runCatching { client.connect() }.getOrNull() }?.let { client } ?: null.also { client.close() }
    }

    private suspend fun <T> KinuvoClient.use(block: suspend (KinuvoClient) -> T): T = try { block(this) } finally { close() }
}
