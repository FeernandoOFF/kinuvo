package kinuvo.protocol.approval

import kotlinx.serialization.Serializable

/** What an Approval's call would change: each field before and after in [changes], and [body] for content the user reads whole, e.g. a hidden Session's result. */
@Serializable
data class ChangePreview(val title: String, val changes: List<FieldChange> = emptyList(), val body: String? = null, val note: String? = null) {
    /** The preview as plain text, for Approvals shown outside Kinuvo's own apps. */
    fun text(): String = buildString {
        append(if (changes.isEmpty() && body == null) "$title: no changes." else "$title:")
        changes.forEach { append("\n- ${it.field}: ${show(it.before)} → ${show(it.after)}") }
        body?.let { append("\n").append(it) }
        note?.let { append("\n").append(it) }
    }

    private fun show(value: String?): String {
        val text = value?.trim().orEmpty()
        return when {
            text.isEmpty() -> "(none)"
            '\n' !in text && text.length <= SHORT -> text
            else -> "\"" + text.take(EXCERPT).replace('\n', ' ') + (if (text.length > EXCERPT) "…" else "") + "\" (${text.length} characters)"
        }
    }

    private companion object {
        const val SHORT = 120
        const val EXCERPT = 400
    }
}

/** One field of a [ChangePreview]; a null [before] is a new value, a null [after] a removed one. */
@Serializable
data class FieldChange(val field: String, val before: String? = null, val after: String? = null)
