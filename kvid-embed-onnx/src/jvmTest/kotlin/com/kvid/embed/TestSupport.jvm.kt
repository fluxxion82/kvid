package com.kvid.embed

import ai.onnxruntime.OrtSession
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.test.fail

// The JVM side of the helpers in commonTest's TestSupport.kt.

internal actual fun fixtureFile(path: String): Path? =
    Path(path).takeIf { SystemFileSystem.metadataOrNull(it)?.isRegularFile == true }

internal actual val testPlatform: String =
    "jvm/${System.getProperty("os.name")}/${System.getProperty("os.arch")}/java-${System.getProperty("java.version")}".replace(' ', '_')

internal actual val memoryKind: String = "none"

/** Not measured on the JVM. */
internal actual fun memoryKb(): Long? = null

internal actual class TestLatch actual constructor(count: Int) {
    private val latch = CountDownLatch(count)

    actual fun countDown() = latch.countDown()

    actual fun await(timeout: Duration): Boolean = latch.await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
}

internal actual suspend fun openForTuning(
    modelPath: String,
    vocabPath: String,
    model: BertEmbeddingModel,
    options: OnnxOptions,
    executionProvider: String,
    providerThreads: Int
): OnnxEmbedder {
    // As ONNX Runtime recommends for XNNPACK: the session's own pool does not spin; XNNPACK has its own.
    val configure: ((OrtSession.SessionOptions) -> Unit)? = when (executionProvider) {
        "cpu" -> null
        "xnnpack" -> { session ->
            session.addConfigEntry("session.intra_op.allow_spinning", "0")
            session.addXnnpack(mapOf("intra_op_num_threads" to providerThreads.toString()))
        }
        else -> fail("unknown execution provider '$executionProvider'")
    }
    return OnnxEmbedder.openTimed(modelPath, vocabPath, model, options, timings = null, configureSession = configure)
}
