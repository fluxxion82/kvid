package com.kvid.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.sqrt

/** How stored vectors are compared. Both rank higher-is-better. */
enum class VectorMetric {
    /** Cosine similarity: vectors are L2-normalized when stored and queried, then compared by dot product. */
    COSINE,
    /** Raw dot product of the vectors as the embedder returned them. */
    DOT
}

/**
 * Identity of the embedding model that produced a store's vectors (persistence contract, section 8).
 * A store records the spec of its first indexed vectors, and every later embedder must present an
 * equal spec: vectors from different models are never mixed. Change models with [Kvid.resetVectors].
 */
@Serializable
data class EmbeddingSpec(
    /** Model name, such as "BAAI/bge-small-en-v1.5". */
    val model: String,
    /** Content identity of the weights, such as "sha256:…", or a provider-specific revision. */
    val modelDigest: String,
    /** Tokenizer identity, such as a digest of tokenizer.json. */
    val tokenizer: String,
    val dimensions: Int,
    /** Pooling applied by the embedder, such as "cls" or "mean". */
    val pooling: String,
    /** Whether the embedder L2-normalizes its output. */
    val normalized: Boolean,
    val metric: VectorMetric = VectorMetric.COSINE,
    /** Longest input, in model tokens, before the embedder truncates; null when not applicable. */
    val maxInputTokens: Int? = null
) {
    init {
        require(model.isNotBlank()) { "model must not be blank" }
        require(modelDigest.isNotBlank()) { "modelDigest must not be blank" }
        require(tokenizer.isNotBlank()) { "tokenizer must not be blank" }
        require(pooling.isNotBlank()) { "pooling must not be blank" }
        require(dimensions in 1..MAX_DIMENSIONS) { "dimensions must be within 1..$MAX_DIMENSIONS" }
        require(maxInputTokens == null || maxInputTokens > 0) { "maxInputTokens must be positive" }
    }

    companion object {
        const val MAX_DIMENSIONS = 8192
    }
}

/**
 * Produces vectors for text. Implementations live outside the core library (for example an ONNX
 * Runtime module); the store never downloads or runs a model by itself.
 */
interface Embedder {
    val spec: EmbeddingSpec

    /** One vector of [EmbeddingSpec.dimensions] finite values per text, in the same order. */
    suspend fun embed(texts: List<String>): List<FloatArray>
}

/** The store's vector state: the recorded spec, and how many live documents have or lack a vector. */
data class VectorStatus(val spec: EmbeddingSpec?, val embedded: Long, val pending: Long)

/** Result of one [Kvid.indexVectors] call. */
data class VectorIndexReport(val embedded: Int, val pending: Long)

/** Options for [Kvid.findSimilar]; filters behave exactly as in [ListOptions]. */
data class SimilarOptions(
    val limit: Int = 20,
    val cursor: String? = null,
    val sinceEventTimeMs: Long? = null,
    val untilEventTimeMs: Long? = null,
    val tags: List<String> = emptyList(),
    val uriPrefix: String? = null
)

/**
 * Reciprocal rank fusion for [Kvid.findHybrid]: a document's score is the sum over the lexical and
 * semantic rankings of `weight / (k + rank)`, with 1-based ranks among each ranking's top [candidates].
 */
data class FusionOptions(
    val k: Int = 60,
    val candidates: Int = 100,
    val lexicalWeight: Double = 1.0,
    val semanticWeight: Double = 1.0
) {
    init {
        require(k >= 0) { "k must not be negative" }
        require(candidates in 1..Limits.MAX_PAGE_SIZE) { "candidates must be within 1..${Limits.MAX_PAGE_SIZE}" }
        require(lexicalWeight >= 0.0 && semanticWeight >= 0.0) { "weights must not be negative" }
        require(lexicalWeight.isFinite() && semanticWeight.isFinite()) { "weights must be finite" }
    }
}

/** The text a version contributes to its embedding: the title, a blank line, then the body. */
internal fun embeddingInput(title: String?, body: String): String =
    if (title.isNullOrEmpty()) body else "$title\n\n$body"

internal object VectorCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun encodeSpec(spec: EmbeddingSpec): String = json.encodeToString(EmbeddingSpec.serializer(), spec)

    fun decodeSpec(raw: String): EmbeddingSpec = try {
        json.decodeFromString(EmbeddingSpec.serializer(), raw)
    } catch (e: Exception) {
        throw KvidException.Corrupt("embedding_config is not a valid embedding spec: ${e.message}", e)
    }

    /** Validates an embedder or caller vector and prepares it for storage or comparison under [spec]. */
    fun prepare(spec: EmbeddingSpec, vector: FloatArray, what: String): FloatArray {
        if (vector.size != spec.dimensions) throw KvidException.InvalidVector("$what has ${vector.size} dimensions, the spec has ${spec.dimensions}")
        for (v in vector) if (!v.isFinite()) throw KvidException.InvalidVector("$what contains a non-finite value")
        if (spec.metric != VectorMetric.COSINE) return vector
        var sum = 0.0
        for (v in vector) sum += v.toDouble() * v
        if (sum == 0.0) return vector.copyOf()
        val scale = (1.0 / sqrt(sum)).toFloat()
        return FloatArray(vector.size) { vector[it] * scale }
    }

    fun dot(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i].toDouble() * b[i]
        return sum
    }
}
