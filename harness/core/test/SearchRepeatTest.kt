package kinuvo.harness.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SearchRepeatTest {
    @Test fun ignoresPunctuationAndStopWords() {
        assertEquals(setOf("ada", "lovelace"), searchWords("""{"query":"Who is Ada Lovelace?"}"""))
        assertTrue(similarSearch(searchWords("""{"query":"Who is Ada Lovelace?"}"""), searchWords("""{"query":"Ada Lovelace"}""")))
        assertTrue(similarSearch(setOf("a", "b", "c"), setOf("a", "b", "c", "d", "e")))
        assertFalse(similarSearch(setOf("a", "b"), setOf("a", "c")))
        assertFalse(similarSearch(emptySet(), emptySet()))
    }
}
