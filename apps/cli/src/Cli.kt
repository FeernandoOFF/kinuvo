package kinuvo.cli

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import kinuvo.client.KinuvoClient
import kinuvo.protocol.Api

/** Opens a connection to the Server for one command and closes it afterwards. */
interface ServerAccess {
    suspend fun <T> use(block: suspend (KinuvoClient) -> T): T
    suspend fun <T> useRunning(block: suspend (KinuvoClient) -> T): T = use(block)
}

/**
 * `kinuvo [-a AGENT] <command>`, shaped after the Hermes CLI. [commands] are the app's own next to the management ones;
 * [default] runs when no command is given.
 */
class Kinuvo(
    private val access: ServerAccess,
    commands: List<CoreSuspendingCliktCommand> = emptyList(),
    private val default: (suspend () -> Unit)? = null,
) : CoreSuspendingCliktCommand("kinuvo") {
    private val agent by option("-a", "--agent", envvar = "KINUVO_AGENT", help = "Agent to manage (default: the first one)")

    override val invokeWithoutSubcommand = true

    override fun help(context: Context) = "Kinuvo, a self-hosted personal assistant. Manages the Server running on this machine."

    override fun aliases() = mapOf("cron" to listOf("routines"), "profile" to listOf("agents"))

    init {
        versionOption(Kinuvo::class.java.getResource("/kinuvo-version")?.readText() ?: "dev")
        subcommands(commands +listOf(AcpCommand(), AgentCommands(), RoutineCommands(), SkillCommands(), McpCommands(), KnowledgeCommands(), TaskCommands(), SecretCommands(), NotifyCommand(), PairCommand(), DeviceCommands()))
    }

    override suspend fun run() {
        currentContext.findOrSetObject { Session(access, agent) }
        if (currentContext.invokedSubcommand == null) default?.invoke() ?: echoFormattedHelp()
    }
}

/** What every management command shares: the connection and the Agent it acts on. */
internal class Session(private val access: ServerAccess, private val agent: String?) {
    suspend fun <T> call(running: Boolean = false, block: suspend Calls.() -> T): T = try {
        val call: suspend (KinuvoClient) -> T = { Calls(it, agent).block() }
        if (running) access.useRunning(call) else access.use(call)
    } catch (e: IllegalStateException) {
        throw CliktError(e.message ?: e.toString())
    }
}

internal class Calls(val client: KinuvoClient, private val agent: String?) {
    suspend fun agentId(): String = agent ?: client.call(Api.Agents, Unit).firstOrNull()?.id ?: error("The Server has no Agents")
}

/** A command that talks to the Server. */
internal abstract class ServerCommand(name: String, private val about: String) : CoreSuspendingCliktCommand(name) {
    protected val session by requireObject<Session>()

    override fun help(context: Context) = about
}

/** A group of commands, e.g. `routines`. */
internal abstract class Group(name: String, private val about: String, vararg commands: CoreSuspendingCliktCommand) : CoreSuspendingCliktCommand(name) {
    init {
        subcommands(*commands)
    }

    override fun help(context: Context) = about

    override suspend fun run() = Unit
}
