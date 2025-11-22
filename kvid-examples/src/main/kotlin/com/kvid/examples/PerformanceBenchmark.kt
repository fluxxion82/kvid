package com.kvid.examples

import com.kvid.core.*
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Comprehensive performance benchmarking suite for KVID
 *
 * Run with: ./gradlew :kvid-examples:run --args="benchmark"
 *
 * Benchmarks cover:
 * - Text chunking performance (various input sizes)
 * - QR code generation (single and batch)
 * - Embedding generation (SimpleEmbedding)
 * - Vector search with Flat and HNSW indexes
 * - Video encoding (all codecs, all presets)
 * - Video decoding and frame extraction
 * - End-to-end encoding/decoding pipeline
 * - Storage efficiency metrics
 */
object PerformanceBenchmark {
    private val tempDir = File.createTempFile("kvid-perf", "").parentFile!!
    private val results = mutableListOf<BenchmarkResult>()

    fun runAll() {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║       KVID Comprehensive Performance Benchmark Suite           ║")
        println("╚════════════════════════════════════════════════════════════════╝\n")

        println("System Information:")
        println("  Runtime: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB max memory")
        println("  Processors: ${Runtime.getRuntime().availableProcessors()}")
        println()

        benchmarkTextChunking()
        benchmarkQRCodeGeneration()
        benchmarkEmbeddingGeneration()
        benchmarkVectorIndexes()
        benchmarkVideoEncoding()
        benchmarkVideoDecoding()
        benchmarkStorageEfficiency()
        benchmarkEndToEnd()

        println("\n" + "═".repeat(64))
        println("Benchmark Summary & Results")
        println("═".repeat(64))
        generateReport()
    }

    private data class BenchmarkResult(
        val category: String,
        val test: String,
        val timeMs: Double,
        val throughput: String = "",
        val metadata: Map<String, Any> = emptyMap()
    )

    private fun benchmarkTextChunking() {
        println("\n▼ Text Chunking Benchmarks")
        println("═".repeat(64))

        val chunker = TextChunker(512)

        val testCases = mapOf(
            "Small (65 bytes)" to "Hello World. This is a test. Performance is important.",
            "Medium (1KB)" to "The quick brown fox jumps over the lazy dog. ".repeat(10),
            "Large (10KB)" to "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(100),
            "XLarge (100KB)" to "Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. ".repeat(1000)
        )

        testCases.forEach { (name, text) ->
            val time = measureTimeMillis {
                repeat(10) {
                    chunker.chunk(text)
                }
            }
            val avgTime = time / 10.0
            val chunks = chunker.chunk(text)
            val throughput = "%.0f chunks/sec".format((chunks.size / (avgTime / 1000.0)))
            println("  %-20s: %7.2f ms/run | %5d chunks | %s".format(name, avgTime, chunks.size, throughput))
            results.add(BenchmarkResult("Text Chunking", name, avgTime, throughput))
        }
    }

    private fun benchmarkQRCodeGeneration() {
        println("\n▼ QR Code Generation Benchmarks")
        println("═".repeat(64))

        val generator = JvmQRCodeGenerator()

        val testCases = mapOf(
            "Small (11 bytes)" to "Hello World",
            "Medium (150 bytes)" to "The quick brown fox jumps over the lazy dog. This is a test message.",
            "Large (500 bytes)" to "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
                                   "Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. " +
                                   "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris."
        )

        testCases.forEach { (name, data) ->
            val time = measureTimeMillis {
                repeat(10) {
                    generator.generateQRCode(data)
                }
            }
            val avgTime = time / 10.0
            val throughput = "%.0f QR/sec".format(10.0 / (time / 1000.0))
            println("  %-30s: %7.2f ms | %s".format(name, avgTime, throughput))
            results.add(BenchmarkResult("QR Generation", name, avgTime, throughput))
        }
    }

    private fun benchmarkEmbeddingGeneration() {
        println("\n▼ Embedding Generation Benchmarks")
        println("═".repeat(64))

        val embedding = SimpleEmbedding()
        val testTexts = listOf(
            "The quick brown fox",
            "The quick brown fox jumps over the lazy dog. This is a test message for encoding and decoding.",
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(5)
        )

        testTexts.forEach { text ->
            val time = measureTimeMillis {
                runBlocking {
                    repeat(100) {
                        embedding.embed(text)
                    }
                }
            }
            val avgTime = time / 100.0
            val throughput = "%.0f embeddings/sec".format(100.0 / (time / 1000.0))
            val label = "Text (${text.length} chars)"
            println("  %-35s: %7.2f ms | %s".format(label, avgTime, throughput))
            results.add(BenchmarkResult("Embedding", label, avgTime, throughput))
        }

        val batchText = (0..999).map { "Sample text $it" }
        val batchTime = measureTimeMillis {
            runBlocking {
                embedding.embedBatch(batchText)
            }
        }
        val throughput = "%.0f embeddings/sec".format(1000.0 / (batchTime / 1000.0))
        println("  %-35s: %7.2f ms | %s".format("Batch (1000 texts)", batchTime.toDouble(), throughput))
        results.add(BenchmarkResult("Embedding", "Batch (1000)", batchTime.toDouble(), throughput))
    }

    private fun benchmarkVectorIndexes() {
        println("\n▼ Vector Index Search Benchmarks")
        println("═".repeat(64))

        val embedding = SimpleEmbedding()
        val indexSizes = listOf(100, 1000, 10000, 100000)

        println("\nFlat Index (Exhaustive Search):")
        for (size in indexSizes) {
            val index = FlatVectorIndex(embedding)
            val buildTime = measureTimeMillis {
                runBlocking {
                    repeat(size) { i ->
                        val vec = embedding.embed("Document $i with content for searching")
                        index.add(i, vec)
                    }
                }
            }

            val queryVec = runBlocking { embedding.embed("Query text") }
            val searchTime = measureTimeMillis {
                runBlocking {
                    repeat(10) {
                        index.search(queryVec, topK = 5)
                    }
                }
            }

            val avgSearchTime = searchTime / 10.0
            val throughput = "%.0f queries/sec".format(10.0 / (searchTime / 1000.0))
            println("  Size %6d: Build %6.1f ms | Search %7.2f ms | %s".format(size, buildTime.toDouble(), avgSearchTime, throughput))
            results.add(BenchmarkResult("Vector Search (Flat)", "Size $size", avgSearchTime, throughput))
        }

        println("\nHNSW Index (Approximate Search):")
        for (size in listOf(1000, 10000, 100000)) {  // HNSW is slower to build, test smaller sizes
            val index = HnswVectorIndex(embedding, maxM = 16, efConstruction = 200)
            val buildTime = measureTimeMillis {
                runBlocking {
                    repeat(size) { i ->
                        val vec = embedding.embed("Document $i with content for searching")
                        index.add(i, vec)
                    }
                }
            }

            val queryVec = runBlocking { embedding.embed("Query text") }
            val searchTime = measureTimeMillis {
                runBlocking {
                    repeat(10) {
                        index.search(queryVec, topK = 5)
                    }
                }
            }

            val avgSearchTime = searchTime / 10.0
            val throughput = "%.0f queries/sec".format(10.0 / (searchTime / 1000.0))
            println("  Size %6d: Build %6.1f ms | Search %7.2f ms | %s".format(size, buildTime.toDouble(), avgSearchTime, throughput))
            results.add(BenchmarkResult("Vector Search (HNSW)", "Size $size", avgSearchTime, throughput))
        }
    }

    private fun benchmarkVideoEncoding() {
        println("\n▼ Video Encoding Benchmarks")
        println("═".repeat(64))

        val encoder = JvmVideoEncoder()
        val width = 256
        val height = 256
        val frameCount = 30

        val rgbData = ByteArray(width * height * 3)
        repeat(rgbData.size) { i ->
            rgbData[i] = (i % 256).toByte()
        }
        val testFrame = FrameData(rgbData, PixelFormat.RGB_888, width, height)

        val codecs = listOf(VideoCodec.H264, VideoCodec.H265)
        val presets = listOf(EncodingPreset.ULTRAFAST, EncodingPreset.MEDIUM)

        println("\nH.264 Codec:")
        for (preset in presets) {
            val outputFile = File(tempDir, "bench-h264-$preset-${System.nanoTime()}.mp4")
            outputFile.deleteOnExit()

            var stats: EncodingStats? = null
            val time = measureTimeMillis {
                encoder.initialize(VideoEncodingParams(
                    width = width, height = height, fps = 30,
                    codec = VideoCodec.H264, preset = preset
                ))
                repeat(frameCount) { i ->
                    encoder.addFrame(testFrame, i)
                }
                stats = encoder.finalize(outputFile.absolutePath).getOrNull()
            }
            if (stats != null) {
                val compressionRatio = if (stats!!.fileSize > 0) {
                    (frameCount * width * height * 3).toFloat() / stats!!.fileSize
                } else 0f
                println("  Preset %-12s: %6.0f ms | Size: %6.1f KB | Ratio: %5.1f:1".format(
                    preset.name, time.toDouble(), stats!!.fileSize / 1024.0, compressionRatio
                ))
            }
        }

        println("\nH.265 Codec:")
        for (preset in presets) {
            val outputFile = File(tempDir, "bench-h265-$preset-${System.nanoTime()}.mp4")
            outputFile.deleteOnExit()

            var stats: EncodingStats? = null
            val time = measureTimeMillis {
                encoder.initialize(VideoEncodingParams(
                    width = width, height = height, fps = 30,
                    codec = VideoCodec.H265, preset = preset
                ))
                repeat(frameCount) { i ->
                    encoder.addFrame(testFrame, i)
                }
                stats = encoder.finalize(outputFile.absolutePath).getOrNull()
            }
            if (stats != null) {
                val compressionRatio = if (stats!!.fileSize > 0) {
                    (frameCount * width * height * 3).toFloat() / stats!!.fileSize
                } else 0f
                println("  Preset %-12s: %6.0f ms | Size: %6.1f KB | Ratio: %5.1f:1".format(
                    preset.name, time.toDouble(), stats!!.fileSize / 1024.0, compressionRatio
                ))
            }
        }
    }

    private fun benchmarkVideoDecoding() {
        println("\n▼ Video Decoding Benchmarks")
        println("═".repeat(64))

        val encoder = JvmVideoEncoder()
        val testVideoFile = File(tempDir, "bench-test-${System.currentTimeMillis()}.mp4")

        val params = VideoEncodingParams(
            width = 256, height = 256, fps = 30,
            codec = VideoCodec.H264, preset = EncodingPreset.ULTRAFAST
        )

        encoder.initialize(params)
        repeat(30) { i ->
            val rgbData = ByteArray(256 * 256 * 3) { (i % 256).toByte() }
            val frame = FrameData(rgbData, PixelFormat.RGB_888, 256, 256)
            encoder.addFrame(frame, i)
        }
        encoder.finalize(testVideoFile.absolutePath)

        if (testVideoFile.exists()) {
            val decoder = JvmVideoDecoder()
            val metadataTime = measureTimeMillis {
                runBlocking {
                    repeat(3) {
                        decoder.getVideoInfo(testVideoFile.absolutePath)
                    }
                }
            }
            val avgTime = metadataTime / 3.0
            println("  Metadata extraction: %.2f ms (avg for 3 iterations)".format(avgTime))
            results.add(BenchmarkResult("Video Decoding", "Metadata", avgTime))

            testVideoFile.delete()
        }
    }

    private fun benchmarkStorageEfficiency() {
        println("\n▼ Storage Efficiency Analysis")
        println("═".repeat(64))

        val generator = JvmQRCodeGenerator()

        val testData = "The quick brown fox jumps over the lazy dog. This is a test. ".repeat(10)
        // use version 40 with error correction L for maximum capacity
        val qrImage = generator.generateQRCode(testData, version = 40, errorCorrection = "L")

        val originalSize = testData.toByteArray().size
        val qrSize = qrImage.pixels.size  // Size in bytes

        val ratio = if (qrSize > 0) originalSize.toFloat() / qrSize else 0f
        println("  QR Code Size: ${qrSize / 1024.0}KB for ${testData.length} chars")
        println("  Original: ${originalSize} bytes")
        println("  Efficiency: 1:${String.format("%.2f", ratio)} compression")

        results.add(BenchmarkResult("Storage", "QR Compression Ratio", ratio.toDouble()))
    }

    private fun benchmarkEndToEnd() {
        println("\n▼ End-to-End Pipeline Benchmarks")
        println("═".repeat(64))

        val encoder = MemoryEncoder(
            videoEncoder = JvmVideoEncoder(),
            qrGenerator = JvmQRCodeGenerator()
        )

        val testSizes = mapOf(
            "Small (1KB)" to "The quick brown fox. ".repeat(50),
            "Medium (10KB)" to "Lorem ipsum dolor sit amet. ".repeat(350),
            "Large (100KB)" to "Sed do eiusmod tempor incididunt. ".repeat(3000)
        )

        testSizes.forEach { (name, message) ->
            val outputFile = File(tempDir, "bench-e2e-${System.nanoTime()}.mp4")
            outputFile.deleteOnExit()

            var success = false
            var fileSize = 0L
            var compression = 0f
            val time = measureTimeMillis {
                runBlocking {
                    encoder.addMessage(message)
                    val result = encoder.buildVideo(outputFile.absolutePath)
                    if (result.isSuccess && outputFile.exists()) {
                        success = true
                        fileSize = outputFile.length()
                        compression = message.toByteArray().size.toFloat() / fileSize
                    }
                }
            }
            if (success) {
                println("  %-20s: %6.0f ms | %8.1f KB | Compression: %.1f:1".format(
                    name, time.toDouble(), fileSize / 1024.0, compression
                ))
                results.add(BenchmarkResult("End-to-End", name, time.toDouble(),
                    "%.1f:1 compression".format(compression)))
            }
        }
    }

    private fun generateReport() {
        println("\nResults Summary by Category:")
        println("═".repeat(64))

        results.groupBy { it.category }.forEach { (category, categoryResults) ->
            println("\n$category:")
            categoryResults.forEach { result ->
                println("  %-35s: %8.2f ms".format(result.test, result.timeMs))
                if (result.throughput.isNotEmpty()) {
                    println("    └─ Throughput: ${result.throughput}")
                }
            }
        }

        println("\n" + "═".repeat(64))
        println("Benchmark Complete!")
        println("═".repeat(64))
    }
}

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "benchmark") {
        PerformanceBenchmark.runAll()
    } else {
        println("Usage: ./gradlew :kvid-examples:run --args=\"benchmark\"")
    }
}
