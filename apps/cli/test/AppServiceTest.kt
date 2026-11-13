package kinuvo.cli

import kinuvo.server.jvm.LoginServiceSettings
import kinuvo.testing.harness.TemporaryDirectories
import com.github.ajalt.clikt.core.CliktError
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppServiceTest {
    private val temporaryDirectories = TemporaryDirectories()

    @AfterTest
    fun deleteTemporaryDirectories() = temporaryDirectories.close()

    private val home = temporaryDirectories.create("app-service").toFile()
    private val calls = mutableListOf<List<String>>()
    private var status = "notRegistered"
    private val spec = ServiceSpec("", "", "0.0.0.0", 8788, "${home.path}/.kinuvo/logs/server.log", mapOf("KINUVO_PUBLIC_URL" to "https://kinuvo.example.com"))

    private fun app(agent: Boolean = true) = File(home, "Applications/Kinuvo.app").also { app ->
        File(app, AppService.EXECUTABLE).apply { parentFile.mkdirs(); writeText("#!/bin/sh"); setExecutable(true) }
        if (agent) File(app, AppService.AGENT).apply { parentFile.mkdirs(); writeText("<plist/>") }
    }

    private fun service() = AppService(app(), home) { command ->
        calls += command
        when (command.first()) {
            "id" -> Sh(0, "501\n")
            "launchctl" -> Sh(1, "")
            else -> when (command.last()) {
                "status" -> Sh(0, "$status\n")
                "register" -> Sh(0, "").also { status = "enabled" }
                else -> Sh(0, "").also { status = "notRegistered" }
            }
        }
    }

    @Test
    fun recordsTheServiceSettingsForTheApp() {
        val rendered = service().render(spec)
        assertContains(rendered, "\"home\":\"${home.path}/.kinuvo\"")
        assertContains(rendered, "\"port\":8788")
        assertContains(rendered, "\"host\":\"0.0.0.0\"")
        assertContains(rendered, "\"publicUrl\":\"https://kinuvo.example.com\"")
    }

    @Test
    fun installRegistersTheBundledAgentAndUninstallRemovesIt() {
        val service = service()
        service.install(spec)
        assertEquals(spec.home, LoginServiceSettings.read(home.path)?.home)
        assertEquals(8788, LoginServiceSettings.read(home.path)?.options?.port)
        assertEquals(listOf(File(home, "Applications/Kinuvo.app/${AppService.EXECUTABLE}").path, "--service", "register"), calls.single())
        assertTrue(service.installed())

        service.uninstall()
        assertFalse(service.installed())
        assertNull(LoginServiceSettings.read(home.path))
    }

    @Test
    fun installRemovesAnOldStyleAgentFirst() {
        val old = Launchd(home)
        old.file.apply { parentFile.mkdirs(); writeText("<plist/>") }
        status = "enabled"
        val service = service()
        assertFalse(service.installed())
        service.install(spec)
        assertFalse(old.file.exists())
        assertTrue(calls.any { it.take(2) == listOf("launchctl", "bootout") })
        assertTrue(service.installed())
    }

    @Test
    fun waitsForApprovalInsteadOfReportingARunningServer() {
        status = "requiresApproval"
        val service = service()
        assertTrue(service.installed())
        assertEquals("waiting for your approval", service.state())
        assertContains(service.bootHint().orEmpty(), "Login Items")
        assertEquals(1, calls.size)
        status = "notRegistered"
        val removed = service()
        assertFalse(removed.installed())
        assertEquals("not installed", removed.state())
    }

    @Test
    fun reportsAFailedUnregister() {
        val failing = AppService(app(), home) { Sh(1, "Operation not permitted\n") }
        assertEquals("Operation not permitted", assertFailsWith<CliktError> { failing.uninstall() }.message)
    }

    @Test
    fun findsOnlyAnAppThatBundlesTheAgent() {
        val system = File(home, "system")
        val unknown = { _: List<String> -> Sh(1, "") }
        assertNull(DesktopApp.find(home, system, unknown))
        app(agent = false)
        assertNull(DesktopApp.find(home, system, unknown))
        val installed = app()
        assertEquals(installed, DesktopApp.find(home, system, unknown))

        val elsewhere = File(home, "elsewhere/Kinuvo.app")
        elsewhere.parentFile.mkdirs()
        installed.renameTo(elsewhere)
        assertNull(DesktopApp.find(home, system, unknown))
        assertEquals(elsewhere, DesktopApp.find(home, system) { Sh(0, "${elsewhere.path}\n") })
    }
}
