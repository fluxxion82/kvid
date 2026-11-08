package com.kvid.embed

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Test seams shared by the platform backends; internal, so no app can reach them. Both backends
 * increment [live] when they are created and decrement it when they are closed, and invoke [insideRun]
 * inside every inference after it registered itself (JVM and Android: holding the read lock; iOS:
 * counted as active), so a test can hold an inference in flight.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object BackendHooks {
    /** Backends created and not yet closed in this process; tests check that failed opens leak none. */
    val live = AtomicInt(0)

    /** Called inside each inference; null outside tests. */
    val insideRun = AtomicReference<(() -> Unit)?>(null)
}
