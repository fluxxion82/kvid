package com.kvid.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Milestone 3: query syntax, filters, Unicode handling and ranking (persistence contract, section 7). */
class SearchTest {

    private suspend fun Kvid.ids(query: String, options: FindOptions = FindOptions()): List<DocumentId> =
        find(query, options).items.map { it.document.id }

    @Test fun uriPrefixesCompareEmbeddedNulLiterally() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        try {
            store.put("note", PutOptions(uri = "a"))
            store.put("note", PutOptions(uri = "a\u0000x"))
            val expected = store.put("note", PutOptions(uri = "a\u0000b/child"))
            assertEquals(listOf(expected), store.list(ListOptions(uriPrefix = "a\u0000b")).items.map { it.id })
            assertEquals(listOf(expected), store.ids("note", FindOptions(uriPrefix = "a\u0000b")))
        } finally { store.close() }
    }

    @Test fun tagFiltersHaveTheSameLengthBoundsAsWrites() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        try {
            val tags = listOf("x".repeat(Limits().tagCodePoints + 1))
            assertFailsWith<KvidException.LimitExceeded> { store.list(ListOptions(tags = tags)) }
            assertFailsWith<KvidException.LimitExceeded> { store.find("x", FindOptions(tags = tags)) }
        } finally { store.close() }
    }

    @Test fun configuredLargeTagSetsRemainSearchable() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"), StoreOptions(limits = Limits(tagsPerVersion = 1024)))
        try {
            val tags = (1..1001).map { "t$it" }
            val id = store.put("searchable", PutOptions(tags = tags))
            assertEquals(listOf(id), store.list(ListOptions(tags = tags)).items.map { it.id })
            assertEquals(listOf(id), store.ids("searchable", FindOptions(tags = tags)))
        } finally { store.close() }
    }

    @Test fun wideAcceptedFiltersProduceUsableCursors() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"), StoreOptions(limits = Limits(uriBytes = Limits.MAX_URI_BYTES)))
        try {
            val uri = "\"".repeat(Limits.MAX_URI_BYTES)
            repeat(3) { store.put("note", PutOptions(uri = uri)) }
            val options = ListOptions(limit = 1, uriPrefix = uri)
            val page = store.list(options)
            assertNotNull(page.nextCursor)
            assertEquals(1, store.list(options.copy(cursor = page.nextCursor)).items.size)
        } finally { store.close() }
    }

    @Test fun embeddedNulInPlainTextDoesNotProduceSyntaxErrors() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        try {
            val id = store.put("rock roll")
            assertEquals(listOf(id), store.ids("rock\u0000roll"))
            assertEquals(listOf(id), store.ids("rock\u0000"))
        } finally { store.close() }
    }

    @Test fun plainQueriesTreatOperatorsQuotesAndPunctuationAsText() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val both = store.put("rock and roll all night")
        val rock = store.put("rock only")
        val roll = store.put("roll OR nothing")

        assertEquals(listOf(both), store.ids("rock AND roll"), "AND is the word 'and'")
        assertEquals(listOf(both), store.ids("rock roll"), "every piece is required by default")
        assertEquals(setOf(both, rock, roll), store.ids("rock roll", FindOptions(match = MatchMode.ANY)).toSet())
        assertEquals(setOf(both, rock), store.ids("(rock)").toSet(), "parentheses do not group")
        assertEquals(setOf(both, rock), store.ids("\"rock\"").toSet(), "quotes do not mark phrases")
        assertEquals(setOf(both, rock), store.ids("rock*").toSet(), "a trailing star is not a prefix operator")
        assertTrue(store.ids("title:rock").isEmpty(), "column filters are text: no document has 'title' next to 'rock'")
        assertEquals(setOf(both, rock), store.ids("rock ??? !!!").toSet(), "pieces without letters or digits are ignored")
        assertTrue(store.ids("???").isEmpty())
        assertTrue(store.ids("").isEmpty())
        assertTrue(store.ids("🙂 —").isEmpty(), "emoji and dashes are not tokens")
        assertNull(store.find("???").nextCursor)
        assertEquals(listOf(both), store.ids("ROCK Roll rock"), "case variants are one piece")
        store.close()
    }

    @Test fun plainPiecesMatchTheirTokensAdjacently() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val hyphenated = store.put("a state-of-the-art design")
        val scattered = store.put("the art of a state")
        assertEquals(listOf(hyphenated), store.ids("state-of-the-art"), "one piece is a phrase of its tokens")
        assertEquals(setOf(hyphenated, scattered), store.ids("state of the art").toSet(), "separate pieces may occur anywhere")
        store.close()
    }

    @Test fun fts5SyntaxIsOptInAndRejectionsAreTyped() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val both = store.put("rock and roll all night", PutOptions(title = "Anthem"))
        val rock = store.put("rock only", PutOptions(title = "Rock"))
        val roll = store.put("roll OR nothing")
        val fts = FindOptions(syntax = QuerySyntax.FTS5)
        assertEquals(listOf(both), store.ids("\"rock and roll\"", fts))
        assertEquals(setOf(both, rock, roll), store.ids("rock OR roll", fts).toSet())
        assertEquals(listOf(rock), store.ids("title:rock", fts))
        assertEquals(setOf(both, rock), store.ids("roc*", fts).toSet())
        assertEquals(listOf(both), store.ids("NEAR(rock night, 5)", fts))
        assertEquals(listOf(both), store.ids("rock AND roll", fts))
        for (bad in listOf("rock AND", "\"unterminated", "", "   ", "nosuch:rock", "rock NOT")) {
            val e = assertFailsWith<KvidException.InvalidQuery>("query '$bad'") { store.find(bad, fts) }
            assertEquals("KV_INVALID_QUERY", e.code)
        }
        store.close()
    }

    @Test fun unicodeTextIsCaseFoldedDiacriticInsensitiveAndNormalized() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val precomposed = store.put("Café au lait", PutOptions(title = "Français"))
        val decomposed = store.put("Café noir", PutOptions(tags = listOf("café")))   // NFD input
        val ascii = store.put("plain cafe")
        assertEquals("Café noir", store.get(decomposed)!!.body, "bodies are stored in NFC")
        assertEquals(listOf("café"), store.get(decomposed)!!.version.tags, "tags are stored in NFC")
        val all = setOf(precomposed, decomposed, ascii)
        assertEquals(all, store.ids("CAFÉ").toSet(), "case and diacritics fold")
        assertEquals(all, store.ids("café").toSet(), "queries are normalized too")
        assertEquals(all, store.ids("cafe").toSet())
        assertEquals(listOf(decomposed), store.ids("cafe", FindOptions(tags = listOf("café"))), "tag filters are normalized")
        assertEquals(listOf(precomposed), store.ids("français"))
        assertEquals(listOf(precomposed), store.ids("francais"))
        store.close()
    }

    @Test fun filtersCombineWithAndAcrossListAndFind() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val a = store.put("note alpha", PutOptions(tags = listOf("work", "urgent"), uri = "notes/2026/a", eventTimeMs = 1000))
        val b = store.put("note beta", PutOptions(tags = listOf("work"), uri = "notes/2026/b", eventTimeMs = 2000))
        val c = store.put("note gamma", PutOptions(tags = listOf("urgent"), uri = "notes/2025/c", eventTimeMs = 3000))
        val d = store.put("note delta", PutOptions(uri = "n*tes/x", eventTimeMs = 4000))
        store.put("note epsilon", PutOptions(eventTimeMs = 5000))

        assertEquals(listOf(a), store.ids("note", FindOptions(tags = listOf("work", "urgent"))), "every tag is required")
        assertEquals(listOf(b, a), store.list(ListOptions(tags = listOf("work"))).items.map { it.id })
        assertEquals(listOf(b, a), store.list(ListOptions(uriPrefix = "notes/2026/")).items.map { it.id })
        assertEquals(setOf(c, b, a), store.ids("note", FindOptions(uriPrefix = "notes/")).toSet())
        assertEquals(listOf(d), store.list(ListOptions(uriPrefix = "n*")).items.map { it.id }, "glob characters in the prefix are literal")
        assertEquals(listOf(d, c, b, a), store.list(ListOptions(uriPrefix = "")).items.map { it.id }, "an empty prefix selects documents with a uri")
        assertEquals(listOf(b), store.list(ListOptions(tags = listOf("work"), uriPrefix = "notes/2026/", sinceEventTimeMs = 1500)).items.map { it.id })
        assertEquals(listOf(b), store.ids("note", FindOptions(tags = listOf("work"), uriPrefix = "notes/", sinceEventTimeMs = 1500, untilEventTimeMs = 2500)))
        assertTrue(store.ids("note", FindOptions(tags = listOf("nope"))).isEmpty())
        assertFailsWith<KvidException.Usage> { store.list(ListOptions(tags = listOf(""))) }

        val page = store.find("note", FindOptions(limit = 1, uriPrefix = "notes/"))
        assertNotNull(page.nextCursor)
        assertFailsWith<KvidException.Usage> { store.find("note", FindOptions(limit = 1, uriPrefix = "notes/2026/", cursor = page.nextCursor)) }
        assertFailsWith<KvidException.Usage> { store.find("note", FindOptions(limit = 1, uriPrefix = "notes/", match = MatchMode.ANY, cursor = page.nextCursor)) }
        assertFailsWith<KvidException.Usage> { store.find("note", FindOptions(limit = 1, uriPrefix = "notes/", syntax = QuerySyntax.FTS5, cursor = page.nextCursor)) }
        assertFailsWith<KvidException.Usage> { store.find("note", FindOptions(limit = 1, uriPrefix = "notes/", tags = listOf("work"), cursor = page.nextCursor)) }
        assertEquals(1, store.find("note", FindOptions(limit = 1, uriPrefix = "notes/", cursor = page.nextCursor)).items.size)
        store.close()
    }

    @Test fun scoresMatchAnIndependentBm25Calculation() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val corpus = listOf(
            "Budget review" to "The budget meeting covered the plan for next quarter and the budget risks.",
            "Standup" to "Blocked on the plan review; no budget questions.",
            null to "Groceries: milk, eggs, bread, and a plan for dinner.",
            "Plan" to "plan plan plan",
            "Holiday" to "Beach, sun, and sea.",
            "Budget review" to "The budget meeting covered the plan for next quarter and the budget risks."   // exact duplicate: a tie
        )
        val ids = store.transaction { corpus.map { (title, body) -> put(body, PutOptions(title = title)) } }
        val reference = ReferenceBm25(corpus)
        for (query in listOf("budget", "plan", "budget plan", "review", "sea", "plan review budget")) {
            val hits = store.find(query).items
            val terms = query.split(" ")
            val expected = corpus.indices.filter { i -> terms.all { reference.contains(i, it) } }
                .map { i -> i to reference.score(i, terms) }
                .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenByDescending { it.first })
            assertEquals(expected.map { ids[it.first] }, hits.map { it.document.id }, "order for '$query'")
            for ((exp, hit) in expected.zip(hits)) {
                assertTrue(abs(exp.second - hit.score) < 1e-9, "score for '$query': expected ${exp.second}, got ${hit.score}")
            }
        }
        assertEquals(listOf(ids[5], ids[0]), store.ids("quarter"), "equal scores order by newest version first")
        store.close()
    }

    @Test fun rebuiltIndexReturnsIdenticalResults() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val ids = store.transaction { (1..30).map { put("entry $it about ${if (it % 3 == 0) "budget" else "travel"} and plans", PutOptions(title = "Entry $it", tags = listOf("t${it % 4}"))) } }
        store.update(ids[0], "entry 1 rewritten about budget", PutOptions(title = "Entry 1"))
        store.delete(ids[1])
        val queries = listOf("budget", "travel plans", "entry", "rewritten")
        suspend fun snapshot() = queries.map { q -> store.find(q, FindOptions(limit = 50)).items.map { it.document.id to it.score } }
        val before = snapshot()
        store.rebuildIndex()
        assertEquals(before, snapshot(), "rebuild from authoritative versions reproduces ids, order and scores")
        assertTrue(store.verify().ok)
        assertTrue(before[3].single().first == ids[0])
        assertTrue(before.flatten().none { it.first == ids[1] }, "the deleted document stays out of the rebuilt index")
        store.close()
    }

    @Test fun indexedTextIsStoredOnceAndDriftIsDetected() = storeTest { dir ->
        val path = dir.file("a.kvid")
        val store = Kvid.create(path)
        val id = store.put("the original searchable text", PutOptions(title = "Original"))
        BundledSQLiteDriver().open(path).use { raw ->
            raw.prepare("SELECT count(*) FROM pragma_table_info('current') WHERE name IN ('title', 'body')").use { st ->
                st.step(); assertEquals(0L, st.getLong(0), "the projection holds no copy of the text")
            }
            raw.execSQL("UPDATE versions SET body = 'replaced behind the index' WHERE doc_id = '$id'")
        }
        val report = store.verify()
        assertFalse(report.ok)
        assertTrue(report.problems.any { it.startsWith("fts integrity-check") }, report.problems.joinToString())
        store.rebuildIndex()
        assertTrue(store.verify().ok, store.verify().problems.joinToString())
        assertEquals(listOf(id), store.ids("replaced"))
        assertTrue(store.ids("original searchable").isEmpty())
        store.close()
    }

    @Test fun tagCountsCoverLiveCurrentVersionsOnly() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val a = store.put("a", PutOptions(tags = listOf("work", "urgent")))
        store.put("b", PutOptions(tags = listOf("work")))
        val c = store.put("c", PutOptions(tags = listOf("home", "urgent")))
        assertEquals(listOf(TagCount("urgent", 2), TagCount("work", 2), TagCount("home", 1)), store.tagCounts())
        store.update(a, "a edited", PutOptions(tags = listOf("work")))
        store.delete(c)
        assertEquals(listOf(TagCount("work", 2)), store.tagCounts(), "superseded and deleted versions do not count")
        assertEquals(listOf(TagCount("work", 2)), store.tagCounts(limit = 1))
        assertEquals(store.tagCounts(), store.transaction { tagCounts() })
        store.close()
    }

    @Test fun queryBoundsApply() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        store.put("x")
        assertFailsWith<KvidException.LimitExceeded> { store.find("x".repeat(Limits().queryBytes + 1)) }
        assertFailsWith<KvidException.LimitExceeded> { store.find((1..Limits.MAX_QUERY_TERMS + 1).joinToString(" ") { "t$it" }) }
        assertTrue(store.find((1..Limits.MAX_QUERY_TERMS).joinToString(" ") { "t$it" }).items.isEmpty())
        store.close()
    }
}

/**
 * FTS5's `bm25()` as implemented in fts5_aux.c: k1 = 1.2, b = 0.75, IDF `log((N - n + 0.5) / (n + 0.5))`
 * floored at 1e-6, document length and average length summed over all columns, unit column weights.
 * The corpus is ASCII so tokenization is lowercase runs of letters and digits, as unicode61 produces.
 */
private class ReferenceBm25(corpus: List<Pair<String?, String>>) {
    private val docs: List<List<String>> = corpus.map { (title, body) -> tokens(title.orEmpty()) + tokens(body) }
    private val avgdl = docs.sumOf { it.size }.toDouble() / docs.size

    fun contains(i: Int, term: String) = term.lowercase() in docs[i]

    fun score(i: Int, terms: List<String>): Double {
        var score = 0.0
        for (term in terms) {
            val t = term.lowercase()
            val n = docs.count { t in it }
            var idf = ln((docs.size - n + 0.5) / (n + 0.5))
            if (idf <= 0.0) idf = 1e-6
            val tf = docs[i].count { it == t }.toDouble()
            // Same operation order as fts5Bm25Function, so equal inputs round identically.
            score += idf * ((tf * (1.2 + 1.0)) / (tf + 1.2 * (1 - 0.75 + 0.75 * docs[i].size / avgdl)))
        }
        return score
    }

    private fun tokens(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
}
