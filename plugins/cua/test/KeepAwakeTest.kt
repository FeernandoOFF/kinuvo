package kinuvo.plugins.cua

import kinuvo.protocol.device.Platform
import kotlin.test.*

class KeepAwakeTest {
    @Test
    fun commandsCoverDesktopPlatforms() {
        assertEquals(listOf("/usr/bin/caffeinate", "-diu", "-t", "15"), keepAwakeCommand(Platform.MacOS))
        val windows = keepAwakeCommand(Platform.Windows)!!
        assertEquals("powershell.exe", windows.first())
        assertContains(windows.last(), "2147483651")
        assertContains(windows.last(), "Start-Sleep -Seconds 15")
        assertEquals(listOf("systemd-inhibit", "--what=idle:sleep", "--why=Kinuvo Computer use", "sleep", "15"), keepAwakeCommand(Platform.Linux) { true })
        assertNull(keepAwakeCommand(Platform.Linux) { false })
        assertNull(keepAwakeCommand(Platform.Android))
    }

    @Test
    fun refreshAndReleaseUseTheInjectedRunner() {
        var time = 0L
        val processes = mutableListOf<FakeProcess>()
        val awake = KeepAwake(Platform.Windows, start = { FakeProcess().also(processes::add) }, now = { time })
        awake.update(true)
        awake.update(true)
        assertEquals(1, processes.size)
        time = 5_000_000_000L
        awake.update(true)
        assertFalse(processes.first().isAlive)
        assertTrue(processes.last().isAlive)
        awake.update(false)
        assertFalse(processes.last().isAlive)
    }

    private class FakeProcess : Process() {
        private var alive = true
        override fun isAlive() = alive
        override fun destroy() { alive = false }
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun getOutputStream() = error("unused")
        override fun getInputStream() = error("unused")
        override fun getErrorStream() = error("unused")
    }
}
