package kinuvo.harness.codex

import kinuvo.protocol.Usage

import io.ktor.http.Headers

/** Parses the `x-codex-*` usage headers of a Codex response. */
fun codexUsageFrom(headers: Headers): Usage? {
    fun window(name: String): Usage.Window? {
        val used = headers["x-codex-$name-used-percent"]?.toDoubleOrNull() ?: return null
        return Usage.Window(
            name = name,
            usedPercent = used,
            windowMinutes = headers["x-codex-$name-window-minutes"]?.toIntOrNull() ?: 0,
            resetsAtEpochSeconds = headers["x-codex-$name-reset-at"]?.toLongOrNull(),
        )
    }
    val primary = window("primary")
    val secondary = window("secondary")
    if (primary == null && secondary == null) return null
    return Usage("codex", listOfNotNull(primary, secondary), headers["x-codex-plan-type"])
}
