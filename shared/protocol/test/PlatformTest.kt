package kinuvo.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformTest {
    @Test
    fun operatingSystemNamesHaveOneMapping() {
        Platform.entries.forEach { assertEquals(it, Platform.fromName(it.displayName)) }
        listOf("Mac OS X", "macOS", "Darwin").forEach { assertEquals(Platform.MacOS, Platform.fromName(it)) }
        listOf("Windows 10", "Windows 11", "windows server 2025").forEach { assertEquals(Platform.Windows, Platform.fromName(it)) }
        listOf("", "FreeBSD", "Plan 9").forEach { assertEquals(Platform.Unknown, Platform.fromName(it)) }
    }
}
