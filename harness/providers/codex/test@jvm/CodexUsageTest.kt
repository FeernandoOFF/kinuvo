package kinuvo.harness.codex

import kinuvo.protocol.Usage

import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodexUsageTest {
    @Test
    fun parsesUsageHeaders() {
        val usage = codexUsageFrom(
            headersOf(
                "x-codex-primary-used-percent" to listOf("12.5"),
                "x-codex-primary-window-minutes" to listOf("300"),
                "x-codex-primary-reset-at" to listOf("1790803248"),
                "x-codex-secondary-used-percent" to listOf("1"),
                "x-codex-plan-type" to listOf("team"),
            )
        )!!
        assertEquals("codex", usage.provider)
        assertEquals(Usage.Window("primary", 12.5, 300, 1790803248L), usage.windows.first())
        assertEquals(1.0, usage.windows.last().usedPercent)
        assertEquals("team", usage.plan)
    }

    @Test
    fun noHeadersMeansNoUsage() = assertNull(codexUsageFrom(headersOf()))
}
