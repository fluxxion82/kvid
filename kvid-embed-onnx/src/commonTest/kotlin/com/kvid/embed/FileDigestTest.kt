package com.kvid.embed

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * [sha256Hex] on every platform (java.security on the JVM and Android, CommonCrypto on iOS) against known
 * digests: the FIPS 180-2 vectors, files shorter than, equal to and longer than the 1 MiB read block, and
 * the fixture's model files (the digests in its source.json, from Python's hashlib).
 */
class FileDigestTest {
    private val dir = createTempDirectory("kvid-digest-test")

    @AfterTest fun deleteFiles() = dir.deleteRecursively()

    private fun file(name: String, bytes: ByteArray): String {
        val path = Path(dir, name)
        SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
        return path.toString()
    }

    private fun pattern(size: Int) = ByteArray(size) { ((it * 31 + 7) % 256).toByte() }

    @Test fun knownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(file("empty", ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex(file("abc", "abc".encodeToByteArray())))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex(file("448-bit", "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()))
        )
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", sha256Hex(file("million-a", ByteArray(1_000_000) { 'a'.code.toByte() })))
    }

    @Test fun filesAroundTheReadBlockSize() {
        // Exactly one 1 MiB block, and two and a half blocks plus three bytes.
        assertEquals("06b7bbfb7824aa03382051691630eb26de85102d1b08a81e907ec0744cd8a286", sha256Hex(file("one-block", pattern(1 shl 20))))
        assertEquals("b06fe1fb1424a93da1f4f5631da2a79c672fee22143e2e9dd36f6f5a5d13d396", sha256Hex(file("multi-block", pattern((1 shl 21) + (1 shl 19) + 3))))
    }

    @Test fun aMissingFileFails() {
        assertFails { sha256Hex(Path(dir, "missing").toString()) }
    }

    @Test fun fixtureFilesMatchTheirRecordedDigests() {
        val fixture = EmbeddingFixture.loadOrSkip("fixture digest test") ?: return
        val digests = fixture.source.getValue("sha256").jsonObject
        assertEquals(digests.getValue("model.onnx").jsonPrimitive.content, sha256Hex(fixture.modelPath))
        assertEquals(digests.getValue("vocab.txt").jsonPrimitive.content, sha256Hex(fixture.vocabPath))
    }
}
