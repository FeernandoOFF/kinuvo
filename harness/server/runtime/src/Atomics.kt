package kinuvo.harness.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
internal inline fun <T> AtomicReference<T>.update(transform: (T) -> T) {
    while (true) {
        val old = load()
        if (compareAndSet(old, transform(old))) return
    }
}
