package kinuvo.harness.core

import kinuvo.harness.core.store.readTextOrNull
import kinuvo.harness.core.store.writeAtomically
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.util.Base64

/** DPAPI protects tokens for the current Windows user; only encrypted bytes reach the filesystem. */
internal class WindowsKeychain(
    private val path: Path,
    private val execute: suspend (List<String>, String) -> ProcessOutput = { command, input -> runProcess(command, stdin = input) },
) : Keychain {
    override val name = "Windows secure storage"

    override suspend fun read(): String? = path.readTextOrNull()?.let { crypt(it, protect = false) }

    override suspend fun write(token: String) = writeAtomically(path, crypt(token, protect = true))

    override suspend fun delete() = SystemFileSystem.delete(path, mustExist = false)

    private suspend fun crypt(value: String, protect: Boolean): String {
        val operation = if (protect) "Protect" else "Unprotect"
        val script = "\$ErrorActionPreference = 'Stop'; Add-Type -AssemblyName System.Security; " +
            "\$bytes = [Convert]::FromBase64String([Console]::In.ReadToEnd()); " +
            "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::$operation(\$bytes, \$null, [Security.Cryptography.DataProtectionScope]::CurrentUser))"
        val input = if (protect) Base64.getEncoder().encodeToString(value.encodeToByteArray()) else value
        val result = execute(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), input)
        check(result.ok && result.out.isNotBlank()) { "Windows secure storage is unavailable; the token could not be ${if (protect) "saved" else "read"}" }
        val encoded = result.out.trim()
        val bytes = Base64.getDecoder().decode(encoded)
        return if (protect) encoded else bytes.decodeToString()
    }
}
