package com.kvid.embed

import com.kvid.embed.BertEmbeddingModel.Pooling
import com.kvid.store.VectorMetric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Lifecycle and failure behavior of [OnnxEmbedder]. Tests that need the real model use the embedding
 * fixture with the parity test's skip-or-fail rule; failures on bad files run without it.
 *
 * This is the only class that sets [BackendHooks.insideRun], so no other test runs while it is set.
 * Waits are bounded in real time; blocking work (an inference held by the hook, a close that waits)
 * runs on threads of its own, so it never starves the dispatcher the test needs to release it.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class OnnxEmbedderTest {
    private val bge = BertEmbeddingModel.BGE_SMALL_EN_V1_5
    private val texts = listOf(
        "Groceries: milk, eggs, bread", "Meeting notes from the quarterly planning session", "café crème brûlée",
        "東京で寿司を食べた", "a", "", "The quick brown fox jumps over the lazy dog. ".repeat(20), "remember to call mom 📞"
    )

    private val tempDir = createTempDirectory("kvid-embedder-test")

    @AfterTest fun deleteTempFiles() = tempDir.deleteRecursively()

    private fun tempFile(name: String, content: String): Path = Path(tempDir, "kvid-$name.tmp").apply { writeText(content) }

    @Test fun specNamesTheModelAndTheDigestsOfItsFiles() = runTest(timeout = 10.minutes) {
        val fixture = EmbeddingFixture.loadOrSkip("embedder spec test") ?: return@runTest
        val digests = fixture.source.getValue("sha256").jsonObject
        OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath).use { embedder ->
            val spec = embedder.spec
            assertEquals(fixture.source.getValue("model").jsonPrimitive.content, spec.model)
            assertEquals("sha256:" + digests.getValue("model.onnx").jsonPrimitive.content, spec.modelDigest)
            assertEquals("bert-wordpiece-uncased sha256:" + digests.getValue("vocab.txt").jsonPrimitive.content, spec.tokenizer)
            assertEquals(384, spec.dimensions)
            assertEquals("cls", spec.pooling)
            assertTrue(spec.normalized)
            assertEquals(VectorMetric.COSINE, spec.metric)
            assertEquals(512, spec.maxInputTokens)
            assertTrue(embedder.embed(emptyList()).isEmpty())
        }
    }

    @Test fun closeIsIdempotentAndEmbedAfterwardsFails() = runTest(timeout = 10.minutes) {
        val fixture = EmbeddingFixture.loadOrSkip("embedder close test") ?: return@runTest
        val embedder = OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath)
        assertEquals(384, embedder.embed(listOf("hello")).single().size)
        embedder.close()
        embedder.close()
        val e = assertFailsWith<IllegalStateException> { embedder.embed(listOf("hello")) }
        assertEquals("OnnxEmbedder is closed", e.message)
    }

    @Test fun missingOrInvalidFilesFailNamingTheFile() = runTest(timeout = 10.minutes) {
        val vocab = tempFile("vocab", listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "a").joinToString("\n", postfix = "\n"))
        val missing = Path(tempDir, "kvid-missing-model.onnx").toString()
        val before = BackendHooks.live.load()

        val noModel = assertFailsWith<IllegalArgumentException> { OnnxEmbedder.open(missing, vocab.toString()) }
        assertTrue(missing in noModel.message!!, noModel.message)
        val noVocab = assertFailsWith<IllegalArgumentException> { OnnxEmbedder.open(vocab.toString(), "$missing.txt") }
        assertTrue("$missing.txt" in noVocab.message!!, noVocab.message)
        // A vocabulary given as the model: ONNX Runtime cannot parse it.
        val notAModel = assertFailsWith<IllegalArgumentException> { OnnxEmbedder.open(vocab.toString(), vocab.toString()) }
        assertTrue(vocab.toString() in notAModel.message!! && "could not be loaded" in notAModel.message!!, notAModel.message)
        val notAVocabulary = assertFailsWith<IllegalArgumentException> { OnnxEmbedder.open(vocab.toString(), tempFile("empty", "").toString()) }
        assertTrue("not a BERT WordPiece vocabulary" in notAVocabulary.message!!, notAVocabulary.message)
        assertEquals(before, BackendHooks.live.load(), "a failed open left a session open")
    }

    @Test fun aModelThatDoesNotMatchTheContractFailsToOpen() = runTest(timeout = 10.minutes) {
        val fixture = EmbeddingFixture.loadOrSkip("embedder contract test") ?: return@runTest
        val before = BackendHooks.live.load()
        val wide = BertEmbeddingModel(bge.name, 768, Pooling.CLS, true, 512, bge.outputName)
        val dims = assertOpenFails { OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath, wide) }
        assertTrue("768" in dims.message!! && fixture.modelPath in dims.message!!, dims.message)
        val renamed = BertEmbeddingModel(bge.name, 384, Pooling.CLS, true, 512, "sentence_embedding")
        val output = assertFailsWith<IllegalArgumentException> { OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath, renamed) }
        assertTrue("no output 'sentence_embedding'" in output.message!!, output.message)
        assertEquals(before, BackendHooks.live.load(), "a failed open left a session open")
    }

    @Test fun concurrentEmbedsMatchSerialResults() = runTest(timeout = 10.minutes) {
        val fixture = EmbeddingFixture.loadOrSkip("embedder concurrency test") ?: return@runTest
        OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath, options = OnnxOptions(maxBatchSize = 3)).use { embedder ->
            val serial = texts.map { embedder.embed(listOf(it)).single() }
            val concurrent = (0 until 4).map { worker ->
                async(Dispatchers.Default) { embedder.embed(texts.drop(worker) + texts.take(worker)) }
            }.awaitAll()
            concurrent.forEachIndexed { worker, vectors ->
                vectors.forEachIndexed { i, vector ->
                    val c = cosine(vector, serial[(i + worker) % texts.size])
                    assertTrue(c >= 0.99999, "worker $worker text ${(i + worker) % texts.size}: cosine $c")
                }
            }
        }
    }

    @Test fun closeWaitsForARunningInferenceAndLaterCallsFail() = runTest(timeout = 10.minutes) {
        val fixture = EmbeddingFixture.loadOrSkip("embedder close-while-running test") ?: return@runTest
        val before = BackendHooks.live.load()
        val embedder = OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath, options = OnnxOptions(maxBatchSize = 2))
        val entered = CompletableDeferred<Unit>()
        val release = TestLatch(1)
        val inferenceThread = newSingleThreadContext("kvid-test-inference")
        val closeThread = newSingleThreadContext("kvid-test-close")
        // Runs on the inference's native thread, which cannot suspend: a blocking, timed latch.
        BackendHooks.insideRun.store { entered.complete(Unit); release.await(2.minutes) }
        try {
            // One inference held inside the backend, then a second call queued behind it in the same embed.
            val running = async(inferenceThread) { runCatching { embedder.embed(texts.take(3)) } }
            withRealTimeout(60.seconds, "the held inference's start") { entered.await() }
            BackendHooks.insideRun.store(null)
            val closerStarted = CompletableDeferred<Unit>()
            val closing = async(closeThread) { closerStarted.complete(Unit); embedder.close() }
            withRealTimeout(60.seconds, "the closer's start") { closerStarted.await() }
            val early = withContext(Dispatchers.Default) { withTimeoutOrNull(300.milliseconds) { closing.await() } }
            assertTrue(early == null && closing.isActive, "close returned while an inference was running")
            release.countDown()
            withRealTimeout(60.seconds, "close after the inference finished") { closing.await() }
            // Its first sub-batch finished; the second found the embedder closed, so the call returns nothing.
            val failure = withRealTimeout(60.seconds, "the held embed call") { running.await() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException && failure.message == "OnnxEmbedder is closed", "unexpected result: $failure")
            assertFailsWith<IllegalStateException> { embedder.embed(listOf("after close")) }
            assertEquals(before, BackendHooks.live.load(), "close left a session open")
        } finally {
            BackendHooks.insideRun.store(null)
            release.countDown()
            embedder.close()
            inferenceThread.close()
            closeThread.close()
        }
    }

    @Test fun aCancelledOpenLeavesNoSessionOpen() = runTest(timeout = 10.minutes) {
        val fixture = EmbeddingFixture.loadOrSkip("embedder cancelled-open test") ?: return@runTest
        val before = BackendHooks.live.load()
        // Cancelled after the blocking work finished, while withContext returns: open must close what it
        // built. The job starts lazily, after its reference is published, so the callback cannot miss it.
        val self = AtomicReference<Job?>(null)
        val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            openOnnxEmbedder(fixture.modelPath, fixture.vocabPath, bge, OnnxOptions(), timings = { self.load()!!.cancel() })
            fail("open returned after its caller was cancelled")
        }
        self.store(job)
        job.start()
        withRealTimeout(2.minutes, "the cancelled open") { job.join() }
        assertTrue(job.isCancelled)
        assertEquals(before, BackendHooks.live.load(), "a cancelled open left a session open")
        // Cancelled at other points: whatever open returned is closed here, and nothing else stays open.
        for (delayMs in listOf(0L, 5L, 50L, 200L)) {
            val opened = AtomicReference<OnnxEmbedder?>(null)
            val attempt = launch(Dispatchers.Default) { opened.store(OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath)) }
            withContext(Dispatchers.Default) { delay(delayMs) }
            attempt.cancel()
            withRealTimeout(2.minutes, "open cancelled after $delayMs ms") { attempt.join() }
            opened.load()?.close()
            assertEquals(before, BackendHooks.live.load(), "open cancelled after $delayMs ms left a session open")
        }
    }

    private inline fun assertOpenFails(block: () -> Unit): Exception {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            assertTrue(e is IllegalArgumentException || e is IllegalStateException, "unexpected exception type: $e")
            return e
        }
        fail("expected an exception")
    }
}
