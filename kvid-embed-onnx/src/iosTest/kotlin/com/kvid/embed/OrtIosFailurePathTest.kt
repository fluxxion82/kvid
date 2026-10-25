package com.kvid.embed

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * Error unwinding of the iOS backend (implementation plan, R5): a failure injected at each step of open
 * and of an inference, a cancelled open, and a real ONNX Runtime error must leave every per-kind native
 * object count ([NativeObjects]) and the backend count where they were. This is the only class that sets
 * [OrtIosFaults.hook]. Uses the embedding fixture with the parity test's skip-or-fail rule; the real-error
 * case runs without it.
 */
class OrtIosFailurePathTest {
    @AfterTest fun clearHook() = OrtIosFaults.hook.store(null)

    private fun baseline() = NativeObjects.snapshot() to BackendHooks.live.load()

    private fun assertBaseline(expected: Pair<Map<NativeKind, Int>, Int>, what: String) {
        assertEquals(expected.first, NativeObjects.snapshot(), "$what: native objects left behind")
        assertEquals(expected.second, BackendHooks.live.load(), "$what: a backend left open")
    }

    @Test fun anOpenEmbedderOwnsItsObjectsUntilClose() = runTest(timeout = 10.minutes) {
        val fixture = EmbeddingFixture.loadOrSkip("iOS ownership test") ?: return@runTest
        val before = baseline()
        val reached = ArrayList<OrtStep>()
        // The hook runs on the open's IO thread; the list is read only after open returned.
        OrtIosFaults.hook.store { step -> reached += step }
        val embedder = OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath)
        try {
            OrtIosFaults.hook.store(null)
            assertEquals(OrtStep.entries.toSet(), reached.toSet(), "open (with its warm-up) did not reach every step")
            val live = NativeObjects.snapshot()
            for ((kind, count) in mapOf(NativeKind.SESSION_OPTIONS to 1, NativeKind.SESSION to 1, NativeKind.MEMORY_INFO to 1)) {
                assertEquals(before.first.getValue(kind) + count, live.getValue(kind), "$kind while open")
            }
            for (kind in listOf(NativeKind.TYPE_INFO, NativeKind.TENSOR_TYPE_AND_SHAPE_INFO, NativeKind.ALLOCATOR_STRING, NativeKind.VALUE, NativeKind.STATUS)) {
                assertEquals(before.first.getValue(kind), live.getValue(kind), "$kind is per operation and must not outlive it")
            }
            assertTrue(live.getValue(NativeKind.HEAP) > before.first.getValue(NativeKind.HEAP), "the backend's name arrays")
            assertEquals(384, embedder.embed(listOf("hello", "world")).first().size)
            assertEquals(live, NativeObjects.snapshot(), "an inference left native objects behind")
        } finally {
            embedder.close()
        }
        assertBaseline(before, "after close")
    }

    @Test fun aFailureAtEachStepOfOpenReleasesEverything() = runTest(timeout = 10.minutes) {
        val fixture = EmbeddingFixture.loadOrSkip("iOS failure-path test") ?: return@runTest
        for (step in OrtStep.entries) {
            val before = baseline()
            OrtIosFaults.hook.store { if (it == step) throw IllegalStateException("injected failure at $it") }
            val e = assertFailsWith<IllegalStateException> { OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath) }
            OrtIosFaults.hook.store(null)
            assertTrue("injected failure at $step" in e.message!!, "step $step: ${e.message}")
            if (step == OrtStep.RUN_INPUTS || step == OrtStep.RUN_OUTPUT) {
                assertTrue(e.message!!.startsWith("model file '${fixture.modelPath}' failed its warm-up inference:"), e.message)
            }
            assertBaseline(before, "failure at $step")
        }
    }

    @Test fun aFailedInferenceReleasesItsObjectsAndTheEmbedderStaysUsable() = runTest(timeout = 10.minutes) {
        val fixture = EmbeddingFixture.loadOrSkip("iOS inference failure test") ?: return@runTest
        val before = baseline()
        OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath).use { embedder ->
            val open = NativeObjects.snapshot()
            for (step in listOf(OrtStep.RUN_INPUTS, OrtStep.RUN_OUTPUT)) {
                OrtIosFaults.hook.store { if (it == step) throw IllegalStateException("injected failure at $it") }
                val e = assertFailsWith<IllegalStateException> { embedder.embed(listOf("a note", "another note")) }
                OrtIosFaults.hook.store(null)
                assertEquals("injected failure at $step", e.message)
                assertEquals(open, NativeObjects.snapshot(), "a failed inference at $step left native objects behind")
            }
            assertEquals(384, embedder.embed(listOf("still works")).single().size)
        }
        assertBaseline(before, "after close")
    }

    @Test fun anOpenCancelledAtEachStepReleasesEverything() = runTest(timeout = 10.minutes) {
        val fixture = EmbeddingFixture.loadOrSkip("iOS cancelled-open test") ?: return@runTest
        for (step in OrtStep.entries) {
            val before = baseline()
            val self = AtomicReference<Job?>(null)
            val opened = AtomicReference<OnnxEmbedder?>(null)
            OrtIosFaults.hook.store { if (it == step) self.load()!!.cancel() }
            val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                opened.store(OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath))
            }
            self.store(job)
            job.start()
            withRealTimeout(2.minutes, "the open cancelled at $step") { job.join() }
            OrtIosFaults.hook.store(null)
            assertTrue(job.isCancelled, "the open was not cancelled at $step")
            opened.load()?.let { fail("open cancelled at $step returned an embedder") }
            assertBaseline(before, "open cancelled at $step")
        }
    }

    @Test fun aRealOnnxRuntimeErrorReleasesItsStatus() = runTest(timeout = 10.minutes) {
        val dir = createTempDirectory("kvid-failure-test")
        try {
            val vocab = Path(dir, "vocab.txt").apply { writeText(listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "a").joinToString("\n", postfix = "\n")) }
            val before = baseline()
            // The vocabulary as the model: CreateSession returns an error status.
            val e = assertFailsWith<IllegalArgumentException> { OnnxEmbedder.open(vocab.toString(), vocab.toString()) }
            assertTrue(e.message!!.startsWith("model file '$vocab' could not be loaded by ONNX Runtime: "), e.message)
            assertBaseline(before, "a session that failed to load")
        } finally {
            dir.deleteRecursively()
        }
    }
}
