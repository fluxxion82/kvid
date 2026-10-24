package com.kvid.embed

import com.kvid.embed.BertEmbeddingModel.Pooling
import com.kvid.store.VectorMetric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.concurrent.thread
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Lifecycle and failure behavior of [OnnxEmbedder]. Tests that need the real model use the embedding
 * fixture with the parity test's skip-or-fail rule; failures on bad files run without it.
 */
class OnnxEmbedderTest {
    private val bge = BertEmbeddingModel.BGE_SMALL_EN_V1_5
    private val texts = listOf(
        "Groceries: milk, eggs, bread", "Meeting notes from the quarterly planning session", "café crème brûlée",
        "東京で寿司を食べた", "a", "", "The quick brown fox jumps over the lazy dog. ".repeat(20), "remember to call mom 📞"
    )

    private fun tempFile(name: String, content: String): File =
        File.createTempFile("kvid-$name", ".tmp").apply { deleteOnExit(); writeText(content) }

    @Test fun specNamesTheModelAndTheDigestsOfItsFiles() = runBlocking {
        val fixture = EmbeddingFixture.loadOrSkip("embedder spec test") ?: return@runBlocking
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

    @Test fun closeIsIdempotentAndEmbedAfterwardsFails() = runBlocking {
        val fixture = EmbeddingFixture.loadOrSkip("embedder close test") ?: return@runBlocking
        val embedder = OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath)
        assertEquals(384, embedder.embed(listOf("hello")).single().size)
        embedder.close()
        embedder.close()
        val e = assertFailsWith<IllegalStateException> { embedder.embed(listOf("hello")) }
        assertEquals("OnnxEmbedder is closed", e.message)
    }

    @Test fun missingOrInvalidFilesFailNamingTheFile() = runBlocking {
        val vocab = tempFile("vocab", listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "a").joinToString("\n", postfix = "\n"))
        val missing = File(vocab.parentFile, "kvid-missing-model.onnx").path
        val before = OrtEncoderBackend.live.get()

        val noModel = assertFailsWith<IllegalArgumentException> { OnnxEmbedder.open(missing, vocab.path) }
        assertTrue(missing in noModel.message!!, noModel.message)
        val noVocab = assertFailsWith<IllegalArgumentException> { OnnxEmbedder.open(vocab.path, "$missing.txt") }
        assertTrue("$missing.txt" in noVocab.message!!, noVocab.message)
        // A vocabulary given as the model: ONNX Runtime cannot parse it.
        val notAModel = assertFailsWith<IllegalArgumentException> { OnnxEmbedder.open(vocab.path, vocab.path) }
        assertTrue(vocab.path in notAModel.message!! && "could not be loaded" in notAModel.message!!, notAModel.message)
        val notAVocabulary = assertFailsWith<IllegalArgumentException> { OnnxEmbedder.open(vocab.path, tempFile("empty", "").path) }
        assertTrue("not a BERT WordPiece vocabulary" in notAVocabulary.message!!, notAVocabulary.message)
        assertEquals(before, OrtEncoderBackend.live.get(), "a failed open left a session open")
    }

    @Test fun aModelThatDoesNotMatchTheContractFailsToOpen() = runBlocking {
        val fixture = EmbeddingFixture.loadOrSkip("embedder contract test") ?: return@runBlocking
        val before = OrtEncoderBackend.live.get()
        val wide = BertEmbeddingModel(bge.name, 768, Pooling.CLS, true, 512, bge.outputName)
        val dims = assertOpenFails { OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath, wide) }
        assertTrue("768" in dims.message!! && fixture.modelPath in dims.message!!, dims.message)
        val renamed = BertEmbeddingModel(bge.name, 384, Pooling.CLS, true, 512, "sentence_embedding")
        val output = assertFailsWith<IllegalArgumentException> { OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath, renamed) }
        assertTrue("no output 'sentence_embedding'" in output.message!!, output.message)
        assertEquals(before, OrtEncoderBackend.live.get(), "a failed open left a session open")
    }

    @Test fun concurrentEmbedsMatchSerialResults() = runBlocking {
        val fixture = EmbeddingFixture.loadOrSkip("embedder concurrency test") ?: return@runBlocking
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

    @Test fun closeWaitsForARunningInferenceAndLaterCallsFail() = runBlocking {
        val fixture = EmbeddingFixture.loadOrSkip("embedder close-while-running test") ?: return@runBlocking
        val embedder = OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath, options = OnnxOptions(maxBatchSize = 2))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        OrtEncoderBackend.insideRunForTests = { entered.countDown(); release.await() }
        try {
            // One inference held inside the backend, then a second call queued behind it in the same embed.
            val running = async(Dispatchers.Default) { runCatching { embedder.embed(texts.take(3)) } }
            assertTrue(entered.await(60, TimeUnit.SECONDS), "the inference did not start")
            OrtEncoderBackend.insideRunForTests = null
            val closing = thread { embedder.close() }
            closing.join(300)
            assertTrue(closing.isAlive, "close returned while an inference was running")
            release.countDown()
            closing.join(60_000)
            assertTrue(!closing.isAlive, "close did not return after the inference finished")
            // Its first sub-batch finished; the second found the embedder closed, so the call returns nothing.
            val failure = running.await().exceptionOrNull()
            assertTrue(failure is IllegalStateException && failure.message == "OnnxEmbedder is closed", "unexpected result: $failure")
            assertFailsWith<IllegalStateException> { embedder.embed(listOf("after close")) }
        } finally {
            OrtEncoderBackend.insideRunForTests = null
            release.countDown()
            embedder.close()
        }
    }

    @Test fun aCancelledOpenLeavesNoSessionOpen() = runBlocking {
        val fixture = EmbeddingFixture.loadOrSkip("embedder cancelled-open test") ?: return@runBlocking
        val before = OrtEncoderBackend.live.get()
        // Cancelled after the blocking work finished, while withContext returns: open must close what it built.
        lateinit var job: Job
        job = launch(Dispatchers.Default) {
            OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath, bge, OnnxOptions(), timings = { job.cancel() })
            fail("open returned after its caller was cancelled")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(before, OrtEncoderBackend.live.get(), "a cancelled open left a session open")
        // Cancelled at other points: whatever open returned is closed here, and nothing else stays open.
        for (delayMs in listOf(0L, 5L, 50L, 200L)) {
            var opened: OnnxEmbedder? = null
            val attempt = launch(Dispatchers.Default) { opened = OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath) }
            delay(delayMs)
            attempt.cancel()
            attempt.join()
            opened?.close()
            assertEquals(before, OrtEncoderBackend.live.get(), "open cancelled after $delayMs ms left a session open")
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
