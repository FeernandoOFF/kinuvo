package kinuvo.protocol.reflection

import kinuvo.protocol.ProtocolJson
import kotlin.test.Test
import kotlin.test.assertEquals

class ReflectionMemoryChangeTest {
    @Test
    fun reflectionWithoutChangesHasNoMemoryDiffs() {
        val result = ProtocolJson.decodeFromString(ReflectionResult.serializer(), """{"memory":1,"userNotes":2}""")
        assertEquals(emptyList(), result.memoryChanges)
        assertEquals(3, result.total)
    }
}
