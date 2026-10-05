package com.kvid.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A deterministic bag-of-words embedder: each lowercase word adds ±1 to a hashed dimension. */
class HashingEmbedder(dimensions: Int = 64, model: String = "test-hashing") : Embedder {
    override val spec = EmbeddingSpec(
        model = model, modelDigest = "test:1", tokenizer = "lowercase-words",
        dimensions = dimensions, pooling = "sum", normalized = false
    )
    val inputs = ArrayList<String>()
    var calls = 0
    /** When set, embed() completes [entered] and waits for [release]. */
    var entered: CompletableDeferred<Unit>? = null
    var release: CompletableDeferred<Unit>? = null

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        calls++
        inputs += texts
        entered?.complete(Unit)
        release?.await()
        return texts.map { vectorOf(it) }
    }

    fun vectorOf(text: String): FloatArray {
        val v = FloatArray(spec.dimensions)
        for (word in text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }) {
            val h = word.hashCode()
            v[(h ushr 1) % spec.dimensions] += if (h and 1 == 0) 1f else -1f
        }
        return v
    }
}

/** Returns the vector registered for each exact text, or zeros. */
class LookupEmbedder(private val vectors: Map<String, FloatArray>, dimensions: Int = 3) : Embedder {
    override val spec = EmbeddingSpec("test-lookup", "test:1", "exact-text", dimensions, "none", normalized = false)
    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { vectors[it] ?: FloatArray(spec.dimensions) }
}

/** Returns whatever [produce] makes of the texts, to exercise validation. */
class BrokenEmbedder(dimensions: Int = 4, private val produce: (List<String>) -> List<FloatArray>) : Embedder {
    override val spec = EmbeddingSpec("test-broken", "test:1", "none", dimensions, "none", normalized = false)
    override suspend fun embed(texts: List<String>): List<FloatArray> = produce(texts)
}

/** Milestone 5 store-side vector layer (persistence contract, sections 7 and 8). */
class VectorSearchTest {

    private fun rawCount(path: String, sql: String): Long = BundledSQLiteDriver().open(path).use { raw ->
        raw.prepare(sql).use { st -> st.step(); st.getLong(0) }
    }

    /** Same normalization and arithmetic as the store, so equal inputs give equal scores. */
    private fun cosine(a: FloatArray, b: FloatArray): Double = VectorCodec.dot(normalized(a), normalized(b))
    private fun normalized(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        if (sum == 0.0) return v.copyOf()
        val scale = (1.0 / sqrt(sum)).toFloat()
        return FloatArray(v.size) { v[it] * scale }
    }

    @Test fun indexingEmbedsEachLiveDocumentOnceRecordsTheSpecAndDropsStaleVectors() = storeTest { dir ->
        val path = dir.file("a.kvid")
        val store = Kvid.create(path)
        val ids = store.transaction { (1..5).map { put("body $it", PutOptions(title = if (it == 1) "Title" else null)) } }
        val embedder = HashingEmbedder()
        assertEquals(VectorStatus(null, 0, 5), store.vectorStatus())

        val report = store.indexVectors(embedder, batchSize = 2)
        assertEquals(VectorIndexReport(embedded = 5, pending = 0), report)
        assertEquals(3, embedder.calls, "batches of 2, 2 and 1")
        assertEquals("Title\n\nbody 1", embedder.inputs.first(), "the embedded text is the title, a blank line, then the body")
        assertEquals(embedder.spec, store.embeddingSpec())

        assertEquals(VectorIndexReport(0, 0), store.indexVectors(embedder))
        assertEquals(3, embedder.calls, "nothing pending means no embedder call")

        store.update(ids[0], "body 1 edited")
        assertEquals(VectorStatus(embedder.spec, 4, 1), store.vectorStatus(), "the superseded version's vector is removed")
        assertEquals(1, store.indexVectors(embedder).embedded)
        store.delete(ids[1])
        assertEquals(VectorStatus(embedder.spec, 4, 0), store.vectorStatus())
        store.close()
        assertEquals(4L, rawCount(path, "SELECT count(*) FROM version_vectors"), "no vectors are kept for old or deleted versions")
    }

    @Test fun similarSearchMatchesABruteForceReferenceWithFiltersPagingAndExpiry() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val embedder = HashingEmbedder()
        val texts = listOf(
            "budget plan review for the quarter", "plan the budget", "grocery list milk eggs",
            "review the quarterly plan", "travel plan for the summer", "budget plan review for the quarter",
            "eggs and milk", "quarter budget"
        )
        val ids = store.transaction { texts.mapIndexed { i, t -> put(t, PutOptions(tags = if (i % 2 == 0) listOf("even") else emptyList(), uri = "notes/$i")) } }
        store.indexVectors(embedder)
        val query = "budget plan review"

        val queryVector = embedder.vectorOf(query)
        val versionOf = ids.associateWith { store.get(it)!!.version.versionId }
        val expected = texts.indices.map { i -> Triple(ids[i], versionOf.getValue(ids[i]), cosine(queryVector, embedder.vectorOf(texts[i]))) }
            .sortedWith(compareByDescending<Triple<String, Long, Double>> { it.third }.thenByDescending { it.second })

        val hits = store.findSimilar(query, embedder, SimilarOptions(limit = 20)).items
        assertEquals(expected.map { it.first }, hits.map { it.document.id }, "order, including the tie between identical texts")
        for ((e, h) in expected.zip(hits)) assertTrue(abs(e.third - h.score) < 1e-9, "score ${h.score} vs ${e.third}")
        assertTrue(hits.all { it.snippet == null })
        assertEquals(hits.map { it.document.id }, store.findSimilar(queryVector, embedder.spec, SimilarOptions(limit = 20)).items.map { it.document.id })

        val even = store.findSimilar(query, embedder, SimilarOptions(limit = 20, tags = listOf("even"))).items.map { it.document.id }
        assertEquals(expected.map { it.first }.filter { ids.indexOf(it) % 2 == 0 }, even)
        assertEquals(listOf(ids[3]), store.findSimilar(query, embedder, SimilarOptions(uriPrefix = "notes/3")).items.map { it.document.id })

        val first = store.findSimilar(query, embedder, SimilarOptions(limit = 3))
        val second = store.findSimilar(query, embedder, SimilarOptions(limit = 3, cursor = first.nextCursor))
        assertEquals(expected.map { it.first }.subList(0, 6), (first.items + second.items).map { it.document.id })
        assertFailsWith<KvidException.Usage> { store.findSimilar("other", embedder, SimilarOptions(limit = 3, cursor = first.nextCursor)) }
        store.put("budget plan review again")
        assertFailsWith<KvidException.CursorExpired> { store.findSimilar(query, embedder, SimilarOptions(limit = 3, cursor = first.nextCursor)) }
        val lexical = store.find("budget", FindOptions(limit = 1)).nextCursor
        store.indexVectors(embedder)
        assertFailsWith<KvidException.CursorExpired>("indexing vectors is a committed write") { store.find("budget", FindOptions(limit = 1, cursor = lexical)) }
        store.close()
    }

    @Test fun aDifferentModelIsRefusedUntilVectorsAreReset() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        store.put("alpha beta")
        val a = HashingEmbedder(model = "model-a")
        val b = HashingEmbedder(model = "model-b")
        store.indexVectors(a)
        val mismatch = assertFailsWith<KvidException.EmbeddingMismatch> { store.indexVectors(b) }
        assertEquals("KV_EMBEDDING_MISMATCH", mismatch.code)
        assertEquals(a.spec, mismatch.recorded)
        assertEquals(0, b.calls, "the model is not run before the spec is checked")
        assertFailsWith<KvidException.EmbeddingMismatch> { store.findSimilar("alpha", b) }
        assertFailsWith<KvidException.EmbeddingMismatch> { store.findHybrid("alpha", b) }
        assertFailsWith<KvidException.EmbeddingMismatch> { store.findSimilar(b.vectorOf("alpha"), b.spec) }
        assertEquals(VectorStatus(a.spec, 1, 0), store.vectorStatus())

        store.resetVectors()
        assertEquals(VectorStatus(null, 0, 1), store.vectorStatus())
        store.indexVectors(b)
        assertEquals(b.spec, store.embeddingSpec())
        assertEquals(1, store.findSimilar("alpha", b).items.size)
        store.close()
    }

    @Test fun withoutVectorsSimilarIsEmptyAndHybridFollowsLexicalRanking() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        store.transaction { listOf("plan plan plan", "plan budget", "budget only", "the plan").forEach { put(it) } }
        val embedder = HashingEmbedder()
        assertTrue(store.findSimilar("plan", embedder).items.isEmpty())
        assertNull(store.findSimilar("plan", embedder).nextCursor)
        assertEquals(store.find("plan").items.map { it.document.id }, store.findHybrid("plan", embedder).items.map { it.document.id })
        assertEquals(0, embedder.calls, "no model runs while the store has no vectors")
        store.close()
    }

    @Test fun hybridIsReciprocalRankFusionOfBothRankings() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        // Lexical matches for "alpha": a1, a2, a3. Semantic neighbours of the query vector: s1 (no "alpha"), a3, a1.
        val vectors = mapOf(
            "alpha" to floatArrayOf(1f, 0f, 0f),
            "alpha alpha alpha report" to floatArrayOf(0.2f, 1f, 0f),
            "alpha notes" to floatArrayOf(0f, 0f, 1f),
            "alpha with beta" to floatArrayOf(0.8f, 0.6f, 0f),
            "semantic neighbour" to floatArrayOf(0.99f, 0.1f, 0f),
            "unrelated" to floatArrayOf(-1f, 0f, 0f)
        )
        val embedder = LookupEmbedder(vectors)
        val ids = store.transaction { vectors.keys.drop(1).map { put(it) } }
        store.indexVectors(embedder)

        val lexical = store.find("alpha", FindOptions(limit = 100)).items.map { it.document.id }
        val semantic = store.findSimilar("alpha", embedder, SimilarOptions(limit = 100)).items.map { it.document.id }
        val k = 60
        val versionOf = ids.associateWith { store.get(it)!!.version.versionId }
        val expected = (lexical + semantic).distinct().map { id ->
            val score = listOf(lexical, semantic).sumOf { ranking -> ranking.indexOf(id).let { if (it < 0) 0.0 else 1.0 / (k + it + 1) } }
            id to score
        }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenByDescending { versionOf.getValue(it.first) })

        val hits = store.findHybrid("alpha", embedder, FindOptions(limit = 20)).items
        assertEquals(expected.map { it.first }, hits.map { it.document.id })
        for ((e, h) in expected.zip(hits)) assertTrue(abs(e.second - h.score) < 1e-12)
        assertEquals(hits.size, hits.map { it.document.id }.toSet().size, "each document appears once")
        val neighbour = ids[3]
        assertTrue(neighbour in hits.map { it.document.id } && neighbour !in lexical, "a semantic-only document is found")
        assertNull(hits.single { it.document.id == neighbour }.snippet, "semantic-only hits have no snippet")
        assertNotNull(hits.first { it.document.id in lexical }.snippet)

        val lexicalOnly = store.findHybrid("alpha", embedder, FindOptions(limit = 20), FusionOptions(semanticWeight = 0.0)).items
        assertEquals(lexical, lexicalOnly.map { it.document.id }, "weight 0 removes a ranking entirely")
        val page = store.findHybrid("alpha", embedder, FindOptions(limit = 2))
        val rest = store.findHybrid("alpha", embedder, FindOptions(limit = 2, cursor = page.nextCursor))
        assertEquals(hits.map { it.document.id }.take(4), (page.items + rest.items).map { it.document.id })
        assertFailsWith<IllegalArgumentException> { FusionOptions(candidates = 0) }
        store.close()
    }

    @Test fun theEmbedderRunsOutsideTheLockAndSupersededVersionsAreSkipped() = storeTest { dir ->
        val path = dir.file("a.kvid")
        val store = Kvid.create(path)
        val first = store.put("first note")
        store.put("second note")
        val embedder = HashingEmbedder().apply { entered = CompletableDeferred(); release = CompletableDeferred() }
        val indexing = async { store.indexVectors(embedder) }
        embedder.entered!!.await()
        store.update(first, "first note, edited while embedding")
        assertEquals(2, store.list().items.size, "reads and writes proceed while the model runs")
        embedder.release!!.complete(Unit)
        // The first batch stores only "second note"; the next batch embeds the edited version.
        assertEquals(VectorIndexReport(embedded = 2, pending = 0), indexing.await())
        assertEquals(listOf("first note", "second note", "first note, edited while embedding"), embedder.inputs)
        assertEquals(VectorStatus(embedder.spec, 2, 0), store.vectorStatus())
        store.close()
        assertEquals(2L, rawCount(path, "SELECT count(*) FROM version_vectors"), "no vector was stored for the superseded version")
    }

    @Test fun vectorsSurviveRebuildSnapshotAndReopenAndVerifyChecksThem() = storeTest { dir ->
        val path = dir.file("a.kvid")
        val store = Kvid.create(path)
        val embedder = HashingEmbedder()
        store.transaction { (1..4).forEach { put("note number $it about plans") } }
        store.indexVectors(embedder)
        val before = store.findSimilar("plans", embedder).items.map { it.document.id }
        store.rebuildIndex()
        assertEquals(VectorStatus(embedder.spec, 4, 0), store.vectorStatus(), "rebuilding the text index keeps vectors")
        assertTrue(store.verify().ok, store.verify().problems.joinToString())
        store.snapshot(dir.file("copy.kvid"))
        Kvid.openReadOnly(dir.file("copy.kvid")).use { copy ->
            assertEquals(before, copy.findSimilar("plans", embedder).items.map { it.document.id })
            assertFailsWith<KvidException.ReadOnly> { copy.indexVectors(embedder) }
            assertFailsWith<KvidException.ReadOnly> { copy.resetVectors() }
        }
        store.close()
        Kvid.open(path).use { reopened -> assertEquals(before, reopened.findSimilar("plans", embedder).items.map { it.document.id }) }

        BundledSQLiteDriver().open(path).use { it.execSQL("UPDATE version_vectors SET vector = x'00' WHERE version_id = (SELECT min(version_id) FROM version_vectors)") }
        Kvid.open(path).use { damaged ->
            val report = damaged.verify()
            assertFalse(report.ok)
            assertTrue(report.problems.any { it.contains("dimensions") }, report.problems.joinToString())
            assertFailsWith<KvidException.Corrupt> { damaged.findSimilar("plans", embedder) }
        }
    }

    @Test fun invalidVectorsAreRejectedBeforeAnythingIsWritten() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        store.put("text")
        val wrongSize = BrokenEmbedder { texts -> texts.map { FloatArray(3) } }
        assertEquals("KV_INVALID_VECTOR", assertFailsWith<KvidException.InvalidVector> { store.indexVectors(wrongSize) }.code)
        assertFailsWith<KvidException.InvalidVector> { store.indexVectors(BrokenEmbedder { texts -> texts.map { floatArrayOf(1f, Float.NaN, 0f, 0f) } }) }
        assertFailsWith<KvidException.InvalidVector> { store.indexVectors(BrokenEmbedder { emptyList() }) }
        assertEquals(VectorStatus(null, 0, 1), store.vectorStatus(), "no vector and no spec were recorded")
        assertFailsWith<KvidException.InvalidVector> { store.findSimilar(FloatArray(3), wrongSize.spec) }
        assertFailsWith<IllegalArgumentException> { EmbeddingSpec("m", "d", "t", 0, "mean", true) }
        assertFailsWith<IllegalArgumentException> { EmbeddingSpec("m", "d", "t", EmbeddingSpec.MAX_DIMENSIONS + 1, "mean", true) }
        store.close()
    }

    @Test fun schemaTwoStoresMigrateWhenOpenedWritable() = storeTest { dir ->
        val path = dir.file("a.kvid")
        Kvid.create(path).use { it.put("kept across the migration") }
        BundledSQLiteDriver().open(path).use { raw ->
            raw.execSQL("DROP TABLE version_vectors")
            raw.execSQL("PRAGMA user_version = 2")
        }
        assertFailsWith<KvidException.UnsupportedFormat> { Kvid.openReadOnly(path) }
        Kvid.open(path).use { migrated ->
            assertEquals(listOf("kept across the migration"), migrated.list().items.map { it.body })
            assertEquals(1, migrated.indexVectors(HashingEmbedder()).embedded)
            assertTrue(migrated.verify().ok, migrated.verify().problems.joinToString())
        }
        assertEquals(3L, rawCount(path, "PRAGMA user_version"))
        Kvid.openReadOnly(path).use { assertEquals(1L, it.vectorStatus().embedded) }
    }
}
