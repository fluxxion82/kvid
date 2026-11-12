package com.kvid.embed

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The memory sampler of the measurement tests, on virtual time with a fake reading (the JVM measures no memory). */
class PhaseSamplerTest {
    @Test fun samplesGoToThePhaseCurrentAtEachSample() = runTest {
        var reading = 100L
        val sampler = PhaseSampler(before = 90L, read = { reading })
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
        assertEquals(0L, sampler.peak(Phase.EDITS))
        assertEquals("mem_open_peak_kb=300 mem_index_peak_kb=500 mem_close_peak_kb=120 mem_lifecycle_peak_kb=500", sampler.summary())
    }

    @Test fun withoutAMeasurementNothingIsSampled() = runTest {
        var reads = 0
        val sampler = PhaseSampler(before = null, read = { reads++; 1L })
        sampler.run()
        assertEquals(0, reads)
        assertTrue(Phase.entries.all { sampler.peak(it) == 0L })
    }
}
