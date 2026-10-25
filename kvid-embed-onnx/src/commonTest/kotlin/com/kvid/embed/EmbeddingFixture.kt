package com.kvid.embed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.fail

/**
 * A fixture written by scripts/make_embedding_fixture.py: reference vectors from Python ONNX Runtime
 * (`embeddings.jsonl`), its provenance (`source.json`) and the model files it used. Paths are local and
 * readable; on Android they may be copies of the pushed files ([fixtureFile]), and the iOS simulator
 * reads the host's paths.
 */
internal class EmbeddingFixture(
    val modelPath: String,
    val vocabPath: String,
    val cases: List<Case>,
    val source: JsonObject
) {
    class Case(val text: String, val ids: List<Int>, val vector: FloatArray)

    companion object {
        /**
         * The fixture at KVID_EMBEDDING_FIXTURE_DIR, or null after printing why [what] is skipped. With
         * KVID_REQUIRE_EMBEDDING_FIXTURE=true a missing fixture fails instead, as in the tokenizer test.
         */
        fun loadOrSkip(what: String): EmbeddingFixture? {
            val dir = testEnvironment("KVID_EMBEDDING_FIXTURE_DIR")
            val required = testEnvironment("KVID_REQUIRE_EMBEDDING_FIXTURE") == "true"
            val embeddings = dir?.let { fixtureFile("$it/embeddings.jsonl") }
            if (dir == null || embeddings == null) {
                if (required) fail("KVID_REQUIRE_EMBEDDING_FIXTURE=true but there is no fixture at '$dir'")
                println("[kvid] $what skipped: set KVID_EMBEDDING_FIXTURE_DIR to a directory written by scripts/make_embedding_fixture.py")
                return null
            }
            fun file(name: String) = fixtureFile("$dir/$name") ?: fail("the fixture at '$dir' has no $name")
            val cases = embeddings.readText().lineSequence().filter { it.isNotBlank() }.map { line ->
                val case = Json.parseToJsonElement(line).jsonObject
                Case(
                    text = case.getValue("text").jsonPrimitive.content,
                    ids = case.getValue("ids").jsonArray.map { it.jsonPrimitive.int },
                    vector = case.getValue("vector").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
                )
            }.toList()
            val source = Json.parseToJsonElement(file("source.json").readText()).jsonObject
            return EmbeddingFixture(file("model.onnx").toString(), file("vocab.txt").toString(), cases, source)
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

internal fun maxAbsDiff(a: FloatArray, b: FloatArray): Double = a.indices.maxOf { abs(a[it].toDouble() - b[it]) }
