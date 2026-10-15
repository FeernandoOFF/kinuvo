package kinuvo.harness.server

import kotlinx.io.files.Path

/** `$KINUVO_HOME`, or `~/.kinuvo`. */
fun kinuvoHome(): Path = System.getenv("KINUVO_HOME")?.let(::Path) ?: Path(System.getProperty("user.home"), ".kinuvo")
