package com.kvid.embed

import com.kvid.store.EmbeddingSpec
import com.kvid.store.Embedder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The runtime-independent embedding pipeline: tokenize, batch and pad, run [backend], pool and
 * normalize. Texts are processed in sub-batches of at most [maxBatchSize], each padded to its longest
 * sequence. The result has one vector per text in input order, or an exception and nothing at all.
 */
internal class BertEmbedder(
    private val model: BertEmbeddingModel,
    private val tokenizer: BertTokenizer,
    private val backend: EncoderBackend,
    override val spec: EmbeddingSpec,
    private val maxBatchSize: Int
) : Embedder {
    private val padId: Int = requireNotNull(tokenizer.idOf("[PAD]")) { "the vocabulary lacks [PAD]" }

    init {
        require(maxBatchSize > 0) { "maxBatchSize must be positive" }
        require(spec.dimensions == model.dimensions) { "the spec has ${spec.dimensions} dimensions, the model ${model.dimensions}" }
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val vectors = ArrayList<FloatArray>(texts.size)
        var start = 0
        while (start < texts.size) {
            currentCoroutineContext().ensureActive()
            val end = min(start + maxBatchSize, texts.size)
            val ids = texts.subList(start, end).map { tokenizer.encode(it, model.maxInputTokens).ids }
            vectors += embedBatch(padBatch(ids, padId))
            start = end
        }
        return vectors
    }

    private fun embedBatch(batch: PaddedBatch): List<FloatArray> {
        val hidden = backend.run(batch.inputIds, batch.attentionMask, batch.tokenTypeIds, batch.size, batch.seqLen)
        val expected = batch.size.toLong() * batch.seqLen * model.dimensions
        check(hidden.size.toLong() == expected) {
            "the model returned ${hidden.size} values, expected ${batch.size} × ${batch.seqLen} × ${model.dimensions} = $expected"
        }
        return List(batch.size) { row ->
            val pooled = pool(hidden, batch, row, model.dimensions, model.pooling)
            check(pooled.all { it.isFinite() }) { "the model returned a non-finite value for text ${row + 1} of the batch" }
            if (model.normalize) l2Normalize(pooled) else pooled
        }
    }
}

/** Token ids of one sub-batch, padded to its longest sequence; arrays are row-major `[size, seqLen]`. */
internal class PaddedBatch(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray,
    val size: Int,
    val seqLen: Int
)

/** Pads [sequences] to the longest with [padId], attention mask 0 and token type 0. */
internal fun padBatch(sequences: List<IntArray>, padId: Int): PaddedBatch {
    require(sequences.isNotEmpty()) { "a batch needs at least one sequence" }
    val seqLen = sequences.maxOf { it.size }
    val inputIds = LongArray(sequences.size * seqLen) { padId.toLong() }
    val attentionMask = LongArray(sequences.size * seqLen)
    sequences.forEachIndexed { row, ids ->
        for (t in ids.indices) {
            inputIds[row * seqLen + t] = ids[t].toLong()
            attentionMask[row * seqLen + t] = 1
        }
    }
    return PaddedBatch(inputIds, attentionMask, LongArray(sequences.size * seqLen), sequences.size, seqLen)
}

/**
 * One vector from the hidden states of [row]: position 0 for CLS, or the mean over positions whose
 * attention mask is 1 for MEAN (accumulated in Double), so padding never contributes.
 */
internal fun pool(hidden: FloatArray, batch: PaddedBatch, row: Int, dimensions: Int, pooling: BertEmbeddingModel.Pooling): FloatArray {
    val rowStart = row * batch.seqLen * dimensions
    return when (pooling) {
        BertEmbeddingModel.Pooling.CLS -> hidden.copyOfRange(rowStart, rowStart + dimensions)
        BertEmbeddingModel.Pooling.MEAN -> {
            val sums = DoubleArray(dimensions)
            var count = 0
            for (t in 0 until batch.seqLen) {
                if (batch.attentionMask[row * batch.seqLen + t] == 0L) continue
                count++
                val offset = rowStart + t * dimensions
                for (d in 0 until dimensions) sums[d] += hidden[offset + d].toDouble()
            }
            FloatArray(dimensions) { if (count == 0) 0f else (sums[it] / count).toFloat() }
        }
    }
}

/** [vector] scaled to unit L2 length, the squared norm accumulated in Double; a zero vector stays zero. */
internal fun l2Normalize(vector: FloatArray): FloatArray {
    var sum = 0.0
    for (v in vector) sum += v.toDouble() * v
    if (sum == 0.0) return vector.copyOf()
    val scale = 1.0 / sqrt(sum)
    return FloatArray(vector.size) { (vector[it] * scale).toFloat() }
}
