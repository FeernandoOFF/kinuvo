package kinuvo.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class ReflectionMemoryChangeTest {
    @Test
    fun savedReflectionRetainsMemoryDiffs() {
        val entry = SessionEntry.Reflection("r1", 123, ReflectionResult(memory = 1, memoryChanges = listOf(
            ReflectionMemoryChange("memory", "- Old lesson\n", "- New lesson\n"),
        )))
        val text = ProtocolJson.encodeToString(SessionEntry.serializer(), entry)
        assertEquals(entry, ProtocolJson.decodeFromString(SessionEntry.serializer(), text))
    }

    @Test
    fun reflectionWithoutChangesHasNoMemoryDiffs() {
        val result = ProtocolJson.decodeFromString(ReflectionResult.serializer(), """{"memory":1,"userNotes":2}""")
        assertEquals(emptyList(), result.memoryChanges)
        assertEquals(3, result.total)
    }
}
