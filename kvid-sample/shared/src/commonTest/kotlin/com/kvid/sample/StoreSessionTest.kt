package com.kvid.sample

import com.kvid.store.Kvid
import com.kvid.store.KvidException
import com.kvid.store.use
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreSessionTest {

    @Test fun opensLazilyAndSeedsOnlyANewStore() = sampleTest { path ->
        var seeded = 0
        val session = StoreSession(path, onCreate = { seeded++; SampleNotes.seed(it) })
        assertFalse(session.isOpen())
        val count = session.use { it.stats().liveDocuments }
        assertTrue(count > 0)
        assertTrue(session.isOpen())
        session.closeWhenIdle()
        assertFalse(session.isOpen())
        assertEquals(count, session.use { it.stats().liveDocuments }, "reopening does not seed again")
        assertEquals(1, seeded)
        session.closeWhenIdle()
    }

    @Test fun closeWhenIdleClosesTheFileForOtherHandles() = sampleTest { path ->
        val session = StoreSession(path)
        session.use { it.put("kept") }
        assertFailsWith<KvidException.Locked> { Kvid.open(path) }
        session.closeWhenIdle()
        Kvid.open(path).use { assertEquals(listOf("kept"), it.list().items.map { d -> d.body }) }
    }

    @Test fun closeWaitsForRunningWorkAndANewUseCancelsIt() = sampleTest { path ->
        val session = StoreSession(path)
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val worker = launch { session.use { store -> store.put("during"); inside.complete(Unit); release.await() } }
        inside.await()
        session.closeWhenIdle()
        assertTrue(session.isOpen(), "a running use keeps the store open")
        release.complete(Unit)
        worker.join()
        assertFalse(session.isOpen(), "the deferred close ran when the last use finished")

        val second = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val busy = launch { session.use { second.complete(Unit); secondRelease.await() } }
        second.await()
        session.closeWhenIdle()
        session.use { it.put("foreground again") }
        secondRelease.complete(Unit)
        busy.join()
        assertTrue(session.isOpen(), "a use after the close request cancels the deferred close")
        assertEquals(2L, session.use { it.stats().liveDocuments })
        session.closeWhenIdle()
    }

    @Test fun aFailedSeedLeavesNoOpenHandle() = sampleTest { path ->
        val session = StoreSession(path, onCreate = { error("seed failed") })
        assertFailsWith<IllegalStateException> { session.use { } }
        assertFalse(session.isOpen())
        Kvid.open(path).use { assertEquals(0L, it.stats().liveDocuments) }
    }
}
