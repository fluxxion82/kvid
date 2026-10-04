package com.kvid.spike

import com.kvid.spike.SpikeDb.exec
import com.kvid.spike.SpikeDb.query
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** ADR 0001: vectors stored as little-endian float32 BLOBs round-trip exactly (contract section 14). */
class VectorBlobTest {
    @Test
    fun wireEncodingIsLittleEndianAndInvalidLengthsAreRejected() {
        assertContentEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x3f), Float32Blob.encode(floatArrayOf(1f)))
        assertEquals(0, Float32Blob.decode(byteArrayOf()).size)
        assertFailsWith<IllegalArgumentException> { Float32Blob.decode(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun float32VectorsRoundTripThroughBlobs() {
        val rnd = Random(7)
        val edgeBits = intArrayOf(0, Int.MIN_VALUE, 0x3f800000, 0x7f800000, 0xff800000.toInt(), 0x7fc00001, 0x7fc00002)
        val vectors = List(50) { FloatArray(384) { rnd.nextFloat() * 2f - 1f } }.toMutableList()
        vectors[0] = FloatArray(384) { Float.fromBits(edgeBits[it % edgeBits.size]) }
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
                assertContentEquals(vectors[i].map { it.toRawBits() }, pair.second.map { it.toRawBits() }, "vector $i must preserve raw bits, including NaN payloads and signed zero")
            }
            assertEquals(384L * 4, conn.query("SELECT length(embedding) FROM vectors LIMIT 1") { getLong(0) }.single())
        }
    }
}
