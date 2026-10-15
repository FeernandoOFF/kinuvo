package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.protocol.Platform
import kinuvo.protocol.currentPlatform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

internal class DesktopCredentials(private val dir: File) : DeviceCredentials {
    private val platform = currentPlatform
    private val cache = mutableMapOf<String, String?>()
    private fun account(server: String) = MessageDigest.getInstance("SHA-256").digest(server.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun file(server: String) = File(dir, "device-${account(server)}.enc")
    @Synchronized
    override fun read(server: String): String? {
        if (server in cache) return cache[server]
        val account = account(server)
        val token = when (platform) {
            Platform.MacOS -> process(listOf("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", account, "-w"), missing = 44)
            Platform.Linux -> process(listOf("secret-tool", "lookup", "service", SERVICE, "account", account), missing = 1)
            Platform.Windows -> file(server).takeIf { it.isFile }?.let { dpapi(it.readText(), protect = false) }
            else -> error("This platform has no supported Device credential store")
        }?.trimEnd('\n', '\r')?.ifEmpty { null }
        cache[server] = token
        return token
    }
    @Synchronized
    override fun write(server: String, token: String) {
        if (read(server) == token) return
        val account = account(server)
        when (platform) {
            Platform.MacOS -> {
                require(token.all { it.isLetterOrDigit() || it in "-_." }) { "Invalid Device credential" }
                process(listOf("/usr/bin/security", "-i"), "add-generic-password -U -s \"$SERVICE\" -a \"$account\" -w \"$token\"\n")
            }
            Platform.Linux -> process(listOf("secret-tool", "store", "--label=Kinuvo Device", "service", SERVICE, "account", account), token)
            Platform.Windows -> privateWrite(file(server), requireNotNull(dpapi(token, protect = true)))
            else -> error("This platform has no supported Device credential store")
        }
        cache.remove(server)
        check(read(server) == token) { "Couldn't save the Device credential to secure storage" }
    }
    @Synchronized
    override fun delete(server: String) {
        val account = account(server)
        when (platform) {
            Platform.MacOS -> process(listOf("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", account), missing = 44)
            Platform.Linux -> process(listOf("secret-tool", "clear", "service", SERVICE, "account", account), missing = 1)
            Platform.Windows -> check(!file(server).exists() || file(server).delete()) { "Couldn't delete the Device credential" }
            else -> error("This platform has no supported Device credential store")
        }
        cache.remove(server)
    }
    private fun dpapi(value: String, protect: Boolean): String? {
        val operation = if (protect) "Protect" else "Unprotect"
        val script = "Add-Type -AssemblyName System.Security; " +
            "\$bytes = [Convert]::FromBase64String([Console]::In.ReadToEnd()); " +
            "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::$operation(\$bytes, \$null, [Security.Cryptography.DataProtectionScope]::CurrentUser))"
        val input = if (protect) Base64.getEncoder().encodeToString(value.toByteArray()) else value
        val result = requireNotNull(process(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), input)).trim()
        return if (protect) result else String(Base64.getDecoder().decode(result))
    }
    private fun process(command: List<String>, input: String? = null, missing: Int? = null): String? {
        val process = try { ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start() }
        catch (e: java.io.IOException) { error("Secure Device storage is unavailable; Linux requires secret-tool and an unlocked Secret Service") }
        process.outputStream.use { if (input != null) it.write(input.toByteArray()) }
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Secure Device storage timed out") }
        if (process.exitValue() == missing) return null
        check(process.exitValue() == 0) { "Secure Device storage is locked or unavailable" }
        return process.inputStream.bufferedReader().readText()
    }
    private companion object { const val SERVICE = "dev.kinuvo.devices" }
}

internal fun privateWrite(file: File, value: String) {
    file.parentFile.mkdirs()
    val temp = Files.createTempFile(file.parentFile.toPath(), "preferences", ".tmp")
    try {
        if (Files.getFileStore(temp).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"))
        Files.writeString(temp, value)
        Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temp) }
}
