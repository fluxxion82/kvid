package com.kvid.embed

import com.kvid.embed.BertEmbeddingModel.Pooling
import com.kvid.store.EmbeddingSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BertEmbedderTest {
    private val vocab = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "a", "b", "c", "d", "e")
        .joinToString("\n", postfix = "\n")
    private val tokenizer = BertTokenizer.fromVocabulary(vocab)
    private val cls = 2L
    private val sep = 3L

    /**
     * Hidden states computed from the inputs, 4 per token: the token id, the position, the sum of the
     * row's real token ids (so CLS pooling tells texts apart) and 1. Padded positions are 1000, standing
     * in for whatever a real model computes there.
     */
    private class FakeBackend(private val transform: ((FloatArray) -> FloatArray)? = null) : EncoderBackend {
        class Call(val inputIds: LongArray, val attentionMask: LongArray, val tokenTypeIds: LongArray, val batch: Int, val seqLen: Int)

        val calls = ArrayList<Call>()
        var onRun: () -> Unit = {}

        override fun run(inputIds: LongArray, attentionMask: LongArray, tokenTypeIds: LongArray, batch: Int, seqLen: Int): FloatArray {
            calls += Call(inputIds.copyOf(), attentionMask.copyOf(), tokenTypeIds.copyOf(), batch, seqLen)
            onRun()
            val out = FloatArray(batch * seqLen * DIMS)
            for (b in 0 until batch) {
                val signature = (0 until seqLen).sumOf { t -> inputIds[b * seqLen + t] * attentionMask[b * seqLen + t] }.toFloat()
                for (t in 0 until seqLen) {
                    val i = (b * seqLen + t) * DIMS
                    if (attentionMask[b * seqLen + t] == 0L) {
                        for (d in 0 until DIMS) out[i + d] = 1000f
                    } else {
                        out[i] = inputIds[b * seqLen + t].toFloat()
                        out[i + 1] = t.toFloat()
                        out[i + 2] = signature
                        out[i + 3] = 1f
                    }
                }
            }
            return transform?.invoke(out) ?: out
        }

        override fun close() {}
    }

    private fun model(pooling: Pooling = Pooling.CLS, normalize: Boolean = false, maxInputTokens: Int = 512) =
        BertEmbeddingModel("test-model", DIMS, pooling, normalize, maxInputTokens, "last_hidden_state")

    private fun embedder(model: BertEmbeddingModel, backend: EncoderBackend, maxBatchSize: Int = 16) = BertEmbedder(
        model, tokenizer, backend,
        EmbeddingSpec(model.name, "sha256:0", "test", model.dimensions, model.pooling.id, model.normalize),
        maxBatchSize
    )

    @Test fun clsPoolingTakesTheFirstPosition() = runTest {
        val vectors = embedder(model(Pooling.CLS), FakeBackend()).embed(listOf("a b"))
        // [CLS] a b [SEP] = 2 5 6 3
        assertContentEquals(floatArrayOf(2f, 0f, 16f, 1f), vectors.single())
    }

    @Test fun meanPoolingAveragesRealTokensOnly() = runTest {
        val embedder = embedder(model(Pooling.MEAN), FakeBackend())
        val alone = embedder.embed(listOf("a")).single()
        val padded = embedder.embed(listOf("a", "a b c d e")).first()
        // [CLS] a [SEP] = 2 5 3: mean id 10/3, mean position 1
        assertContentEquals(floatArrayOf(10f / 3, 1f, 10f, 1f), alone)
        assertContentEquals(alone, padded, "padding beside a longer text must not change the mean")
    }

    @Test fun normalizationGivesUnitLengthAndKeepsZero() = runTest {
        val vector = embedder(model(normalize = true), FakeBackend()).embed(listOf("a b c")).single()
        assertEquals(1.0, sqrt(vector.sumOf { it.toDouble() * it }), 1e-6)
        assertContentEquals(FloatArray(4), l2Normalize(FloatArray(4)))
        assertContentEquals(floatArrayOf(0.6f, 0.8f), l2Normalize(floatArrayOf(3f, 4f)))
    }

    @Test fun withoutNormalizationValuesPassThrough() = runTest {
        val vector = embedder(model(normalize = false), FakeBackend()).embed(listOf("e")).single()
        assertContentEquals(floatArrayOf(2f, 0f, 14f, 1f), vector)
    }

    @Test fun padsEachBatchToItsLongestSequence() = runTest {
        val backend = FakeBackend()
        embedder(model(), backend).embed(listOf("a", "a b c"))
        val call = backend.calls.single()
        assertEquals(2, call.batch)
        assertEquals(5, call.seqLen)
        assertContentEquals(longArrayOf(cls, 5, sep, 0, 0, cls, 5, 6, 7, sep), call.inputIds)
        assertContentEquals(longArrayOf(1, 1, 1, 0, 0, 1, 1, 1, 1, 1), call.attentionMask)
        assertContentEquals(LongArray(10), call.tokenTypeIds)

        val batch = padBatch(listOf(intArrayOf(2, 9, 3), intArrayOf(2, 3)), padId = 0)
        assertContentEquals(longArrayOf(2, 9, 3, 2, 3, 0), batch.inputIds)
        assertContentEquals(longArrayOf(1, 1, 1, 1, 1, 0), batch.attentionMask)
    }

    @Test fun subBatchesKeepInputOrder() = runTest {
        val texts = listOf("a", "b c", "d", "e e e", "c a")
        val backend = FakeBackend()
        val batched = embedder(model(), backend, maxBatchSize = 2).embed(texts)
        assertEquals(listOf(2, 2, 1), backend.calls.map { it.batch })
        val single = texts.map { embedder(model(), FakeBackend()).embed(listOf(it)).single() }
        assertEquals(texts.size, batched.size)
        batched.zip(single).forEach { (a, b) -> assertContentEquals(b, a) }
    }

    @Test fun sortedSubBatchesGroupSimilarLengthsAndKeepInputOrder() = runTest {
        val texts = listOf("a b c d e", "a", "b c d e", "c", "d e")
        val backend = FakeBackend()
        val sorted = BertEmbedder(
            model(), tokenizer, backend, EmbeddingSpec("test-model", "sha256:0", "test", DIMS, "cls", false),
            maxBatchSize = 2, sortByLength = true
        ).embed(texts)
        // Token lengths with [CLS] and [SEP]: 7 3 6 3 4. Sorted: (3, 3), (4, 6), (7), so less padding.
        assertEquals(listOf(3, 6, 7), backend.calls.map { it.seqLen })
        val single = texts.map { embedder(model(), FakeBackend()).embed(listOf(it)).single() }
        sorted.zip(single).forEach { (a, b) -> assertContentEquals(b, a) }
    }

    @Test fun longTextsAreTruncatedToMaxInputTokens() = runTest {
        val backend = FakeBackend()
        embedder(model(maxInputTokens = 8), backend).embed(listOf("a ".repeat(20)))
        val ids = backend.calls.single().inputIds
        assertEquals(8, ids.size)
        assertEquals(cls, ids.first())
        assertEquals(sep, ids.last())
    }

    @Test fun anEmptyListDoesNotCallTheModel() = runTest {
        val backend = FakeBackend()
        assertTrue(embedder(model(), backend).embed(emptyList()).isEmpty())
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun aWrongOutputLengthFails() = runTest {
        val embedder = embedder(model(), FakeBackend { it.copyOf(it.size - 1) })
        val e = assertFailsWith<IllegalStateException> { embedder.embed(listOf("a")) }
        assertTrue("expected 1 × 3 × 4 = 12" in e.message!!, e.message)
    }

    @Test fun aNonFiniteOutputFails() = runTest {
        val embedder = embedder(model(normalize = true), FakeBackend { out -> out.also { it[0] = Float.NaN } })
        val e = assertFailsWith<IllegalStateException> { embedder.embed(listOf("a", "b")) }
        assertTrue("non-finite" in e.message!!, e.message)
    }

    @Test fun theOutputShapeMustBeExactlyBatchSequenceDimensions() {
        checkHiddenStatesShape(longArrayOf(2, 5, 384), batch = 2, seqLen = 5, dimensions = 384)
        val transposed = assertFailsWith<IllegalStateException> {
            checkHiddenStatesShape(longArrayOf(5, 2, 384), batch = 2, seqLen = 5, dimensions = 384)
        }
        assertTrue("[5, 2, 384]" in transposed.message!! && "[2, 5, 384]" in transposed.message!!, transposed.message)
        assertFailsWith<IllegalStateException> { checkHiddenStatesShape(longArrayOf(10, 384), batch = 2, seqLen = 5, dimensions = 384) }
        assertFailsWith<IllegalStateException> { checkHiddenStatesShape(longArrayOf(2, 5, 768), batch = 2, seqLen = 5, dimensions = 384) }
    }

    @Test fun cancellationStopsBeforeTheNextSubBatch() = runTest {
        val backend = FakeBackend()
        lateinit var job: Job
        backend.onRun = { if (backend.calls.size == 1) job.cancel() }
        job = launch { embedder(model(), backend, maxBatchSize = 1).embed(listOf("a", "b", "c", "d")) }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, backend.calls.size)
    }

    @Test fun modelsAreValidated() {
        val bge = BertEmbeddingModel.BGE_SMALL_EN_V1_5
        assertEquals(listOf<Any>("BAAI/bge-small-en-v1.5", 384, Pooling.CLS, true, 512, "last_hidden_state"),
            listOf(bge.name, bge.dimensions, bge.pooling, bge.normalize, bge.maxInputTokens, bge.outputName))
        assertFailsWith<IllegalArgumentException> { BertEmbeddingModel("m", 0, Pooling.CLS, true, 512, "out") }
        assertFailsWith<IllegalArgumentException> { BertEmbeddingModel("m", 4, Pooling.CLS, true, 1, "out") }
        assertFailsWith<IllegalArgumentException> { BertEmbeddingModel("m", 4, Pooling.CLS, true, 512, " ") }
        assertFailsWith<IllegalArgumentException> { embedder(model(), FakeBackend(), maxBatchSize = 0) }
    }

    private companion object {
        const val DIMS = 4
    }
}
