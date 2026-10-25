package com.kvid.embed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.sqrt
import kotlin.test.fail

/**
 * A fixture written by scripts/make_embedding_fixture.py: reference vectors from Python ONNX Runtime
 * (`embeddings.jsonl`), its provenance (`source.json`) and the model files it used. Paths are local and
 * readable; on Android they may be copies of the pushed files ([fixtureFile]). The model variants of
 * scripts/build_variants.py are fixtures of this kind too ([variant]).
 */
internal class EmbeddingFixture(
    val modelPath: String,
    val vocabPath: String,
    val cases: List<Case>,
    val source: JsonObject
) {
    class Case(val text: String, val ids: List<Int>, val vector: FloatArray)

    /** The model contract the reference vectors were made with: name, pooling, input limit and output. */
    val model: BertEmbeddingModel = BertEmbeddingModel(
        name = source.getValue("model").jsonPrimitive.content,
        dimensions = source.getValue("dimensions").jsonPrimitive.int,
        pooling = BertEmbeddingModel.Pooling.entries.single { it.id == source.getValue("pooling").jsonPrimitive.content },
        normalize = source.getValue("normalize").jsonPrimitive.boolean,
        maxInputTokens = source.getValue("max_tokens").jsonPrimitive.int,
        outputName = source.getValue("output_name").jsonPrimitive.content
    )

    /**
     * The largest batch whose results do not depend on the batch, when the fixture names one: dynamically
     * quantized variants take one activation scale over the whole batch, so they embed one text at a time.
     */
    val maxBatchSize: Int? = source["max_batch_size"]?.jsonPrimitive?.int

    /** [model] with another input limit, for measurements of shorter inputs. */
    fun model(maxInputTokens: Int): BertEmbeddingModel = with(model) {
        BertEmbeddingModel(name, dimensions, pooling, normalize, maxInputTokens, outputName)
    }

    companion object {
        /**
         * The fixture at KVID_EMBEDDING_FIXTURE_DIR, or null after printing why [what] is skipped. With
         * KVID_REQUIRE_EMBEDDING_FIXTURE=true a missing fixture fails instead, as in the tokenizer test.
         */
        fun loadOrSkip(what: String): EmbeddingFixture? {
            val dir = testEnvironment("KVID_EMBEDDING_FIXTURE_DIR")
            val required = testEnvironment("KVID_REQUIRE_EMBEDDING_FIXTURE") == "true"
            if (dir == null || fixtureFile("$dir/embeddings.jsonl") == null) {
                if (required) fail("KVID_REQUIRE_EMBEDDING_FIXTURE=true but there is no fixture at '$dir'")
                println("[kvid] $what skipped: set KVID_EMBEDDING_FIXTURE_DIR to a directory written by scripts/make_embedding_fixture.py")
                return null
            }
            return load(dir)
        }

        /**
         * The variant [id] under KVID_MODEL_VARIANTS_DIR (a directory written by scripts/build_variants.py);
         * fails when it is missing, since a measurement that names a variant must not fall back to another.
         */
        fun variant(id: String): EmbeddingFixture {
            val root = testEnvironment("KVID_MODEL_VARIANTS_DIR") ?: fail("model=$id needs KVID_MODEL_VARIANTS_DIR (scripts/build_variants.py)")
            if (fixtureFile("$root/$id/embeddings.jsonl") == null) fail("no model variant '$id' under '$root'")
            return load("$root/$id")
        }

        /** The fixture in [dir]; fails naming the file when one is missing. */
        fun load(dir: String): EmbeddingFixture {
            fun file(name: String) = fixtureFile("$dir/$name") ?: fail("the fixture at '$dir' has no $name")
            val cases = file("embeddings.jsonl").readText().lineSequence().filter { it.isNotBlank() }.map { line ->
                val case = Json.parseToJsonElement(line).jsonObject
                Case(
                    text = case.getValue("text").jsonPrimitive.content,
                    ids = case.getValue("ids").jsonArray.map { it.jsonPrimitive.int },
                    vector = case.getValue("vector").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
                )
            }.toList()
            val source = Json.parseToJsonElement(file("source.json").readText()).jsonObject
            return EmbeddingFixture(file("model.onnx").path, file("vocab.txt").path, cases, source)
        }
    }
}

internal fun cosine(a: FloatArray, b: FloatArray): Double {
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
        dot += a[i].toDouble() * b[i]
        na += a[i].toDouble() * a[i]
        nb += b[i].toDouble() * b[i]
    }
    return if (na == 0.0 || nb == 0.0) 0.0 else dot / sqrt(na * nb)
}

internal fun norm(v: FloatArray): Double = sqrt(v.sumOf { it.toDouble() * it })

internal fun maxAbsDiff(a: FloatArray, b: FloatArray): Double = a.indices.maxOf { kotlin.math.abs(a[it].toDouble() - b[it]) }

/** [format] with a dot as decimal separator whatever the device locale, for parseable log lines. */
internal fun fmt(format: String, value: Double): String = String.format(java.util.Locale.ROOT, format, value)
