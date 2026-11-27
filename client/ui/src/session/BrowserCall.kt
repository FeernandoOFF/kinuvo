package kinuvo.ui.session

import kinuvo.app.session.LiveCallLevels

/** What to tell the user for a browser's `getUserMedia` failure, by its DOMException [name]. */
internal fun microphoneFailure(name: String?): String = when (name) {
    "NotAllowedError", "PermissionDeniedError", "SecurityError" ->
        "Microphone access is blocked. Allow it for this site in your browser's settings, then try again."
    "NotFoundError", "DevicesNotFoundError", "OverconstrainedError" -> "No microphone found. Connect one and try again."
    "NotReadableError", "TrackStartError", "AbortError" -> "The microphone is in use by another app or tab. Close it and try again."
    else -> "Couldn't start the microphone${name?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}."
}

/** Microphone and speaker loudness from a browser's `getStats` as "input,output", each 0..1. */
internal fun parseLevels(packed: String): LiveCallLevels {
    val (input, output) = packed.split(',').map { it.toFloatOrNull()?.takeIf(Float::isFinite) ?: 0f }.plus(0f).plus(0f)
    return LiveCallLevels(input.coerceIn(0f, 1f), output.coerceIn(0f, 1f))
}
