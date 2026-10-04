package com.kvid.spike

import com.kvid.spike.SpikeDb.exec
import com.kvid.spike.SpikeDb.query
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** ADR 0001: vectors stored as little-endian float32 BLOBs round-trip exactly (contract section 14). */
class VectorBlobTest {
    @Test
    fun float32VectorsRoundTripThroughBlobs() {
        val rnd = Random(7)
        val vectors = List(50) { FloatArray(384) { rnd.nextFloat() * 2f - 1f } }
        SpikeDb.openInMemory().use { conn ->
            conn.exec("CREATE TABLE vectors(chunk_id INTEGER PRIMARY KEY, embedding BLOB NOT NULL)")
            conn.prepare("INSERT INTO vectors(chunk_id, embedding) VALUES (?, ?)").use { stmt ->
                for ((i, v) in vectors.withIndex()) {
                    stmt.reset(); stmt.clearBindings()
                    stmt.bindLong(1, i.toLong()); stmt.bindBlob(2, Float32Blob.encode(v))
                    stmt.step()
                }
            }
            val back = conn.query("SELECT chunk_id, embedding FROM vectors ORDER BY chunk_id") { getLong(0) to Float32Blob.decode(getBlob(1)) }
            assertEquals(vectors.size, back.size)
            for ((i, pair) in back.withIndex()) {
                assertEquals(i.toLong(), pair.first)
                assertContentEquals(vectors[i], pair.second, "vector $i must round-trip bit-exactly")
            }
            assertEquals(384L * 4, conn.query("SELECT length(embedding) FROM vectors LIMIT 1") { getLong(0) }.single())
        }
    }
}
