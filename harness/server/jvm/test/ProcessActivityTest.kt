package kinuvo.harness.server

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessActivityTest {
    @Test fun holdsTheActivityUntilEveryHolderReleasesIt() {
        val reason = "Testing process activity ${ProcessHandle.current().pid()}"
        val activity = ProcessActivity(reason)
        val macOS = System.getProperty("os.name").startsWith("Mac")
        fun held() = ProcessBuilder("pmset", "-g", "assertions").start().inputStream.bufferedReader().readText().contains(reason)
        repeat(2) {
            val first = activity.hold()
            val second = activity.hold()
            if (macOS) assertEquals(true, held())
            first.close(); first.close()
            if (macOS) assertEquals(true, held())
            second.close()
            if (macOS) assertEquals(false, held())
        }
    }
}
