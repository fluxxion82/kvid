package com.kvid.embed

import kotlinx.coroutines.delay
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.milliseconds

/** The phases [PhaseSampler] attributes memory readings to. */
internal enum class Phase { OPEN, EMBED, INDEX, PAUSE, QUERIES, EDITS, CLOSE, IDLE }

/**
 * Memory over a measured lifecycle, by phase. Two kinds of reading go into the same peaks:
 * - [run] samples [read] ([memoryKb] by default) every 100 ms until it is cancelled;
 * - [reading] takes one explicit reading (before open, after open, at the end of each phase, after close).
 *
 * Every reading raises the lifecycle peak, so `mem_lifecycle_peak_kb` is at least every other memory value
 * a measurement prints. A reading also raises the peak of its phase, unless it is a sample during which the
 * phase changed: the phase is read on both sides of [read], and a sample that spans a change counts for the
 * lifecycle only (`mem_boundary_samples`). Explicit readings are taken by the code that sets [phase], so they
 * never span a change.
 *
 * [phase] has one writer (the measurement); [run] and [reading] may run on different threads.
 */
internal class PhaseSampler(private val read: () -> Long? = ::memoryKb) {
    // The phase in the low 4 bits, a change counter above them: equal before and after a read means no change.
    private val state = AtomicInt(Phase.OPEN.ordinal)
    private val peaks = Array(Phase.entries.size) { AtomicLong(0L) }
    private val readings = Array(Phase.entries.size) { AtomicInt(0) }
    private val entered = Array(Phase.entries.size) { AtomicInt(if (it == Phase.OPEN.ordinal) 1 else 0) }
    private val lifecycle = AtomicLong(0L)
    private val samples = AtomicInt(0)
    private val boundary = AtomicInt(0)

    var phase: Phase
        get() = Phase.entries[state.load() and PHASE_MASK]
        set(value) {
            entered[value.ordinal].store(1)
            state.store((((state.load() ushr PHASE_BITS) + 1) shl PHASE_BITS) or value.ordinal)
        }

    /** Samples every 100 ms until cancelled; returns at once where memory is not measured. */
    suspend fun run() {
        while (true) {
            val before = state.load()
            val kb = read() ?: return
            val after = state.load()
            samples.incrementAndFetch()
            raise(lifecycle, kb)
            if (before == after) record(before and PHASE_MASK, kb) else boundary.incrementAndFetch()
            delay(100.milliseconds)
        }
    }

    /** One explicit reading in the current phase; null where memory is not measured. */
    fun reading(): Long? {
        val kb = read() ?: return null
        raise(lifecycle, kb)
        record(state.load() and PHASE_MASK, kb)
        return kb
    }

    /** The highest reading of [phase], or 0 if it has none. */
    fun peak(phase: Phase): Long = peaks[phase.ordinal].load()

    /**
     * One `mem_<phase>_peak_kb` field for each of [phases], in that order: the peak, `none` for a phase entered
     * without a reading, or `not-run` for a phase never entered. Then the lifecycle peak and the sample counts.
     * Call it after [run] has been cancelled and joined.
     */
    fun summary(phases: List<Phase>): String =
        phases.joinToString(" ") { p ->
            val value = when {
                entered[p.ordinal].load() == 0 -> "not-run"
                readings[p.ordinal].load() == 0 -> "none"
                else -> peak(p).toString()
            }
            "mem_${p.name.lowercase()}_peak_kb=$value"
        } + " mem_lifecycle_peak_kb=${lifecycle.load()} mem_samples=${samples.load()} mem_boundary_samples=${boundary.load()}"

    private fun record(ordinal: Int, kb: Long) {
        readings[ordinal].incrementAndFetch()
        raise(peaks[ordinal], kb)
    }

    private fun raise(peak: AtomicLong, kb: Long) {
        while (true) {
            val seen = peak.load()
            if (kb <= seen || peak.compareAndSet(seen, kb)) return
        }
    }

    private companion object {
        const val PHASE_BITS = 4
        const val PHASE_MASK = (1 shl PHASE_BITS) - 1
    }
}

/** How the memory fields are obtained, the same on both measurement lines (`mem_peak=`). */
internal const val MEMORY_METHOD = "sampled-100ms+readings-from-before-open"
