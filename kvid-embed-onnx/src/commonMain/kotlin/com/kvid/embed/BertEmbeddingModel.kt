package com.kvid.embed

import com.kvid.store.EmbeddingSpec

/**
 * The contract of a BERT-style sentence embedding model: what its graph returns and how one vector is
 * made from it. The output name and pooling belong to the model, so two embedders with equal
 * [EmbeddingSpec]s always read the same output the same way. Only [BGE_SMALL_EN_V1_5] is
 * offered for now; opening other models is a later API decision.
 */
class BertEmbeddingModel internal constructor(
    /** Model name recorded in the spec, such as "BAAI/bge-small-en-v1.5". */
    val name: String,
    /** Hidden size of [outputName], and so the length of every vector. */
    val dimensions: Int,
    val pooling: Pooling,
    /** Whether vectors are L2-normalized after pooling. */
    val normalize: Boolean,
    /** Longest input in tokens, `[CLS]` and `[SEP]` included; longer texts are truncated. */
    val maxInputTokens: Int,
    /** The graph output holding the token hidden states, shaped `[batch, sequence, dimensions]`. */
    val outputName: String
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(dimensions in 1..EmbeddingSpec.MAX_DIMENSIONS) { "dimensions must be within 1..${EmbeddingSpec.MAX_DIMENSIONS}" }
        require(maxInputTokens >= 2) { "maxInputTokens must leave room for [CLS] and [SEP]" }
        require(outputName.isNotBlank()) { "outputName must not be blank" }
    }

    /** How token hidden states become one vector; [id] is the spec's `pooling` value. */
    enum class Pooling(val id: String) {
        /** The hidden state of the first token, `[CLS]`. */
        CLS("cls"),
        /** The mean of the hidden states of real (unpadded) tokens. */
        MEAN("mean")
    }

    override fun toString(): String =
        "BertEmbeddingModel(name=$name, dimensions=$dimensions, pooling=${pooling.id}, normalize=$normalize, " +
            "maxInputTokens=$maxInputTokens, outputName=$outputName)"

    companion object {
        /** `BAAI/bge-small-en-v1.5` (ADR 0002): 384 dimensions, CLS pooling, normalized, 512 tokens. */
        val BGE_SMALL_EN_V1_5 = BertEmbeddingModel(
            name = "BAAI/bge-small-en-v1.5",
            dimensions = 384,
            pooling = Pooling.CLS,
            normalize = true,
            maxInputTokens = 512,
            outputName = "last_hidden_state"
        )
    }
}
