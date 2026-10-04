package com.kvid.store

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdsTest {
    @Test fun uuidV7HasVersionAndVariantBitsAndSortsByTime() {
        val a = Ids.uuidV7(1_700_000_000_000L, Random(1))
        val b = Ids.uuidV7(1_700_000_000_001L, Random(2))
        assertTrue(Ids.isUuid(a), a)
        assertEquals('7', a[14], "version nibble")
        assertTrue(a[19] in "89ab", "variant nibble")
        assertTrue(a < b, "ids order by their millisecond timestamp")
        assertEquals(36, a.length)
    }

    @Test fun callerIdsAreBounded() {
        Ids.validateDocumentId("note-1")
        assertFailsWith<KvidException.Usage> { Ids.validateDocumentId("") }
        assertFailsWith<KvidException.Usage> { Ids.validateDocumentId("a\u0000b") }
        assertFailsWith<KvidException.LimitExceeded> { Ids.validateDocumentId("x".repeat(129)) }
    }
}
