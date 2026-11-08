package com.kvid.embed

import com.kvid.store.EmbeddingSpec
import com.kvid.store.Kvid
import com.kvid.store.PutOptions
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * A store indexed on one platform reopens on another (implementation plan, R12): the same model files
 * give an equal spec, nothing is embedded again, and semantic search returns the same top hits with the
 * same scores. Two halves, each switched on by an environment variable, so the writer and the reader run
 * in different test processes and on different platforms:
 *
 * - **Writer**, `KVID_PORTABLE_STORE_OUT=<dir>` (scripts/check-local.sh's jvm and portable-store parts, on
 *   the JVM): indexes [NotesCorpus] into `<dir>/notes.kvid` and records the spec and the top hit and
 *   score of each query in `<dir>/expected.json`.
 * - **Reader**, `KVID_PORTABLE_STORE_IN=<dir>` (the ios part, on the simulator): opens a copy of that
 *   store and checks it against `expected.json`: spec equal to this platform's embedder's, no vector
 *   embedded again, the same top hit per query, scores within [SCORE_TOLERANCE].
 *
 * Both use the embedding fixture's model files with the parity test's skip-or-fail rule. Without its
 * variable each half prints why it is skipped; with `KVID_REQUIRE_PORTABLE_STORE=true` a reader without
 * a store fails instead.
 */
class OnnxEmbedderPortabilityTest {
    @Test fun writesAPortableStore() = runTest(timeout = 10.minutes) {
        val out = testEnvironment("KVID_PORTABLE_STORE_OUT")
        if (out == null) {
            println("[kvid] portable store writer skipped: set KVID_PORTABLE_STORE_OUT to a directory")
            return@runTest
        }
        val fixture = EmbeddingFixture.loadOrSkip("portable store writer") ?: return@runTest
        val dir = Path(out)
        SystemFileSystem.createDirectories(dir)
        SystemFileSystem.list(dir).forEach { it.deleteRecursively() }
        val path = Path(dir, STORE).toString()

        val hits = ArrayList<Triple<String, String, Double>>()
        var opened: EmbeddingSpec? = null
        val store = Kvid.create(path)
        try {
            store.transaction { NotesCorpus.notes.forEach { (title, body) -> put(body, PutOptions(title = title)) } }
            OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath).use { embedder ->
                opened = embedder.spec
                assertEquals(NotesCorpus.notes.size, store.indexVectors(embedder).embedded)
                for ((query, title) in NotesCorpus.queries) {
                    val top = store.findSimilar(query, embedder).items.first()
                    assertEquals(title, top.document.title, "semantic search for '$query'")
                    hits += Triple(query, title, top.score)
                }
            }
        } finally {
            store.close()
        }
        val spec = opened!!
        // A closed store is one file (journal mode DELETE): that file is what moves between platforms.
        assertEquals(listOf(STORE), SystemFileSystem.list(dir).map { it.name }, "files next to the closed store")

        val expected = buildJsonObject {
            put("writer", testPlatform)
            put("spec", Json.encodeToJsonElement(EmbeddingSpec.serializer(), spec))
            put("hits", buildJsonArray {
                hits.forEach { (query, title, score) ->
                    add(buildJsonObject { put("query", query); put("title", title); put("score", score) })
                }
            })
        }
        Path(dir, EXPECTED).writeText(expected.toString())
        println("[kvid-portable] wrote $path writer=$testPlatform documents=${NotesCorpus.notes.size} queries=${hits.size} " +
            "model=${spec.modelDigest}")
    }

    @Test fun reopensAPortableStore() = runTest(timeout = 10.minutes) {
        val input = testEnvironment("KVID_PORTABLE_STORE_IN")
        val required = testEnvironment("KVID_REQUIRE_PORTABLE_STORE") == "true"
        val storeFile = input?.let { fixtureFile("$it/$STORE") }
        val expectedFile = input?.let { fixtureFile("$it/$EXPECTED") }
        if (storeFile == null || expectedFile == null) {
            if (required) fail("KVID_REQUIRE_PORTABLE_STORE=true but there is no $STORE and $EXPECTED at '$input'")
            println("[kvid] portable store reader skipped: set KVID_PORTABLE_STORE_IN to a directory written by the writer")
            return@runTest
        }
        val fixture = EmbeddingFixture.loadOrSkip("portable store reader") ?: return@runTest
        val expected = Json.parseToJsonElement(expectedFile.readText()).jsonObject
        val writer = expected.getValue("writer").jsonPrimitive.content
        val recordedSpec = Json.decodeFromJsonElement(EmbeddingSpec.serializer(), expected.getValue("spec"))
        val recordedHits = expected.getValue("hits").jsonArray.map { it.jsonObject }

        val dir = createTempDirectory("kvid-portable")
        try {
            // A copy: the reader must not change the store it was given.
            val copy = Path(dir, STORE)
            SystemFileSystem.source(storeFile).use { source -> SystemFileSystem.sink(copy).buffered().use { it.transferFrom(source) } }
            val store = Kvid.open(copy.toString())
            var maxScoreDiff = 0.0
            try {
                OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath).use { embedder ->
                    assertEquals(recordedSpec, embedder.spec, "the same model files give the writer's spec on $testPlatform")
                    val status = store.vectorStatus()
                    assertEquals(embedder.spec, status.spec, "the store's recorded spec")
                    assertEquals(NotesCorpus.notes.size.toLong(), status.embedded)
                    assertEquals(0L, status.pending)
                    val report = store.indexVectors(embedder)
                    assertEquals(0, report.embedded, "vectors written on $writer are embedded again")
                    assertEquals(NotesCorpus.queries.size, recordedHits.size, "queries in $EXPECTED")
                    for (hit in recordedHits) {
                        val query = hit.getValue("query").jsonPrimitive.content
                        val title = hit.getValue("title").jsonPrimitive.content
                        val score = hit.getValue("score").jsonPrimitive.double
                        val top = store.findSimilar(query, embedder).items.first()
                        assertEquals(title, top.document.title, "top hit for '$query' on $testPlatform, written on $writer")
                        val diff = abs(top.score - score)
                        println("[kvid] portable '$query': ${top.document.title} ${fmt("%.6f", top.score)} here, ${fmt("%.6f", score)} on $writer")
                        maxScoreDiff = maxOf(maxScoreDiff, diff)
                        assertTrue(diff <= SCORE_TOLERANCE, "score for '$query': ${top.score} here, $score on $writer")
                    }
                }
            } finally {
                store.close()
            }
            println("[kvid-portable] reopened writer=$writer reader=$testPlatform spec=equal embedded_again=0 " +
                "top1=${recordedHits.size}/${recordedHits.size} max_score_diff=${fmt("%.2e", maxScoreDiff)} tolerance=${fmt("%.0e", SCORE_TOLERANCE)}")
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val STORE = "notes.kvid"
        const val EXPECTED = "expected.json"
        const val SCORE_TOLERANCE = 1e-4
    }
}
