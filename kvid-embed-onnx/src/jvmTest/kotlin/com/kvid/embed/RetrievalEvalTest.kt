package com.kvid.embed

import com.kvid.store.DocumentId
import com.kvid.store.Embedder
import com.kvid.store.FindOptions
import com.kvid.store.Hit
import com.kvid.store.Kvid
import com.kvid.store.MatchMode
import com.kvid.store.PutOptions
import com.kvid.store.SimilarOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.log2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Retrieval quality of kvid's lexical, semantic and hybrid search for the mobile indexing study (planning
 * branch, docs/plans/mobile-indexing.md), measured through the store on datasets written by
 * scripts/prepare_retrieval_eval.py with the model variants of scripts/build_variants.py. Runs only when
 * KVID_RETRIEVAL_EVAL names a configuration, such as
 * `datasets=scifact+nfcorpus,runs=bge-fp32@512+bge-int8@512+minilm-fp32@256,instruction=off+on`:
 * - `datasets`: directories under KVID_RETRIEVAL_EVAL_DIR;
 * - `runs`: `variant@inputLimit` (the limit defaults to the variant's own); each run re-indexes the vectors;
 * - `instruction`: whether queries get the model's retrieval instruction ([QUERY_INSTRUCTIONS]; a model
 *   without one runs `off` only). Documents are always embedded as they are.
 *
 * Lexical search (plain syntax, any word) does not depend on the run and is scored once per dataset. One
 * `[kvid-retrieval]` line per dataset, run, instruction and mode reports nDCG@10 (graded gains, as BEIR's
 * trec_eval computes it), recall@10 and recall@100 over the top 100, with indexing time and token counts;
 * the same records are appended as JSON lines to KVID_RETRIEVAL_EVAL_OUT when set. Nothing is asserted
 * about quality.
 */
class RetrievalEvalTest {
    private class Doc(val id: String, val title: String, val text: String)

    private class Dataset(val name: String, val docs: List<Doc>, val queries: List<Pair<String, String>>, val qrels: Map<String, Map<String, Int>>)

    /** Embeds queries with [prefix]; shares [inner]'s spec, so it searches vectors [inner] stored. */
    private class PrefixedQueries(private val inner: Embedder, private val prefix: String) : Embedder {
        override val spec get() = inner.spec
        override suspend fun embed(texts: List<String>): List<FloatArray> = inner.embed(texts.map { prefix + it })
    }

    @Test fun metricsMatchHandComputedValues() {
        val relevant = mapOf("a" to 1, "c" to 2, "x" to 1)
        val ranked = listOf("a", "b", "c")
        // DCG = 1/log2(2) + 2/log2(4) = 2; ideal = 2/log2(2) + 1/log2(3) + 1/log2(4) = 3.1309.
        assertEquals(2.0 / (2.0 + 1 / log2(3.0) + 0.5), ndcg(ranked, relevant, 10), 1e-12)
        assertEquals(1.0, ndcg(listOf("c"), relevant, 1), 1e-12)  // the grade-2 document first is ideal at k = 1
        assertEquals(0.5, ndcg(listOf("a"), relevant, 1), 1e-12)  // grade 1 where the ideal has grade 2
        assertEquals(1.0 / 3.0, recall(ranked, relevant, 2), 1e-12)
        assertEquals(2.0 / 3.0, recall(ranked, relevant, 100), 1e-12)
        assertEquals(0.0, ndcg(listOf("b"), relevant, 10))
    }

    @Test fun evaluateRetrievalQuality() = runBlocking {
        val config = testEnvironment("KVID_RETRIEVAL_EVAL") ?: run {
            println("[kvid] retrieval evaluation skipped: set KVID_RETRIEVAL_EVAL (and KVID_RETRIEVAL_EVAL_DIR, KVID_MODEL_VARIANTS_DIR)")
            return@runBlocking
        }
        val root = testEnvironment("KVID_RETRIEVAL_EVAL_DIR") ?: fail("KVID_RETRIEVAL_EVAL needs KVID_RETRIEVAL_EVAL_DIR (scripts/prepare_retrieval_eval.py)")
        val settings = config.split(',').filter { it.isNotBlank() }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().split('+') }
        val datasets = settings["datasets"] ?: fail("KVID_RETRIEVAL_EVAL names no datasets")
        val runs = settings["runs"] ?: fail("KVID_RETRIEVAL_EVAL names no runs")
        val instructions = (settings["instruction"] ?: listOf("off")).map {
            when (it) { "on" -> true; "off" -> false; else -> fail("instruction must be on or off, not '$it'") }
        }
        val out = testEnvironment("KVID_RETRIEVAL_EVAL_OUT")?.let { File(it) }
        for (name in datasets) evaluate(load(File(root, name)), runs, instructions, out)
    }

    private suspend fun evaluate(dataset: Dataset, runs: List<String>, instructions: List<Boolean>, out: File?) {
        val dir = File.createTempFile("kvid-eval", "").apply { delete(); mkdirs() }
        try {
            val store = Kvid.create(File(dir, "eval.kvid").path)
            try {
                val corpusIds = HashMap<DocumentId, String>()
                val inputs = ArrayList<String>()
                store.transaction {
                    for (doc in dataset.docs) {
                        // A document without text is searched by its title alone, stored as the body.
                        val (title, body) = if (doc.text.isBlank()) null to doc.title else doc.title.ifBlank { null } to doc.text
                        if (body.isBlank()) continue
                        corpusIds[put(body, PutOptions(title = title))] = doc.id
                        inputs += if (title == null) body else "$title\n\n$body"
                    }
                }
                val common = mapOf("dataset" to dataset.name, "docs" to corpusIds.size, "queries" to dataset.queries.size)
                fun ids(hits: List<Hit>) = hits.map { corpusIds.getValue(it.document.id) }
                suspend fun search(block: suspend (String) -> List<Hit>): Pair<List<List<String>>, Int> {
                    var failed = 0
                    val results = dataset.queries.map { (_, text) -> runCatching { ids(block(text)) }.getOrElse { failed++; emptyList() } }
                    return results to failed
                }

                val lexical = search { store.find(it, FindOptions(limit = 100, match = MatchMode.ANY)).items }
                report(out, common + mapOf("run" to "lexical", "instruction" to "-", "mode" to "lexical") + scores(dataset, lexical))

                for (run in runs) {
                    val variant = EmbeddingFixture.variant(run.substringBefore('@'))
                    val model = run.substringAfter('@', "").takeIf { it.isNotEmpty() }?.let { variant.model(it.toInt()) } ?: variant.model
                    val tokenizer = BertTokenizer.fromVocabulary(File(variant.vocabPath).readText())
                    val lengths = inputs.map { tokenizer.tokenize(it).size + 2 }
                    val options = OnnxOptions(maxBatchSize = variant.maxBatchSize ?: OnnxOptions().maxBatchSize)
                    OnnxEmbedder.open(variant.modelPath, variant.vocabPath, model, options).use { embedder ->
                        store.resetVectors()
                        val start = System.nanoTime()
                        val indexed = store.indexVectors(embedder)
                        val indexMs = (System.nanoTime() - start) / 1e6
                        assertEquals(0L, indexed.pending, "every document is embedded")
                        val runInfo = common + mapOf(
                            "run" to "${variant.source.getValue("variant").jsonPrimitive.content}@${model.maxInputTokens}",
                            "model" to model.name, "batch" to options.maxBatchSize,
                            "index_ms" to fmt("%.0f", indexMs).toLong(), "ms_per_doc" to fmt("%.2f", indexMs / corpusIds.size).toDouble(),
                            "mean_tokens" to fmt("%.1f", lengths.average()).toDouble(),
                            "truncated" to fmt("%.4f", lengths.count { it > model.maxInputTokens }.toDouble() / lengths.size).toDouble()
                        )
                        for (instruction in instructions) {
                            val prefix = if (instruction) QUERY_INSTRUCTIONS[model.name] ?: continue else ""
                            val queries = if (prefix.isEmpty()) embedder else PrefixedQueries(embedder, prefix)
                            val semantic = search { store.findSimilar(it, queries, SimilarOptions(limit = 100)).items }
                            val hybrid = search { store.findHybrid(it, queries, FindOptions(limit = 100, match = MatchMode.ANY)).items }
                            val label = mapOf("instruction" to if (instruction) "on" else "off")
                            report(out, runInfo + label + mapOf("mode" to "semantic") + scores(dataset, semantic))
                            report(out, runInfo + label + mapOf("mode" to "hybrid") + scores(dataset, hybrid))
                        }
                    }
                }
            } finally {
                store.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun scores(dataset: Dataset, results: Pair<List<List<String>>, Int>): Map<String, Any> {
        val (ranked, failed) = results
        fun mean(metric: (List<String>, Map<String, Int>) -> Double) =
            dataset.queries.indices.map { metric(ranked[it], dataset.qrels.getValue(dataset.queries[it].first)) }.average()
        val values = mapOf(
            "ndcg10" to mean { r, rel -> ndcg(r, rel, 10) },
            "recall10" to mean { r, rel -> recall(r, rel, 10) },
            "recall100" to mean { r, rel -> recall(r, rel, 100) }
        )
        values.values.forEach { assertTrue(it in 0.0..1.0, "metric out of range: $values") }
        return values.mapValues { fmt("%.4f", it.value).toDouble() } + mapOf("failed_queries" to failed)
    }

    private fun report(out: File?, record: Map<String, Any>) {
        println("[kvid-retrieval] " + record.entries.joinToString(" ") { "${it.key}=${it.value}" } + " platform=$testPlatform")
        out?.appendText(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.JsonObject(record.mapValues { (_, v) -> if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) })) + "\n")
    }

    private fun load(dir: File): Dataset {
        fun lines(name: String) = File(dir, name).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }
            ?: fail("no $name in '$dir' (scripts/prepare_retrieval_eval.py)")
        val docs = lines("corpus.jsonl").map { Json.parseToJsonElement(it).jsonObject }.map {
            Doc(it.getValue("id").jsonPrimitive.content, it.getValue("title").jsonPrimitive.content, it.getValue("text").jsonPrimitive.content)
        }
        val queries = lines("queries.jsonl").map { Json.parseToJsonElement(it).jsonObject }.map {
            it.getValue("id").jsonPrimitive.content to it.getValue("text").jsonPrimitive.content
        }
        val qrels = HashMap<String, HashMap<String, Int>>()
        for (line in lines("qrels.tsv").drop(1)) {
            val (query, doc, grade) = line.split('\t')
            qrels.getOrPut(query) { HashMap() }[doc] = grade.toInt()
        }
        assertTrue(queries.all { (id, _) -> qrels[id].orEmpty().isNotEmpty() }, "every query in '$dir' needs a relevant document")
        return Dataset(dir.name, docs, queries, qrels)
    }

    companion object {
        /** Retrieval instructions for queries, from the model cards; documents are embedded without one. */
        val QUERY_INSTRUCTIONS = mapOf("BAAI/bge-small-en-v1.5" to "Represent this sentence for searching relevant passages: ")

        /** nDCG@[k] with the grade as gain, as trec_eval's ndcg_cut computes it. */
        fun ndcg(ranked: List<String>, relevant: Map<String, Int>, k: Int): Double {
            val dcg = ranked.take(k).withIndex().sumOf { (i, doc) -> (relevant[doc] ?: 0) / log2(i + 2.0) }
            val ideal = relevant.values.sortedDescending().take(k).withIndex().sumOf { (i, grade) -> grade / log2(i + 2.0) }
            return if (ideal == 0.0) 0.0 else dcg / ideal
        }

        /** Share of the relevant documents among the first [k] results. */
        fun recall(ranked: List<String>, relevant: Map<String, Int>, k: Int): Double =
            ranked.take(k).count { it in relevant }.toDouble() / relevant.size
    }
}
