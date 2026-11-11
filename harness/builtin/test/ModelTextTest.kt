package kinuvo.harness.builtin

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelTextTest {
    @Test
    fun takeWholeNeverSplitsASurrogatePair() {
        assertEquals("a", "a😀b".takeWhole(2))
        assertEquals("a😀", "a😀b".takeWhole(3))
        assertEquals("a😀b", "a😀b".takeWhole(10))
        assertEquals("", "😀".takeWhole(1))
    }
}
