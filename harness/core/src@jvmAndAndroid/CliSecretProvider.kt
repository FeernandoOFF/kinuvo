package kinuvo.harness.core

import kinuvo.protocol.SecretEntry
import kinuvo.protocol.SecretProviderStatus
import java.io.File
import java.io.IOException

/**
 * A password manager reached through its [cli] on the Server, signed in with a token kept in the OS [keychain], else read
 * from [tokenVariable] in the Server home's `.env` (through [env], Kinuvo's `.env` files, which aren't passed on to other
 * processes). Tokens go to the CLI through its environment, never its arguments.
 */
abstract class CliSecretProvider(
    home: File,
    protected val cli: String,
    private val keychain: Keychain?,
    private val env: () -> Map<String, String>,
) : SecretProvider {
    /** The `.env` variable holding the token where there's no keychain, e.g. `OP_SERVICE_ACCOUNT_TOKEN`. */
    protected abstract val tokenVariable: String
    /** How to install [cli] on the Server. */
    protected abstract val install: String
    /** Where to create a token, shown until one is added. */
    protected abstract val tokenHelp: String
    /** How many names a reference has, e.g. vault, item and field. */
    protected open val levels = 3

    /** Checks [token] signs in, before it's kept. */
    protected abstract suspend fun verify(token: String)
    /** What browsing the top level found, e.g. "3 vaults shared with Kinuvo". */
    protected abstract fun found(entries: List<SecretEntry>): String
    /** The token in what the user pasted. */
    protected open fun parse(pasted: String): String = pasted.trim().also {
        require(it.isNotEmpty() && it.none(Char::isWhitespace)) { "Paste the access token" }
    }
    protected open suspend fun signOut() {}

    private val envFile = File(home, ".env")
    private val tool = cli.substringAfterLast('/')

    override suspend fun status(): SecretProviderStatus {
        val status = SecretProviderStatus(scheme, name, tokenStorage = keychain?.name ?: "$tokenVariable in $envFile", levels = levels)
        if (!installed()) return status.copy(detail = "Install $tool on the Server: $install")
        if (stored() == null) return status.copy(installed = true, detail = tokenHelp)
        return runCatching { browse(emptyList()) }.fold(
            { status.copy(installed = true, connected = true, detail = "Signed in; ${found(it)}") },
            { status.copy(installed = true, connected = true, detail = it.message.orEmpty()) },
        )
    }

    override suspend fun connect(token: String) {
        val parsed = parse(token)
        val keychain = checkNotNull(keychain) { "This Server has no keychain; set $tokenVariable in $envFile instead" }
        check(installed()) { "Install $tool on the Server first: $install" }
        verify(parsed)
        keychain.write(parsed)
    }

    override suspend fun disconnect() {
        keychain?.delete()
        check(env()[tokenVariable].isNullOrBlank()) { "Remove $tokenVariable from $envFile to disconnect" }
        signOut()
    }

    protected suspend fun stored() = keychain?.read() ?: env()[tokenVariable]?.takeIf { it.isNotBlank() }

    protected suspend fun token() = stored() ?: error("$name isn't connected; connect it with an access token")

    /** [cli] with [args] and [env] added to its environment; fails when it isn't installed. */
    protected suspend fun exec(args: List<String>, env: Map<String, String> = emptyMap()): ProcessOutput = try {
        runProcess(listOf(cli) + args, env)
    } catch (e: IOException) {
        throw IllegalStateException("Install $tool on the Server: $install", e)
    }

    private suspend fun installed() = try {
        runProcess(listOf(cli, "--version")).ok
    } catch (_: IOException) {
        false
    }

    companion object {
        /** The first of [paths] that can run, else [name] from the `PATH`. */
        fun executable(name: String, vararg paths: String) = paths.firstOrNull { File(it).canExecute() } ?: name
    }
}
