package com.kvid.core

import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * Benchmark tests for text compression performance
 */
class TextCompressionBenchmark {

    @Test
    fun benchmarkCompressionSpeed() {
        val sizes = listOf(100, 500, 1000, 5000, 10000)

        println("\n=== Compression Speed Benchmark ===")
        println("Size (chars) | Compress (ms) | Decompress (ms) | Throughput (MB/s)")
        println("-".repeat(70))

        for (size in sizes) {
            val text = generateNaturalText(size)

            // Warmup
            repeat(10) {
                TextCompression.compress(text)
                TextCompression.decompress(TextCompression.compress(text))
            }

            // Benchmark compression
            var compressed = ""
            val compressTime = measureNanoTime {
                compressed = TextCompression.compress(text)
            }

            // Benchmark decompression
            val decompressTime = measureNanoTime {
                TextCompression.decompress(compressed)
            }

            val compressMs = compressTime / 1_000_000.0
            val decompressMs = decompressTime / 1_000_000.0
            val throughputMBps = (size.toDouble() / 1_000_000) / (compressTime / 1_000_000_000.0)

            println("${size.toString().padEnd(12)} | ${String.format("%.3f", compressMs).padEnd(13)} | " +
                    "${String.format("%.3f", decompressMs).padEnd(15)} | ${String.format("%.2f", throughputMBps)}")
        }
    }

    @Test
    fun benchmarkCompressionRatios() {
        println("\n=== Compression Ratio Benchmark ===")
        println("Data Type          | Original (B) | Compressed (B) | Ratio   | Space Saved")
        println("-".repeat(75))

        val testCases = mapOf(
            "Repeating text" to "Hello World! ".repeat(100),
            "Natural prose" to generateNaturalText(1000),
            "JSON data" to generateJSON(50),
            "Random chars" to generateRandomText(1000),
            "Code snippet" to generateCodeSnippet(),
            "Whitespace heavy" to generateWhitespaceHeavy(),
            "Numbers sequence" to (1..500).joinToString(" "),
            "URL list" to (1..50).joinToString("\n") { "https://example.com/path/to/resource/$it" }
        )

        for ((name, text) in testCases) {
            val compressed = TextCompression.compress(text)
            val originalBytes = text.toByteArray().size
            val compressedBytes = compressed.toByteArray().size
            val ratio = compressedBytes.toDouble() / originalBytes
            val spaceSaved = ((1 - ratio) * 100)

            println("${name.padEnd(18)} | ${originalBytes.toString().padEnd(12)} | " +
                    "${compressedBytes.toString().padEnd(14)} | ${String.format("%.3f", ratio).padEnd(7)} | " +
                    "${String.format("%.1f%%", spaceSaved)}")
        }
    }

    @Test
    fun benchmarkBatchCompression() {
        println("\n=== Batch Compression Benchmark ===")

        val chunks = (1..100).map { generateNaturalText(200) }

        // Warmup
        repeat(5) {
            chunks.forEach { TextCompression.compress(it) }
        }

        val totalTime = measureNanoTime {
            chunks.forEach { TextCompression.compress(it) }
        }

        val avgTimePerChunk = totalTime / chunks.size / 1_000_000.0
        val chunksPerSecond = 1000.0 / avgTimePerChunk

        println("Chunks: ${chunks.size}")
        println("Average time per chunk: ${String.format("%.3f", avgTimePerChunk)} ms")
        println("Throughput: ${String.format("%.1f", chunksPerSecond)} chunks/second")
    }

    @Test
    fun benchmarkThresholdImpact() {
        println("\n=== Threshold Impact Benchmark ===")
        println("Threshold | Texts Compressed | Avg Ratio | Avg Time (ms)")
        println("-".repeat(60))

        val texts = (1..100).map { generateNaturalText(50 + it * 10) }
        val thresholds = listOf(0, 50, 100, 200, 500)

        for (threshold in thresholds) {
            var compressedCount = 0
            var totalRatio = 0.0
            var totalTime = 0L

            for (text in texts) {
                val time = measureNanoTime {
                    val compressed = TextCompression.compress(text, threshold)
                    if (TextCompression.isCompressed(compressed)) {
                        compressedCount++
                        totalRatio += compressed.length.toDouble() / text.length
                    }
                }
                totalTime += time
            }

            val avgRatio = if (compressedCount > 0) totalRatio / compressedCount else 0.0
            val avgTime = totalTime / texts.size / 1_000_000.0

            println("${threshold.toString().padEnd(9)} | ${compressedCount.toString().padEnd(16)} | " +
                    "${String.format("%.3f", avgRatio).padEnd(9)} | ${String.format("%.3f", avgTime)}")
        }
    }

    @Test
    fun benchmarkMemoryOverhead() {
        println("\n=== Memory Overhead Analysis ===")

        val testSizes = listOf(100, 500, 1000, 5000, 10000)

        println("Size (chars) | Original (B) | Compressed (B) | Base64 Overhead | Total Overhead")
        println("-".repeat(80))

        for (size in testSizes) {
            val text = generateNaturalText(size)
            val originalBytes = text.toByteArray().size

            val compressed = TextCompression.compress(text)
            val compressedBytes = compressed.toByteArray().size

            // Calculate raw gzip size
            val rawGzipBytes = compressBytes(text.toByteArray()).size
            val base64Overhead = compressedBytes - rawGzipBytes - TextCompression.COMPRESSION_PREFIX.length
            val totalOverhead = compressedBytes - rawGzipBytes

            println("${size.toString().padEnd(12)} | ${originalBytes.toString().padEnd(12)} | " +
                    "${compressedBytes.toString().padEnd(14)} | ${base64Overhead.toString().padEnd(15)} | $totalOverhead")
        }
    }

    @Test
    fun benchmarkCompressionConsistency() {
        println("\n=== Compression Consistency Test ===")

        val text = generateNaturalText(1000)
        val iterations = 100

        val sizes = mutableListOf<Int>()
        val times = mutableListOf<Long>()

        repeat(iterations) {
            val time = measureNanoTime {
                val compressed = TextCompression.compress(text)
                sizes.add(compressed.length)
            }
            times.add(time)
        }

        val avgSize = sizes.average()
        val avgTime = times.average() / 1_000_000.0
        val minTime = times.minOrNull()!! / 1_000_000.0
        val maxTime = times.maxOrNull()!! / 1_000_000.0
        val stdDev = kotlin.math.sqrt(times.map { (it / 1_000_000.0 - avgTime).let { d -> d * d } }.average())

        println("Iterations: $iterations")
        println("Consistent size: ${sizes.all { it == sizes[0] }}")
        println("Average time: ${String.format("%.3f", avgTime)} ms")
        println("Min time: ${String.format("%.3f", minTime)} ms")
        println("Max time: ${String.format("%.3f", maxTime)} ms")
        println("Std dev: ${String.format("%.3f", stdDev)} ms")
    }

    // Helper functions to generate test data
    private fun generateNaturalText(targetLength: Int): String {
        val sentences = listOf(
            "The quick brown fox jumps over the lazy dog.",
            "In the realm of software development, efficiency is paramount.",
            "Data compression algorithms reduce storage requirements significantly.",
            "Natural language processing enables machines to understand human text.",
            "Cloud computing has revolutionized how we store and access information.",
            "Machine learning models require vast amounts of training data.",
            "Text compression is essential for reducing bandwidth usage."
        )

        val result = StringBuilder()
        while (result.length < targetLength) {
            result.append(sentences.random()).append(" ")
        }
        return result.toString().take(targetLength)
    }

    private fun generateJSON(entries: Int): String {
        val items = (1..entries).joinToString(",\n") {
            """    {"id": $it, "name": "Item $it", "value": ${it * 100}, "active": ${it % 2 == 0}}"""
        }
        return "{\n  \"items\": [\n$items\n  ]\n}"
    }

    private fun generateRandomText(length: Int): String {
        val random = kotlin.random.Random(42)
        return (1..length).map { ('a'..'z').random(random) }.joinToString("")
    }

    private fun generateCodeSnippet(): String {
        return """
            fun processData(input: List<String>): Map<String, Int> {
                val result = mutableMapOf<String, Int>()
                for (item in input) {
                    result[item] = result.getOrDefault(item, 0) + 1
                }
                return result
            }
        """.trimIndent()
    }

    private fun generateWhitespaceHeavy(): String {
        return """
            {
                "data": {
                    "items": [
                        {
                            "id": 1,
                            "name": "Test"
                        },
                        {
                            "id": 2,
                            "name": "Another"
                        }
                    ]
                }
            }
        """.trimIndent()
    }
}
