package com.kvid.embed

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.test.runTest
import platform.darwin.KERN_SUCCESS
import platform.darwin.TASK_VM_INFO
import platform.darwin.integer_tVar
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t
import platform.posix.RTLD_DEFAULT
import platform.posix.arc4random_buf
import platform.posix.dlsym
import platform.posix.malloc
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * Leak check of the iOS embedder (implementation plan, Task 6 and R11), the loop fallback: `leaks` cannot
 * attach to simulator processes with Xcode 27.1 ("Process exists but has not fully started -- dyld has
 * initialized but libSystem has not", with and without MallocStackLogging), so this measures instead.
 *
 * After an explicit warm-up it runs [CYCLES] open/embed/close cycles, then [FAILURE_ROUNDS] rounds of
 * failure paths: a failure injected through [OrtIosFaults] at every [OrtStep] of open, then a failed
 * inference at each inference step of an open embedder. It requires:
 * - the per-kind native object counts ([NativeObjects]) and [BackendHooks.live] back at their baseline
 *   exactly after every cycle and every round;
 * - the bytes in use in all malloc zones (`malloc_zone_statistics`; ONNX Runtime allocates with malloc and
 *   `new`) to grow by at most [MAX_MALLOC_GROWTH_KB] from the baseline to the end, with a least-squares
 *   slope over the samples of at most [MAX_MALLOC_SLOPE_KB_PER_CYCLE];
 * - `phys_footprint` likewise within [MAX_FOOTPRINT_GROWTH_KB] and [MAX_FOOTPRINT_SLOPE_KB_PER_CYCLE].
 *
 * Every sample follows a Kotlin GC. `phys_footprint` alone is not a leak detector here: with
 * KVID_EMBEDDING_LEAK_CONTROL=true the test leaks 256 KiB of random bytes per cycle on purpose (51 MiB in
 * all) and requires the malloc check to detect it; on the simulator `phys_footprint` did not show that
 * leak (the allocator reused pages already marked reusable, which footprint does not count), while the
 * malloc bytes in use and the resident size did. So malloc in use is the gate, and the footprint bound
 * is kept as the plan's stated, coarser check.
 *
 * What it proves: no counted native object is left behind, and no heap growth above the tolerances.
 * Smaller leaks, and leaks outside malloc, are not distinguishable from noise: the result is "no growth
 * detected", never "zero leaks". The once-only process state (R2: the process-wide `OrtEnv`, the
 * telemetry policy) is created by the warm-up and excluded from the per-cycle expectations.
 *
 * Runs only with KVID_EMBEDDING_LEAK=true (scripts/check-local.sh's ios part runs it on its own, then the
 * control), which also requires the embedding fixture. Prints one `[kvid-embedding-leak]` line, ending in
 * `result: no growth detected` or `result: growth detected`.
 */
@OptIn(ExperimentalForeignApi::class)
class OnnxEmbedderLeakTest {
    @AfterTest fun clearHook() = OrtIosFaults.hook.store(null)

    private class Sample(val cycle: Int, val mallocKb: Long, val footprintKb: Long, val residentKb: Long)

    @OptIn(NativeRuntimeApi::class)
    private fun sample(cycle: Int): Sample {
        GC.collect()
        return memScoped {
            // malloc_statistics_t { unsigned blocks_in_use; size_t size_in_use, max_size_in_use, size_allocated; }:
            // four 8-byte words on arm64, size_in_use the second.
            val stats = allocArray<ULongVar>(4)
            mallocZoneStatistics(null, stats)
            val info = alloc<task_vm_info_data_t>()
            val count = alloc<mach_msg_type_number_tVar>()
            count.value = (sizeOf<task_vm_info_data_t>() / sizeOf<integer_tVar>()).toUInt()
            val result = task_info(mach_task_self_, TASK_VM_INFO.toUInt(), info.ptr.reinterpret(), count.ptr)
            if (result != KERN_SUCCESS) fail("task_info(TASK_VM_INFO) returned $result")
            Sample(cycle, (stats[1] / 1024u).toLong(), (info.phys_footprint / 1024u).toLong(), (info.resident_size / 1024u).toLong())
        }
    }

    private fun counters() = NativeObjects.snapshot() to BackendHooks.live.load()

    /**
     * `malloc_zone_statistics(NULL, &stats)` (malloc/malloc.h): the statistics of all malloc zones. Kotlin's
     * iOS platform libraries do not include malloc.h, so it is looked up with dlsym.
     */
    private val mallocZoneStatistics: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>> =
        dlsym(RTLD_DEFAULT, "malloc_zone_statistics")?.reinterpret() ?: fail("malloc_zone_statistics not found")

    @Test fun repeatedOpenEmbedCloseAndFailuresShowNoGrowth() = runTest(timeout = 60.minutes) {
        if (testEnvironment("KVID_EMBEDDING_LEAK") != "true") {
            println("[kvid] embedding leak check skipped: set KVID_EMBEDDING_LEAK=true")
            return@runTest
        }
        val control = testEnvironment("KVID_EMBEDDING_LEAK_CONTROL") == "true"
        val fixture = EmbeddingFixture.loadOrSkip("embedding leak check")
            ?: fail("KVID_EMBEDDING_LEAK=true needs the embedding fixture (KVID_EMBEDDING_FIXTURE_DIR)")
        val texts = NotesCorpus.notes.map { it.second } + NotesCorpus.queries.map { it.first }
        val started = TimeSource.Monotonic.markNow()
        var controlLeaks = 0

        suspend fun cycle() {
            OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath).use { embedder ->
                assertEquals(texts.size, embedder.embed(texts).size)
            }
            if (control) {
                // The negative control: touched, incompressible, never freed.
                arc4random_buf(malloc(CONTROL_LEAK_BYTES), CONTROL_LEAK_BYTES)
                controlLeaks++
            }
        }

        suspend fun failureRound() {
            for (step in OrtStep.entries) {
                OrtIosFaults.hook.store { if (it == step) throw IllegalStateException("injected failure at $it") }
                assertFailsWith<IllegalStateException> { OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath) }
                OrtIosFaults.hook.store(null)
            }
            OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath).use { embedder ->
                for (step in listOf(OrtStep.RUN_INPUTS, OrtStep.RUN_OUTPUT)) {
                    OrtIosFaults.hook.store { if (it == step) throw IllegalStateException("injected failure at $it") }
                    assertFailsWith<IllegalStateException> { embedder.embed(texts) }
                    OrtIosFaults.hook.store(null)
                }
            }
        }

        // Warm-up: the once-only process state, allocator pools and the code paths of both kinds of cycle.
        repeat(WARMUP_CYCLES) { cycle() }
        failureRound()
        val baseline = counters()
        val samples = arrayListOf(sample(0))
        for (i in 1..CYCLES) {
            cycle()
            assertEquals(baseline, counters(), "native objects or backends left after cycle $i")
            if (i % SAMPLE_EVERY == 0) samples += sample(i)
        }
        for (round in 1..FAILURE_ROUNDS) {
            failureRound()
            assertEquals(baseline, counters(), "native objects or backends left after failure round $round")
        }
        samples += sample(CYCLES + FAILURE_ROUNDS)

        val first = samples.first()
        val last = samples.last()
        val mallocGrowth = last.mallocKb - first.mallocKb
        val mallocSlope = slope(samples) { it.mallocKb }
        val footprintGrowth = last.footprintKb - first.footprintKb
        val footprintSlope = slope(samples) { it.footprintKb }
        val residentGrowth = last.residentKb - first.residentKb
        val mallocOk = mallocGrowth <= MAX_MALLOC_GROWTH_KB && mallocSlope <= MAX_MALLOC_SLOPE_KB_PER_CYCLE
        val footprintOk = footprintGrowth <= MAX_FOOTPRINT_GROWTH_KB && footprintSlope <= MAX_FOOTPRINT_SLOPE_KB_PER_CYCLE
        val result = if (mallocOk && footprintOk) "no growth detected" else "growth detected"
        val elapsedS = started.elapsedNow().toDouble(DurationUnit.SECONDS)
        println(
            "[kvid-embedding-leak] mode=${if (control) "control" else "check"} platform=$testPlatform method=loop " +
                "cycles=$CYCLES failure_rounds=$FAILURE_ROUNDS failed_opens=${FAILURE_ROUNDS * OrtStep.entries.size} " +
                "failed_inferences=${FAILURE_ROUNDS * 2} warmup=$WARMUP_CYCLES control_leaked_kb=${controlLeaks * CONTROL_LEAK_BYTES.toLong() / 1024} " +
                "counters=baseline " +
                "malloc_kb=${first.mallocKb}->${last.mallocKb} malloc_growth_kb=$mallocGrowth malloc_slope_kb_per_cycle=${fmt("%.2f", mallocSlope)} " +
                "footprint_kb=${first.footprintKb}->${last.footprintKb} footprint_growth_kb=$footprintGrowth " +
                "footprint_slope_kb_per_cycle=${fmt("%.2f", footprintSlope)} resident_growth_kb=$residentGrowth " +
                // No '<' in the line: the JUnit XML keeps standard output in CDATA, and the gate greps up to '<'.
                "max_malloc_growth_kb=$MAX_MALLOC_GROWTH_KB max_malloc_slope_kb_per_cycle=${fmt("%.1f", MAX_MALLOC_SLOPE_KB_PER_CYCLE)} " +
                "max_footprint_growth_kb=$MAX_FOOTPRINT_GROWTH_KB max_footprint_slope_kb_per_cycle=${fmt("%.1f", MAX_FOOTPRINT_SLOPE_KB_PER_CYCLE)} " +
                "elapsed_s=${fmt("%.0f", elapsedS)} " +
                "samples(cycle:malloc/footprint/resident)=${samples.joinToString(",") { "${it.cycle}:${it.mallocKb}/${it.footprintKb}/${it.residentKb}" }} " +
                "result: $result"
        )
        if (control) {
            assertTrue(!mallocOk, "the negative control leaked ${controlLeaks * CONTROL_LEAK_BYTES.toLong() / 1024} KiB and the malloc check did not detect it")
        } else {
            assertTrue(mallocOk, "malloc bytes in use grew by $mallocGrowth KiB, slope ${fmt("%.2f", mallocSlope)} KiB per cycle")
            assertTrue(footprintOk, "phys_footprint grew by $footprintGrowth KiB, slope ${fmt("%.2f", footprintSlope)} KiB per cycle")
        }
    }

    /** Least-squares slope of [value] over cycles, in KiB per cycle. */
    private fun slope(samples: List<Sample>, value: (Sample) -> Long): Double {
        val n = samples.size
        val mx = samples.sumOf { it.cycle.toDouble() } / n
        val my = samples.sumOf { value(it).toDouble() } / n
        val sxy = samples.sumOf { (it.cycle - mx) * (value(it) - my) }
        val sxx = samples.sumOf { (it.cycle - mx) * (it.cycle - mx) }
        return if (sxx == 0.0) 0.0 else sxy / sxx
    }

    private companion object {
        const val WARMUP_CYCLES = 5
        const val CYCLES = 200
        const val FAILURE_ROUNDS = 20
        const val SAMPLE_EVERY = 20
        const val MAX_MALLOC_GROWTH_KB = 1024L
        const val MAX_MALLOC_SLOPE_KB_PER_CYCLE = 2.0
        const val MAX_FOOTPRINT_GROWTH_KB = 8L * 1024
        const val MAX_FOOTPRINT_SLOPE_KB_PER_CYCLE = 16.0
        val CONTROL_LEAK_BYTES: ULong = 262144uL
    }
}
