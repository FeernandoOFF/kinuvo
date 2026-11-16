package kinuvo.server

import kinuvo.harness.ModelPolicy
import kinuvo.server.providers.checkModelSources
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelSourceIdsTest {
    private fun source(id: String) = object : ModelPolicy { override val id = id }

    @Test fun uniqueIdsPass() = checkModelSources(listOf(source("openai"), source("custom/local")))

    @Test fun aRepeatedIdFailsNamingIt() {
        val error = assertFailsWith<IllegalArgumentException> { checkModelSources(listOf(source("openai"), source("custom/local"), source("openai"))) }
        assertTrue("openai" in error.message.orEmpty())
    }
}
