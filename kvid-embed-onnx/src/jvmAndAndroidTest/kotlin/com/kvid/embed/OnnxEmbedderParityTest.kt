package com.kvid.embed

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compares [OnnxEmbedder] with the reference vectors of scripts/make_embedding_fixture.py (Python ONNX
 * Runtime, one text at a time, the fixture's pooling, float32 normalization) on every case, embedded in
 * batches of 8 (exercising padding) and one at a time. ADR 0002 requires a cosine of at least 0.9999 per
 * case. The model contract comes from the fixture's source.json, so the same test checks the variants of
 * scripts/build_variants.py. CI requires the fixture; locally the test is skipped with a message when
 * KVID_EMBEDDING_FIXTURE_DIR is unset.
 */
class OnnxEmbedderParityTest {
    @Test fun matchesPythonOnnxRuntimeOnTheReferenceFixture() = runBlocking {
        val fixture = EmbeddingFixture.loadOrSkip("embedding parity") ?: return@runBlocking
        val cases = fixture.cases
        assertTrue(cases.isNotEmpty(), "the fixture has no cases")
        assertEquals(fixture.source.getValue("cases").jsonPrimitive.int, cases.size, "embeddings.jsonl and source.json disagree on the case count")
        val model = fixture.model
        cases.forEachIndexed { i, case ->
            assertEquals(model.dimensions, case.vector.size, "reference vector $i has ${case.vector.size} values")
            assertTrue(case.vector.all { it.isFinite() }, "reference vector $i is not finite")
            assertTrue(abs(norm(case.vector) - 1.0) <= 1e-5, "reference vector $i has norm ${norm(case.vector)}")
        }

        val tokenizer = BertTokenizer.fromVocabulary(java.io.File(fixture.vocabPath).readText())
        val idMismatches = cases.indices.filter { tokenizer.encode(cases[it].text, model.maxInputTokens).ids.toList() != cases[it].ids }

        val texts = cases.map { it.text }
        val batchSize = minOf(8, fixture.maxBatchSize ?: 8)
        val (batched, single) = OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath, model, OnnxOptions(maxBatchSize = batchSize)).use { embedder ->
            embedder.embed(texts) to texts.map { embedder.embed(listOf(it)).single() }
        }
        assertEquals(cases.size, batched.size)

        var minCosine = 1.0
        var maxDiff = 0.0
        var batchedMinCosine = 1.0
        val below = ArrayList<String>()
        cases.forEachIndexed { i, case ->
            for ((how, vector) in listOf("batched" to batched[i], "single" to single[i])) {
                assertEquals(model.dimensions, vector.size)
                assertTrue(vector.all { it.isFinite() }, "case $i ($how) has a non-finite value")
                // Cosine ignores scale, so a wrongly scaled vector would pass it; the spec says normalized.
                val norm = sqrt(vector.sumOf { it.toDouble() * it })
                assertTrue(abs(norm - 1.0) < 1e-5, "case $i ($how) has norm $norm, expected 1")
                val c = cosine(vector, case.vector)
                if (c < 0.9999) below += "  case $i ($how) cosine=$c text=${case.text.take(60).replace("\n", "\\n")}"
                minCosine = minOf(minCosine, c)
                maxDiff = maxOf(maxDiff, maxAbsDiff(vector, case.vector))
            }
            batchedMinCosine = minOf(batchedMinCosine, cosine(batched[i], single[i]))
        }
        println(
            "[kvid-embedding] cases=${cases.size} min_cosine=${fmt("%.8f", minCosine)} max_abs_diff=${fmt("%.3e", maxDiff)} " +
                "batched_min_cosine=${fmt("%.8f", batchedMinCosine)} model=${model.name} pooling=${model.pooling.id} batch=$batchSize platform=$testPlatform"
        )
        assertTrue(idMismatches.isEmpty(), "token ids differ from the fixture for ${idMismatches.size} cases, first: ${idMismatches.take(10)}")
        assertTrue(below.isEmpty(), "${below.size} results are below cosine 0.9999:\n" + below.take(10).joinToString("\n"))
        assertTrue(batchedMinCosine >= 0.99999, "batched and single results differ: min cosine $batchedMinCosine")
    }
}
