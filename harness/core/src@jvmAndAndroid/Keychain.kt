package kinuvo.harness.core

import java.io.File

internal actual fun systemKeychain(service: String, account: String): Keychain? {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        "mac" in os -> MacKeychain(service, account)
        "linux" in os && executable("secret-tool") != null -> SecretTool(service, account, executable("secret-tool")!!)
        else -> null
    }
}

private fun executable(name: String) = System.getenv("PATH").orEmpty().split(File.pathSeparator)
    .map { File(it, name) }.firstOrNull { it.canExecute() }?.path

/** A generic password in the login keychain, written through `security -i` so the token never shows in a process list. */
private class MacKeychain(private val service: String, private val account: String) : Keychain {
    override val name = "macOS Keychain"

    override suspend fun read() = runProcess(listOf(SECURITY, "find-generic-password", "-s", service, "-a", account, "-w"))
        .takeIf { it.ok }?.out?.trimEnd('\n')?.ifEmpty { null }

    override suspend fun write(token: String) {
        require(token.none { it == '"' || it == '\\' || it.isWhitespace() }) { "The token has characters it can't have" }
        runProcess(listOf(SECURITY, "-i"), stdin = "add-generic-password -U -s \"$service\" -a \"$account\" -w \"$token\"\n").value("security")
        check(read() == token) { "Couldn't save the token to the macOS Keychain" }
    }

    override suspend fun delete() {
        runProcess(listOf(SECURITY, "delete-generic-password", "-s", service, "-a", account))
    }

    private companion object {
        const val SECURITY = "/usr/bin/security"
    }
}

private class SecretTool(private val service: String, private val account: String, private val tool: String) : Keychain {
    override val name = "Secret Service"

    override suspend fun read() = runProcess(listOf(tool, "lookup", "service", service, "account", account))
        .takeIf { it.ok }?.out?.trimEnd('\n')?.ifEmpty { null }

    override suspend fun write(token: String) {
        runProcess(listOf(tool, "store", "--label=Kinuvo $account", "service", service, "account", account), stdin = token).value("secret-tool")
    }

    override suspend fun delete() {
        runProcess(listOf(tool, "clear", "service", service, "account", account))
    }
}
