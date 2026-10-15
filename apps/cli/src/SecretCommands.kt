package kinuvo.cli

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kinuvo.protocol.*

internal class SecretCommands : Group(
    "secrets", "Connect the Server's password manager for \${pass://Vault/Item/field} references in config.",
    SecretsStatus(), ConnectSecrets(), DisconnectSecrets(), BrowseSecrets(), RefreshSecrets(),
)

private class SecretsStatus : ServerCommand("status", "Show each password manager and whether it's connected.") {
    override suspend fun run() {
        val all = session.call { client.call(Api.SecretProviders, Unit) }
        echo(table(listOf("SCHEME", "NAME", "CONNECTED", "TOKEN IN", "STATUS"), all.map {
            listOf("${it.scheme}://", it.name, if (it.connected) "yes" else "no", it.tokenStorage, it.detail)
        }))
    }
}

private class ConnectSecrets : ServerCommand("connect", "Save an access token and sign in: paste the token when asked, or pipe the AI agent instructions (pbpaste | kinuvo secrets connect).") {
    private val scheme by option("--scheme").default("pass")

    override suspend fun run() {
        val token = System.console()?.readPassword("Access token: ")?.let(::String) ?: generateSequence(::readlnOrNull).joinToString("\n")
        val status = session.call { client.call(Api.ConnectSecrets, ConnectSecretsParams(scheme, token.trim())) }
        echo("${status.name}: ${status.detail}")
    }
}

private class DisconnectSecrets : ServerCommand("disconnect", "Forget the access token and sign out.") {
    private val scheme by option("--scheme").default("pass")

    override suspend fun run() {
        val status = session.call { client.call(Api.DisconnectSecrets, SecretProviderRef(scheme)) }
        echo("${status.name}: disconnected")
    }
}

private class BrowseSecrets : ServerCommand("browse", "List vaults, a vault's items, or an item's fields (names only).") {
    private val path by argument("VAULT [ITEM]").multiple()
    private val scheme by option("--scheme").default("pass")

    override suspend fun run() {
        val entries = session.call { client.call(Api.BrowseSecrets, BrowseSecretsParams(scheme, path)) }
        if (path.size == 2) entries.forEach { echo(secretReference(scheme, path + it.name)) }
        else echo(table(listOf("NAME", "KIND"), entries.map { listOf(it.name, it.kind) }))
    }
}

private class RefreshSecrets : ServerCommand("refresh", "Forget cached secrets so the next use reads them again.") {
    override suspend fun run() {
        session.call { client.call(Api.RefreshSecrets, Unit) }
        echo("Refreshed")
    }
}
