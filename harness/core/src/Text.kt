package kinuvo.harness.core

/** The first [n] characters, one fewer when the cut would split a surrogate pair. */
internal fun String.takeWhole(n: Int): String = take(if (n in 1 until length && this[n - 1].isHighSurrogate()) n - 1 else n)
