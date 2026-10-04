package com.kvid.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Persistence contract test matrix (section 11) for the parts Milestone 2 implements. */
class KvidStoreTest {

    @Test fun caughtWriteFailureDoesNotCommitPartialDocumentChanges() = storeTest { dir ->
        val store = Kvid.create(dir.file("notes.kvid"), StoreOptions(uniqueUri = true))
        try {
            val existing = store.put("original", PutOptions(uri = "same"))
            store.transaction {
                assertFailsWith<KvidException.AlreadyExists> {
                    put("failed", PutOptions(documentId = "failed", uri = "same"))
                }
                assertNull(get("failed"))
                assertFailsWith<KvidException.AlreadyExists> {
                    update(existing, "failed update", PutOptions(uri = "same-other"))
                    put("second failure", PutOptions(uri = "same-other"))
                }
                put("survives")
            }
            assertNull(store.get("failed"))
            assertTrue(store.verify().ok)
        } finally { store.close() }
    }

    @Test fun verifyChecksProjectionContentNotJustVersionPointers() = storeTest { dir ->
        val path = dir.file("notes.kvid")
        val store = Kvid.create(path)
        try {
            store.put("authoritative", PutOptions(uri = "note://a"))
            BundledSQLiteDriver().open(path).use { it.execSQL("UPDATE current SET uri = 'note://wrong'") }
            assertFalse(store.verify().ok)
        } finally { store.close() }
    }

    @Test fun readOnlyVerifyReportsMissingFtsCoverage() = storeTest { dir ->
        val path = dir.file("notes.kvid")
        val store = Kvid.create(path)
        try {
            store.put("test")
            val ro = Kvid.openReadOnly(path)
            try { assertTrue(ro.verify().unchecked.isNotEmpty()) } finally { ro.close() }
        } finally { store.close() }
    }

    @Test fun cancellationWhileBeginningDoesNotLeakTransaction() = storeTest { dir ->
        val path = dir.file("notes.kvid")
        val store = Kvid.create(path)
        val lock = BundledSQLiteDriver().open(path)
        try {
            lock.execSQL("BEGIN IMMEDIATE")
            val job = launch { store.transaction { put("cancelled") } }
            withContext(Dispatchers.Default) { delay(100) }
            job.cancel()
            lock.execSQL("ROLLBACK")
            job.join()
            store.put("later")
            assertEquals(1, store.stats().liveDocuments)
        } finally { lock.close(); store.close() }
    }

    @Test fun nestedDifferentStoreCannotHideOuterTransactionReentry() = storeTest { dir ->
        val a = Kvid.create(dir.file("a.kvid"))
        val b = Kvid.create(dir.file("b.kvid"))
        try {
            withContext(Dispatchers.Default) { withTimeout(2000) {
                a.transaction {
                    b.transaction { assertFailsWith<KvidException.Usage> { a.get("missing") } }
                }
            } }
        } finally { a.close(); b.close() }
    }

    @Test fun snapshotRefusesLogicallyInvalidSource() = storeTest { dir ->
        val path = dir.file("notes.kvid")
        val store = Kvid.create(path)
        try {
            store.put("authoritative")
            BundledSQLiteDriver().open(path).use { it.execSQL("UPDATE current SET event_time_ms = event_time_ms + 1") }
            assertFailsWith<KvidException.Corrupt> { store.snapshot(dir.file("copy.kvid")) }
            assertFalse(dir.exists("copy.kvid"))
        } finally { store.close() }
    }

    @Test fun storedTitleLengthsAreCheckedBeforeMaterializingText() = storeTest { dir ->
        val path = dir.file("notes.kvid")
        val store = Kvid.create(path)
        try {
            val id = store.put("body")
            BundledSQLiteDriver().open(path).use { raw ->
                raw.prepare("UPDATE versions SET title = ?").use { st ->
                    st.bindText(1, "x".repeat(Limits.MAX_TITLE_BYTES + 1)); st.step()
                }
            }
            assertFailsWith<KvidException.Corrupt> { store.get(id) }
        } finally { store.close() }
    }

    @Test fun cursorRejectsDifferentQueriesEvenWithHashCollisions() = storeTest { dir ->
        val store = Kvid.create(dir.file("notes.kvid"))
        try {
            repeat(3) { store.put("Aa BB") }
            val page = store.find("Aa", FindOptions(limit = 1))
            assertNotNull(page.nextCursor)
            assertFailsWith<KvidException.Usage> { store.find("BB", FindOptions(limit = 1, cursor = page.nextCursor)) }
        } finally { store.close() }
    }

    @Test fun snapshotPublicationNeverReplacesAnExistingFile() = storeTest { dir ->
        val src = dir.file("source")
        val dest = dir.file("destination")
        overwriteFile(src, byteArrayOf(1))
        overwriteFile(dest, byteArrayOf(2))
        assertFailsWith<KvidException.AlreadyExists> { FileSync.publishNoReplace(src, dest) }
        assertContentEquals(byteArrayOf(2), readFile(dest))
        assertContentEquals(byteArrayOf(1), readFile(src))
    }

    @Test fun cancelledOpenReleasesTheConnectionAndWriterReservation() = storeTest { dir ->
        val path = dir.file("notes.kvid")
        Kvid.create(path).close()
        val lock = BundledSQLiteDriver().open(path)
        try {
            lock.execSQL("BEGIN IMMEDIATE")
            val job = launch { Kvid.open(path).close() }
            withContext(Dispatchers.Default) { delay(100) }
            job.cancel()
            lock.execSQL("ROLLBACK")
            job.join()
            val reopened = Kvid.open(path)
            try { reopened.put("works") } finally { reopened.close() }
        } finally { lock.close() }
    }

    @Test fun automaticSqliteRollbackMakesTheSessionUnusable() = storeTest { dir ->
        val path = dir.file("notes.kvid")
        val store = Kvid.create(path)
        try {
            BundledSQLiteDriver().open(path).use {
                it.execSQL("CREATE TRIGGER fail_write BEFORE INSERT ON versions WHEN new.body = 'fail' BEGIN SELECT RAISE(ROLLBACK, 'injected rollback'); END")
            }
            assertFailsWith<KvidException.Io> {
                store.transaction {
                    put("before")
                    assertFailsWith<KvidException.Io> { put("fail") }
                    put("after")
                }
            }
            assertEquals(0, store.stats().liveDocuments)
        } finally { store.close() }
    }

    @Test fun allMaintenanceThatCanChangeResultsExpiresCursors() = storeTest { dir ->
        val store = Kvid.create(dir.file("notes.kvid"))
        try {
            repeat(3) { store.put("match $it") }
            val beforeVacuum = store.find("match", FindOptions(limit = 1)).nextCursor
            store.vacuum()
            assertFailsWith<KvidException.CursorExpired> { store.find("match", FindOptions(limit = 1, cursor = beforeVacuum)) }
            val beforeRebuild = store.find("match", FindOptions(limit = 1)).nextCursor
            store.rebuildIndex()
            assertFailsWith<KvidException.CursorExpired> { store.find("match", FindOptions(limit = 1, cursor = beforeRebuild)) }
        } finally { store.close() }
    }

    @Test fun oversizedStoredTagsAreRejected() = storeTest { dir ->
        val path = dir.file("notes.kvid")
        val store = Kvid.create(path)
        try {
            val id = store.put("body", PutOptions(tags = listOf("small")))
            for (tag in listOf("x".repeat(Limits.MAX_TAG_CODE_POINTS + 1), "\u0000" + "x".repeat(4 * Limits.MAX_TAG_CODE_POINTS + 1))) {
                BundledSQLiteDriver().open(path).use { raw ->
                    raw.prepare("UPDATE version_tags SET tag = ?").use { st -> st.bindText(1, tag); st.step() }
                }
                assertFailsWith<KvidException.Corrupt> { store.get(id) }
            }
        } finally { store.close() }
    }

    @Test fun olderSchemaWithoutAMigrationIsRefused() = storeTest { dir ->
        val path = dir.file("notes.kvid")
        Kvid.create(path).close()
        BundledSQLiteDriver().open(path).use { it.execSQL("PRAGMA user_version = 0") }
        assertFailsWith<KvidException.UnsupportedFormat> { Kvid.open(path).close() }
        BundledSQLiteDriver().open(path).use { raw ->
            raw.prepare("PRAGMA user_version").use { st -> st.step(); assertEquals(0, st.getLong(0)) }
        }
    }

    @Test fun failedCommitInvalidatesTheConnectionAndReleasesItsReservation() = storeTest { dir ->
        val path = dir.file("notes.kvid")
        val store = Kvid.create(path)
        try {
            BundledSQLiteDriver().open(path).use {
                it.execSQL("CREATE TRIGGER fail_commit BEFORE UPDATE OF commit_seq ON kvid_meta BEGIN SELECT RAISE(ROLLBACK, 'injected commit failure'); END")
            }
            assertFailsWith<KvidException.Io> { store.put("not committed") }
            assertFailsWith<KvidException.Closed> { store.get("missing") }
            val reopened = Kvid.open(path)
            try { assertEquals(0, reopened.stats().liveDocuments) } finally { reopened.close() }
        } finally { store.close() }
    }

    @Test fun exclusiveCreationNeverTruncatesAnExistingFile() = storeTest { dir ->
        val path = dir.file("reserved")
        FileSync.createExclusive(path)
        assertTrue(dir.exists("reserved"))
        overwriteFile(path, byteArrayOf(7))
        assertFailsWith<KvidException.AlreadyExists> { FileSync.createExclusive(path) }
        assertContentEquals(byteArrayOf(7), readFile(path))
    }

    // ---------------------------------------------------------------- lifecycle and portability

    @Test fun createPutCloseReopenRoundTrip() = storeTest { dir ->
        val path = dir.file("notes.kvid")
        val meta = buildJsonObject { put("source", "test"); put("priority", 3) }
        val store = Kvid.create(path)
        val a = store.put("Met Sam about the Q4 plan", PutOptions(title = "standup", tags = listOf("work", "q4"), metadata = meta, uri = "note://a"))
        val b = store.put("Milk, eggs, bread", PutOptions(title = "groceries"))
        assertTrue(Ids.isUuid(a)); assertTrue(Ids.isUuid(b))
        store.close()
        assertEquals(listOf("notes.kvid"), dir.listNames(), "exactly one file after a clean close")

        val reopened = Kvid.open(path)
        val doc = assertNotNull(reopened.get(a))
        assertEquals("Met Sam about the Q4 plan", doc.body)
        assertEquals("standup", doc.title)
        assertEquals(listOf("q4", "work"), doc.version.tags)
        assertEquals(meta, doc.version.metadata)
        assertEquals("note://a", doc.version.uri)
        assertEquals(1L, doc.version.seq)
        assertEquals(2L, assertNotNull(reopened.get(b)).version.seq, "each auto-commit write is its own commit")
        val stats = reopened.stats()
        assertEquals(2L, stats.documents); assertEquals(2L, stats.liveDocuments); assertEquals(2L, stats.versions); assertEquals(2L, stats.commitSeq)
        assertTrue(reopened.verify().ok, reopened.verify().problems.joinToString())
        reopened.close()
        assertFailsWith<KvidException.Closed> { reopened.get(a) }
    }

    @Test fun closedArtifactCanBeCopiedAndOpened() = storeTest { dir ->
        val path = dir.file("src.kvid")
        Kvid.create(path).apply { put("portable"); close() }
        val copy = dir.file("copy.kvid")
        overwriteFile(copy, readFile(path))
        val opened = Kvid.open(copy)
        assertEquals(1, opened.list().items.size)
        assertTrue(opened.verify().ok)
        opened.close()
    }

    @Test fun createRefusesExistingAndOpenRefusesMissingOrForeignFiles() = storeTest { dir ->
        val path = dir.file("a.kvid")
        Kvid.create(path).close()
        assertFailsWith<KvidException.AlreadyExists> { Kvid.create(path) }
        assertFailsWith<KvidException.NotFound> { Kvid.open(dir.file("missing.kvid")) }

        overwriteFile(dir.file("garbage.kvid"), ByteArray(100) { 0x41 })
        assertFailsWith<KvidException.NotAStore> { Kvid.open(dir.file("garbage.kvid")) }

        val plain = dir.file("plain.db")
        BundledSQLiteDriver().open(plain).use { it.execSQL("CREATE TABLE t(x)") }
        assertFailsWith<KvidException.NotAStore> { Kvid.open(plain) }
    }

    @Test fun newerFormatsAreRefused() = storeTest { dir ->
        val path = dir.file("a.kvid")
        Kvid.create(path).close()
        BundledSQLiteDriver().open(path).use { it.execSQL("UPDATE kvid_meta SET format_minor = format_minor + 1") }
        val minor = assertFailsWith<KvidException.UnsupportedFormat> { Kvid.open(path) }
        assertEquals(Schema.FORMAT_MINOR + 1, minor.fileMinor)
        assertFailsWith<KvidException.UnsupportedFormat> { Kvid.openReadOnly(path) }
        BundledSQLiteDriver().open(path).use { it.execSQL("UPDATE kvid_meta SET format_minor = format_minor - 1, format_major = format_major + 1") }
        assertFailsWith<KvidException.UnsupportedFormat> { Kvid.open(path) }
        BundledSQLiteDriver().open(path).use { it.execSQL("UPDATE kvid_meta SET format_major = format_major - 1"); it.execSQL("PRAGMA user_version = 99") }
        assertFailsWith<KvidException.UnsupportedFormat> { Kvid.open(path) }
    }

    @Test fun uncleanCloseMarkerTriggersQuickCheckOnOpen() = storeTest { dir ->
        val path = dir.file("a.kvid")
        Kvid.create(path).apply { put("x"); close() }
        BundledSQLiteDriver().open(path).use { it.execSQL("UPDATE kvid_meta SET clean_close = 0") }
        val store = Kvid.open(path)   // quick_check passes on an intact file
        assertEquals(1, store.list().items.size)
        store.close()
        BundledSQLiteDriver().open(path).use { c ->
            c.prepare("SELECT clean_close FROM kvid_meta").use { st -> st.step(); assertEquals(1L, st.getLong(0)) }
        }
    }

    // ---------------------------------------------------------------- transactions

    @Test fun transactionCommitsTogetherWithReadYourWrites() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val (a, b) = store.transaction {
            val a = put("first")
            val b = put("second")
            assertEquals("first", get(a)?.body, "reads inside the block see staged writes")
            assertEquals(2, list().items.size)
            a to b
        }
        assertEquals(1L, store.get(a)!!.version.seq)
        assertEquals(1L, store.get(b)!!.version.seq, "both versions share the transaction's commit sequence")
        assertEquals(1L, store.stats().commitSeq)
        store.close()
    }

    @Test fun transactionRollsBackOnException() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val boom = assertFailsWith<IllegalStateException> {
            store.transaction { put("doomed"); throw IllegalStateException("boom") }
        }
        assertEquals("boom", boom.message)
        assertEquals(0, store.list().items.size)
        assertEquals(0L, store.stats().commitSeq, "a rolled-back transaction consumes no commit sequence")
        store.put("after")   // the connection is usable again
        assertEquals(1L, store.stats().commitSeq)
        store.close()
    }

    @Test fun transactionRollsBackOnCancellation() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        var staged = false
        val job = launch {
            store.transaction {
                put("doomed"); staged = true
                awaitCancellation()
            }
        }
        while (!staged) yield()
        job.cancelAndJoin()
        assertEquals(0, store.list().items.size, "cancelled transaction left nothing behind")
        store.put("after cancel")
        assertEquals(1, store.list().items.size)
        store.close()
    }

    @Test fun nestedTransactionRollsBackOnlyItsOwnWork() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val outer = store.transaction {
            val outer = put("outer")
            val inner = runCatching { transaction { put("inner"); throw IllegalArgumentException("inner fails") } }
            assertTrue(inner.isFailure)
            assertEquals(1, list().items.size, "inner work rolled back to the savepoint")
            transaction { put("inner ok") }
            outer
        }
        assertEquals(2, store.list().items.size)
        assertNotNull(store.get(outer))
        store.close()
    }

    @Test fun reentryAndSessionEscapeAreRejected() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        var escaped: Transaction? = null
        store.transaction {
            escaped = this
            assertFailsWith<KvidException.Usage> { store.put("via store inside block") }
            assertFailsWith<KvidException.Usage> { store.transaction { put("nested via store") } }
            assertFailsWith<KvidException.Usage> { store.close() }
            assertFailsWith<KvidException.Usage> { store.snapshot(dir.file("s.kvid")) }
            val shared = async { runCatching { put("from another coroutine") } }
            assertTrue(shared.await().exceptionOrNull() is KvidException.Usage, "session shared with a child coroutine is rejected")
            put("legit")
        }
        assertFailsWith<KvidException.Closed> { escaped!!.put("after the block") }
        assertEquals(1, store.list().items.size)
        store.close()
    }

    // ---------------------------------------------------------------- identity, versions, history

    @Test fun updatesCreateVersionsVisibleByCommitSequence() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val id = store.put("v1", PutOptions(title = "t1"))          // seq 1
        val v2 = store.update(id, "v2", PutOptions(title = "t2"))   // seq 2
        val v3 = store.update(id, "v3", PutOptions(title = "t3"))   // seq 3
        assertEquals("v3", store.get(id)!!.body)
        assertEquals("v1", store.get(id, asOfSeq = 1)!!.body)
        assertEquals("v2", store.get(id, asOfSeq = 2)!!.body)
        assertNull(store.get(id, asOfSeq = 0), "before the document existed")
        val history = store.history(id)
        assertEquals(listOf("v1", "v2", "v3"), history.map { it.body })
        assertEquals(listOf(null, history[0].versionId, v2), history.map { it.supersedesVersionId })
        assertTrue(v3 > v2)

        val tomb = store.delete(id)                                  // seq 4
        assertNull(store.get(id))
        assertEquals("v3", store.get(id, asOfSeq = 3)!!.body, "history before the delete stays readable")
        assertEquals(4, store.history(id).size)
        assertTrue(store.history(id).last().tombstone)
        assertEquals(tomb, store.history(id).last().versionId)
        assertFailsWith<KvidException.NotFound> { store.update(id, "zombie") }
        assertFailsWith<KvidException.NotFound> { store.delete(id) }
        assertFailsWith<KvidException.NotFound> { store.update("nope", "x") }
        assertFailsWith<KvidException.AlreadyExists> { store.put("dup", PutOptions(documentId = id)) }
        assertTrue(store.verify().ok, store.verify().problems.joinToString())
        store.close()
    }

    @Test fun multipleUpdatesInOneTransactionOrderByVersionId() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val id = store.transaction {
            val id = put("a")
            update(id, "b")
            update(id, "c")
            id
        }
        assertEquals("c", store.get(id)!!.body)
        assertEquals("c", store.get(id, asOfSeq = 1)!!.body, "same sequence: the largest versionId wins")
        assertEquals(listOf("a", "b", "c"), store.history(id).map { it.body })
        assertEquals(setOf(1L), store.history(id).map { it.seq }.toSet())
        store.close()
    }

    @Test fun callerSuppliedIdsAndUniqueUriOption() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"), StoreOptions(uniqueUri = true))
        store.put("one", PutOptions(documentId = "note-1", uri = "note://x"))
        assertFailsWith<KvidException.AlreadyExists> { store.put("two", PutOptions(uri = "note://x")) }
        assertEquals(1, store.list().items.size, "the failed put wrote nothing")
        store.delete("note-1")
        store.put("three", PutOptions(uri = "note://x"))   // the index covers live documents only
        assertEquals(1, store.list().items.size)
        store.close()
    }

    // ---------------------------------------------------------------- list, find, cursors

    @Test fun listOrdersByEventTimeAndCursorsExpireOnWrite() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val ids = (1..5).map { i -> store.put("doc $i", PutOptions(eventTimeMs = 1000L * i, tags = if (i % 2 == 0) listOf("even") else emptyList())) }
        val page1 = store.list(ListOptions(limit = 2))
        assertEquals(listOf("doc 5", "doc 4"), page1.items.map { it.body })
        val page2 = store.list(ListOptions(limit = 2, cursor = page1.nextCursor))
        assertEquals(listOf("doc 3", "doc 2"), page2.items.map { it.body })
        val page3 = store.list(ListOptions(limit = 2, cursor = page2.nextCursor))
        assertEquals(listOf("doc 1"), page3.items.map { it.body })
        assertNull(page3.nextCursor)

        assertEquals(listOf("doc 4", "doc 2"), store.list(ListOptions(tags = listOf("even"))).items.map { it.body })
        assertEquals(listOf("doc 3", "doc 2"), store.list(ListOptions(sinceEventTimeMs = 2000, untilEventTimeMs = 4000)).items.map { it.body })
        assertFailsWith<KvidException.Usage> { store.list(ListOptions(limit = 3, cursor = page1.nextCursor)) }

        store.update(ids[0], "doc 1 edited")
        assertFailsWith<KvidException.CursorExpired> { store.list(ListOptions(limit = 2, cursor = page1.nextCursor)) }
        store.close()
    }

    @Test fun findSearchesCurrentLiveVersionsOnly() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val plan = store.put("We met Sam about the Q4 plan and budget.", PutOptions(title = "Q4 planning", tags = listOf("work")))
        store.put("Milk, eggs, bread.", PutOptions(title = "Groceries"))
        val standup = store.put("Daily standup notes: blocked on the plan review.", PutOptions(title = "Standup"))

        val hits = store.find("plan")
        assertEquals(setOf(plan, standup), hits.items.map { it.document.id }.toSet())
        assertTrue(hits.items.all { it.score > 0 }, "scores are reported higher-is-better")
        assertTrue(hits.items.first { it.document.id == plan }.snippet!!.contains("[plan]"))
        assertEquals(listOf(plan), store.find("plan", FindOptions(tags = listOf("work"))).items.map { it.document.id })
        assertEquals(listOf(plan), store.find("\"Q4 plan\"", FindOptions(syntax = QuerySyntax.FTS5)).items.map { it.document.id })
        assertTrue(store.find("\"plan Q4\"", FindOptions(syntax = QuerySyntax.FTS5)).items.isEmpty(), "FTS5 phrases are ordered")
        assertEquals(listOf(plan), store.find("\"plan Q4\"").items.map { it.document.id }, "plain queries do not interpret quotes")
        assertEquals(listOf(plan), store.find("AND").items.map { it.document.id }, "plain queries treat operators as words")
        assertFailsWith<KvidException.InvalidQuery> { store.find("AND", FindOptions(syntax = QuerySyntax.FTS5)) }
        assertTrue(store.find("   ").items.isEmpty(), "a plain query without terms matches nothing")
        assertFailsWith<KvidException.InvalidQuery> { store.find("   ", FindOptions(syntax = QuerySyntax.FTS5)) }

        store.update(standup, "Daily notes: nothing blocked.")
        assertEquals(listOf(plan), store.find("plan").items.map { it.document.id }, "superseded text is not searchable")
        store.delete(plan)
        assertTrue(store.find("plan").items.isEmpty(), "deleted documents are not searchable")

        store.transaction { (1..7).forEach { put("needle number $it") } }
        val paged = store.find("needle", FindOptions(limit = 3))
        assertEquals(3, paged.items.size); assertNotNull(paged.nextCursor)
        val second = store.find("needle", FindOptions(limit = 3, cursor = paged.nextCursor))
        assertEquals(3, second.items.size)
        assertTrue(paged.items.map { it.document.id }.intersect(second.items.map { it.document.id }.toSet()).isEmpty())
        store.put("another write")
        assertFailsWith<KvidException.CursorExpired> { store.find("needle", FindOptions(limit = 3, cursor = paged.nextCursor)) }
        store.close()
    }

    // ---------------------------------------------------------------- verification, rebuild, corruption

    @Test fun verifyDetectsProjectionDriftAndRebuildRepairsIt() = storeTest { dir ->
        val path = dir.file("a.kvid")
        val store = Kvid.create(path)
        val id = store.put("searchable text")
        assertTrue(store.verify().ok)
        store.close()
        BundledSQLiteDriver().open(path).use { it.execSQL("DELETE FROM current") }
        val reopened = Kvid.open(path)
        val report = reopened.verify()
        assertFalse(report.ok)
        assertTrue(report.problems.any { it.contains("projection") }, report.problems.joinToString())
        assertTrue(reopened.find("searchable").items.isEmpty())
        reopened.rebuildIndex()
        assertTrue(reopened.verify().ok, reopened.verify().problems.joinToString())
        assertEquals(listOf(id), reopened.find("searchable").items.map { it.document.id })
        reopened.close()
    }

    @Test fun damagedPagesAreDetectedNotReadSilently() = storeTest { dir ->
        val path = dir.file("a.kvid")
        val store = Kvid.create(path)
        repeat(50) { store.put("row $it with enough text to occupy space ".repeat(20)) }
        store.close()
        val bytes = readFile(path)
        assertTrue(bytes.size > 3 * 4096)
        for (i in 4096 + 200 until 4096 + 1200) bytes[i] = 0x5A.toByte()   // damage inside page 2
        overwriteFile(path, bytes)
        val outcome = runCatching {
            val reopened = Kvid.open(path)
            try {
                val report = reopened.verify()
                if (report.ok) reopened.list(ListOptions(limit = 100)).items.forEach { it.body }
                report
            } finally { reopened.close() }
        }
        val ok = outcome.fold(onSuccess = { !it.ok }, onFailure = { it is KvidException.Corrupt || it is KvidException.NotAStore || it is KvidException.Io })
        assertTrue(ok, "damage must surface as a verify problem or a typed failure, got $outcome")
    }

    // ---------------------------------------------------------------- concurrency

    @Test fun oneWritablePerPathAndReadOnlyHandles() = storeTest { dir ->
        val path = dir.file("a.kvid")
        val writer = Kvid.create(path)
        val id = writer.put("committed")
        assertFailsWith<KvidException.Locked> { Kvid.open(path) }
        val reader = Kvid.openReadOnly(path)
        assertEquals("committed", reader.get(id)!!.body)
        assertFailsWith<KvidException.ReadOnly> { reader.put("nope") }
        assertFailsWith<KvidException.ReadOnly> { reader.transaction { put("nope") } }
        assertFailsWith<KvidException.ReadOnly> { reader.vacuum() }
        writer.update(id, "updated")
        assertEquals("updated", reader.get(id)!!.body, "a read-only handle sees committed writes")
        reader.close()
        writer.close()
        Kvid.open(path).close()   // the path is free again after close
    }

    // ---------------------------------------------------------------- snapshots

    @Test fun snapshotPublishesCommittedStateAtomically() = storeTest { dir ->
        val path = dir.file("a.kvid")
        val store = Kvid.create(path)
        val id = store.put("saved")
        val dest = dir.file("backup.kvid")
        store.snapshot(dest)
        assertEquals(listOf("a.kvid", "backup.kvid"), dir.listNames(), "no temporary files remain")
        assertFailsWith<KvidException.AlreadyExists> { store.snapshot(dest) }
        assertFailsWith<KvidException.NotFound> { store.snapshot(dir.file("missing/dir/x.kvid")) }
        val copy = Kvid.openReadOnly(dest)
        assertEquals("saved", copy.get(id)!!.body)
        assertTrue(copy.verify().ok)
        copy.close()
        store.put("after snapshot")
        val again = Kvid.openReadOnly(dest)
        assertEquals(1, again.list().items.size, "the snapshot is independent of later writes")
        again.close()
        store.close()
        val w = Kvid.open(dest)   // the snapshot is itself a full store
        w.put("into the backup"); w.close()
    }

    // ---------------------------------------------------------------- bounds

    @Test fun boundsFailBeforeAnythingIsWritten() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"), StoreOptions(limits = Limits(bodyBytes = 16, titleBytes = 4, tagsPerVersion = 2, tagCodePoints = 3)))
        assertFailsWith<KvidException.LimitExceeded> { store.put("x".repeat(17)) }
        assertFailsWith<KvidException.LimitExceeded> { store.put("ok", PutOptions(title = "toolong")) }
        assertFailsWith<KvidException.LimitExceeded> { store.put("ok", PutOptions(tags = listOf("a", "b", "c"))) }
        assertFailsWith<KvidException.LimitExceeded> { store.put("ok", PutOptions(tags = listOf("abcd"))) }
        assertFailsWith<KvidException.Usage> { store.put("ok", PutOptions(tags = listOf(""))) }
        assertEquals(0L, store.stats().commitSeq)
        store.put("ok", PutOptions(title = "fine", tags = listOf("a", "b")))
        assertEquals(1L, store.stats().commitSeq)
        assertFailsWith<IllegalArgumentException> { Limits(bodyBytes = Limits.MAX_BODY_BYTES + 1) }
        store.close()
    }

    // ---------------------------------------------------------------- retention

    @Test fun keepLatestRetentionRaisesTheHistoryFloor() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val id = store.put("v1")
        store.update(id, "v2")
        store.update(id, "v3")
        val deleted = store.put("gone")
        store.delete(deleted)
        val cursor = store.list(ListOptions(limit = 1)).nextCursor
        store.vacuum(Retention.KEEP_LATEST)
        assertEquals(listOf("v3"), store.history(id).map { it.body })
        assertEquals(1, store.history(deleted).size, "a deletion marker is retained so old content cannot resurrect")
        assertTrue(store.history(deleted).single().tombstone)
        assertFailsWith<KvidException.HistoryUnavailable> { store.get(id, asOfSeq = 1) }
        assertEquals("v3", store.get(id)!!.body)
        assertTrue(store.stats().historyFloorSeq > 0)
        assertTrue(store.verify().ok, store.verify().problems.joinToString())
        if (cursor != null) assertFailsWith<KvidException.CursorExpired> { store.list(ListOptions(limit = 1, cursor = cursor)) }
        store.vacuum(Retention.KEEP_ALL)   // plain VACUUM keeps everything
        assertEquals(1, store.history(id).size)
        store.close()
    }

    // ---------------------------------------------------------------- JSON Lines

    @Test fun jsonLinesExportAndImport() = storeTest { dir ->
        val store = Kvid.create(dir.file("a.kvid"))
        val meta = buildJsonObject { put("k", "v") }
        val a = store.put("alpha", PutOptions(title = "A", tags = listOf("t1"), metadata = meta, uri = "u://a", eventTimeMs = 123))
        store.update(a, "alpha 2", PutOptions(title = "A2", tags = listOf("t2"), eventTimeMs = 456))
        val b = store.put("beta"); store.delete(b)
        val out = dir.file("export.jsonl")
        store.exportJsonLines(out)
        store.close()
        val lines = readFile(out).decodeToString().trim().lines()
        assertEquals(4, lines.size, "every retained version is exported")

        val target = Kvid.create(dir.file("b.kvid"))
        assertEquals(1, target.importJsonLines(out), "only live current versions are imported")
        val doc = assertNotNull(target.get(a))
        assertEquals("alpha 2", doc.body); assertEquals("A2", doc.title); assertEquals(listOf("t2"), doc.version.tags)
        assertEquals(456L, doc.version.eventTimeMs); assertNull(doc.version.uri, "an update carries only the fields it was given")
        assertNull(target.get(b))
        assertFailsWith<KvidException.AlreadyExists> { target.importJsonLines(out) }
        target.close()
    }

    @Test fun vectorBlobCodecIsLittleEndianAndBitExact() {
        assertContentEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x3f), Float32Blob.encode(floatArrayOf(1f)))
        val edge = floatArrayOf(0f, -0f, 1f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.fromBits(0x7fc00001))
        assertContentEquals(edge.map { it.toRawBits() }, Float32Blob.decode(Float32Blob.encode(edge)).map { it.toRawBits() })
        assertFailsWith<IllegalArgumentException> { Float32Blob.decode(byteArrayOf(1, 2, 3)) }
    }
}
