package kinuvo.server.jvm

import kotlinx.io.files.Path

/** `-Dkinuvo.home`, `$KINUVO_HOME`, or `~/.kinuvo`. */
fun kinuvoHome(): Path = (System.getProperty("kinuvo.home") ?: System.getenv("KINUVO_HOME"))?.let(::Path) ?: Path(System.getProperty("user.home"), ".kinuvo")
