package kinuvo.harness.litert

/** This machine's total RAM in bytes, or null when unknown. */
internal expect fun deviceMemory(): Long?

/** Why LiteRT-LM can't run on this machine, or null when it can. */
internal expect fun unsupportedReason(): String?
