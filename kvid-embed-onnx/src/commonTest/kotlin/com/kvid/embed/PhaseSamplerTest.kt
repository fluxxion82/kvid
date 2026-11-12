@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kvid.embed

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The memory sampler of the measurement tests, on virtual time with fake readings (the JVM measures no memory). */
class PhaseSamplerTest {
    @Test fun samplesGoToThePhaseCurrentAtEachSample() = runTest {
        var reading = 100L
        val sampler = PhaseSampler(read = { reading })
        val sampling = launch { sampler.run() }
        runCurrent()                       // t = 0: OPEN samples 100
        reading = 300L
        advanceTimeBy(100); runCurrent()   // t = 100: OPEN samples 300
        sampler.phase = Phase.INDEX
        reading = 250L
        advanceTimeBy(100); runCurrent()   // t = 200: INDEX samples 250
        reading = 500L
        advanceTimeBy(100); runCurrent()   // t = 300: INDEX samples 500
        sampler.phase = Phase.CLOSE
        reading = 120L
        advanceTimeBy(100); runCurrent()   // t = 400: CLOSE samples 120
        sampling.cancel()

        assertEquals(300L, sampler.peak(Phase.OPEN))
        assertEquals(500L, sampler.peak(Phase.INDEX))
        assertEquals(120L, sampler.peak(Phase.CLOSE))
        assertEquals(
            "mem_open_peak_kb=300 mem_index_peak_kb=500 mem_close_peak_kb=120 mem_lifecycle_peak_kb=500 mem_samples=5 mem_boundary_samples=0",
            sampler.summary(listOf(Phase.OPEN, Phase.INDEX, Phase.CLOSE))
        )
    }

    @Test fun aSampleDuringWhichThePhaseChangesCountsForTheLifecycleOnly() = runTest {
        var reading = 100L
        var duringRead: (() -> Unit)? = null
        lateinit var sampler: PhaseSampler
        // A slow read: the measurement moves to the next phase while memory is being read.
        sampler = PhaseSampler(read = { duringRead?.invoke(); duringRead = null; reading })
        val sampling = launch { sampler.run() }
        runCurrent()                       // t = 0: OPEN samples 100
        reading = 900L
        duringRead = { sampler.phase = Phase.EMBED }
        advanceTimeBy(100); runCurrent()   // t = 100: the read spans OPEN -> EMBED
        assertEquals(100L, sampler.peak(Phase.OPEN), "not credited to the phase before the change")
        assertEquals(0L, sampler.peak(Phase.EMBED), "nor to the phase after it")
        assertEquals(
            "mem_open_peak_kb=100 mem_embed_peak_kb=none mem_lifecycle_peak_kb=900 mem_samples=2 mem_boundary_samples=1",
            sampler.summary(listOf(Phase.OPEN, Phase.EMBED))
        )
        reading = 400L
        advanceTimeBy(100); runCurrent()   // t = 200: EMBED samples 400, a stable read
        sampling.cancel()
        assertEquals(400L, sampler.peak(Phase.EMBED))
        assertTrue(sampler.summary(listOf(Phase.OPEN, Phase.EMBED)).contains("mem_lifecycle_peak_kb=900 mem_samples=3 mem_boundary_samples=1"))
    }

    @Test fun explicitReadingsEnterThePhaseAndLifecyclePeaks() = runTest {
        var reading = 50L
        val sampler = PhaseSampler(read = { reading })
        assertEquals(50L, sampler.reading())  // before the open, in OPEN
        val sampling = launch { sampler.run() }
        reading = 200L
        runCurrent()                           // t = 0: OPEN samples 200
        sampler.phase = Phase.INDEX
        reading = 400L
        advanceTimeBy(100); runCurrent()       // t = 100: INDEX samples 400
        reading = 700L
        assertEquals(700L, sampler.reading())  // after indexing: above every sample
        sampler.phase = Phase.CLOSE
        reading = 80L
        assertEquals(80L, sampler.reading())   // after close: a phase too short for any sample
        sampling.cancel()

        assertEquals(
            "mem_open_peak_kb=200 mem_index_peak_kb=700 mem_pause_peak_kb=not-run mem_close_peak_kb=80 " +
                "mem_lifecycle_peak_kb=700 mem_samples=2 mem_boundary_samples=0",
            sampler.summary(listOf(Phase.OPEN, Phase.INDEX, Phase.PAUSE, Phase.CLOSE))
        )
    }

    @Test fun aPhaseEnteredWithoutAReadingSaysSo() {
        val sampler = PhaseSampler(read = { 10L })
        sampler.reading()
        sampler.phase = Phase.EDITS
        sampler.phase = Phase.CLOSE
        sampler.reading()
        assertEquals(
            "mem_open_peak_kb=10 mem_edits_peak_kb=none mem_queries_peak_kb=not-run mem_close_peak_kb=10 " +
                "mem_lifecycle_peak_kb=10 mem_samples=0 mem_boundary_samples=0",
            sampler.summary(listOf(Phase.OPEN, Phase.EDITS, Phase.QUERIES, Phase.CLOSE))
        )
    }

    @Test fun withoutAMeasurementNothingIsRecorded() = runTest {
        var reads = 0
        val sampler = PhaseSampler(read = { reads++; null })
        assertNull(sampler.reading())
        sampler.run()  // returns at its first read
        assertEquals(2, reads)
        assertTrue(Phase.entries.all { sampler.peak(it) == 0L })
    }

    @Test fun queryOrdersAreAWilliamsSquare() {
        val rows = QUERY_ORDERS.map { it.toList() }
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.sorted() == listOf(0, 1, 2, 3) }, "each row orders all four calls")
        for (position in 0..3) assertEquals(setOf(0, 1, 2, 3), rows.map { it[position] }.toSet(), "position $position")
        val adjacent = rows.flatMap { row -> row.zipWithNext() }
        assertEquals(12, adjacent.toSet().size, "each ordered pair of calls is adjacent exactly once")
        assertEquals(12, adjacent.size)
    }
}
