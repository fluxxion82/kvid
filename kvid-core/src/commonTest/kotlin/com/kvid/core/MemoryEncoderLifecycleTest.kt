package com.kvid.core

import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MemoryEncoderLifecycleTest {
    @Test
    fun cancellationCleansUpAndAllowsRetry() = runTest {
        for (stage in Stage.entries) {
            val cancellation = CancellationException("cancel at $stage")
            val video = TestVideoEncoder(stage, cancellation)
            val encoder = MemoryEncoder(TestQrGenerator(), video)
            encoder.addMessage("hello").getOrThrow()

            var thrown: CancellationException? = null
            try {
                encoder.buildVideo("unused.mp4", smallParams)
            } catch (e: CancellationException) {
                thrown = e
            }
            assertEquals(cancellation.message, thrown?.message, "Cancellation must propagate at $stage")
            assertEquals(1, video.cancelCalls, "Cleanup must run at $stage")
            assertFalse(encoder.getStats().isEncoding, "State must reset at $stage")
            video.failure = null
            assertTrue(encoder.buildVideo("unused.mp4", smallParams).isSuccess, "Retry must succeed at $stage")
        }
    }

    @Test
    fun returnedFailureCleansUpAndAllowsRetry() = runTest {
        for (stage in Stage.entries) {
            val failure = IllegalStateException("failure at $stage")
            val video = TestVideoEncoder(stage, failure)
            val encoder = MemoryEncoder(TestQrGenerator(), video)
            encoder.addMessage("hello").getOrThrow()

            val result = encoder.buildVideo("unused.mp4", smallParams)
            assertSame(failure, result.exceptionOrNull())
            assertEquals(1, video.cancelCalls, "Cleanup must run at $stage")
            assertFalse(encoder.getStats().isEncoding, "State must reset at $stage")
            video.failure = null
            assertTrue(encoder.buildVideo("unused.mp4", smallParams).isSuccess)
        }
    }

    @Test
    fun cleanupFailureDoesNotReplaceCancellation() = runTest {
        val cancellation = CancellationException("cancel frame")
        val cleanupFailure = IllegalStateException("cleanup failed")
        val video = TestVideoEncoder(Stage.FRAME, cancellation, cleanupFailure)
        val encoder = MemoryEncoder(TestQrGenerator(), video)
        encoder.addMessage("hello").getOrThrow()
        var thrown: CancellationException? = null
        try {
            encoder.buildVideo("unused.mp4", smallParams)
        } catch (e: CancellationException) {
            thrown = e
        }
        assertEquals(cancellation.message, thrown?.message)
        assertTrue(cleanupFailure in cancellation.suppressedExceptions)
        assertFalse(encoder.getStats().isEncoding)
    }

    private enum class Stage { INITIALIZE, FRAME, FINALIZE }

    private class TestVideoEncoder(
        private val stage: Stage,
        var failure: Exception?,
        private val cleanupFailure: Exception? = null
    ) : VideoEncoder {
        var cancelCalls = 0
        private fun <T> result(at: Stage, value: T): Result<T> {
            val error = failure
            if (at != stage || error == null) return Result.success(value)
            if (error is CancellationException) throw error
            return Result.failure(error)
        }
        override fun initialize(params: VideoEncodingParams) = result(Stage.INITIALIZE, Unit)
        override fun addFrame(frameData: FrameData, frameNumber: Int) = result(Stage.FRAME, Unit)
        override fun finalize(outputPath: String) = result(
            Stage.FINALIZE, EncodingStats(1, 3, 1.0, 24, VideoCodec.H264, 0)
        )
        override fun cancel() {
            cancelCalls++
            cleanupFailure?.let { throw it }
        }
    }

    private class TestQrGenerator : QRCodeGenerator {
        override fun getCapabilities() = QRCapabilities(2953, 1..40, listOf("M"))
        override fun generateQRCode(data: String, version: Int, errorCorrection: String) =
            QRCodeData(1, 1, byteArrayOf(0), version, data.length)
    }

    private val smallParams = VideoEncodingParams(width = 1, height = 1)
}
