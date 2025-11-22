package com.kvid.examples

import com.kvid.core.*
import kotlinx.coroutines.runBlocking

/**
 * Basic example demonstrating KVID message storage and retrieval
 */
fun main(args: Array<String>) = runBlocking {
    when {
        args.contains("benchmark") -> {
            PerformanceBenchmark.runAll()
            return@runBlocking
        }
        args.contains("benchmark-advanced") -> {
            AdvancedBenchmarks.runAll()
            return@runBlocking
        }
        args.contains("benchmark-stress") -> {
            StressBenchmarks.runAll()
            return@runBlocking
        }
        args.contains("qrtest") -> {
            main()
            return@runBlocking
        }
        args.contains("persistence") -> {
            persistenceExample()
            return@runBlocking
        }
        args.contains("persistence-load") -> {
            persistenceLoadExample()
            return@runBlocking
        }
        args.contains("advanced") -> {
            advancedExample()
            return@runBlocking
        }
        args.contains("embedding") -> {
            embeddingExample()
            return@runBlocking
        }
        args.contains("vector-index") -> {
            vectorIndexExample()
            return@runBlocking
        }
        args.contains("chunking") -> {
            chunkingExample()
            return@runBlocking
        }
    }

    println("=== KVID Basic Example ===\n")

    println("1. Creating memory store...")
    val store = MemoryStore(chunkSize = 256)

    println("2. Adding messages...\n")
    val messages = listOf(
        Message(
            id = 1,
            content = "Hello, this is my first message stored in video. It contains important information.",
            source = "user_1",
            tags = listOf("greeting", "important")
        ),
        Message(
            id = 2,
            content = "The quick brown fox jumps over the lazy dog. This is a famous pangram used in typography.",
            source = "user_2",
            tags = listOf("typography", "reference")
        ),
        Message(
            id = 3,
            content = "Kotlin is a modern programming language that runs on the JVM and offers concise syntax.",
            source = "user_1",
            tags = listOf("programming", "kotlin")
        ),
        Message(
            id = 4,
            content = "Video compression algorithms reduce file size by exploiting spatial and temporal redundancy.",
            source = "user_3",
            tags = listOf("video", "compression")
        ),
        Message(
            id = 5,
            content = "QR codes can store up to 2953 bytes of data and are widely used for product identification.",
            source = "user_2",
            tags = listOf("qrcode", "data")
        )
    )

    store.addMessages(messages).getOrThrow()
    println("✓ Added ${messages.size} messages\n")

    println("3. Store Statistics:")
    val stats = store.getStats()
    println("   - Total messages: ${stats.totalMessages}")
    println("   - Total chunks: ${stats.totalChunks}")
    println("   - Average chunk size: ${stats.averageChunkSize.toInt()} bytes")
    println("   - Vector index size: ${stats.vectorIndexSize}\n")

    println("4. Semantic Searches:\n")

    val queries = listOf(
        "greeting and welcome",
        "programming languages",
        "data storage technology",
        "how to compress video"
    )

    for (query in queries) {
        println("   Query: \"$query\"")
        val results = store.search(query, topK = 2).getOrThrow()
        results.forEach { result ->
            val relevancePercent = (result.relevance * 100).toInt()
            println("      - [${relevancePercent}%] ${result.content.take(60)}...")
        }
        println()
    }

    println("5. Exporting index as JSON...")
    val indexJson = store.exportIndex()
    println("   Index size: ${indexJson.length} bytes\n")

    println("=== Example Complete ===")
}

/**
 * Example showing text chunking behavior
 */
fun chunkingExample() {
    println("=== Text Chunking Example ===\n")

    val chunker = TextChunker(
        chunkSize = 100,
        overlapSize = 20,
        preserveSentences = true
    )

    val longText = """
        Memvid is a video-based memory system that stores text as QR codes in MP4 files.
        It leverages video codec compression to achieve 50-100x smaller storage than traditional databases.
        The system provides fast semantic search capabilities across millions of text chunks.
    """.trimIndent()

    println("Original text length: ${longText.length} bytes\n")

    val chunks = chunker.chunk(longText)
    println("Number of chunks: ${chunks.size}\n")

    chunks.forEach { chunk ->
        println("Chunk #${chunk.sequenceNumber}:")
        println("  Content: \"${chunk.content}\"")
        println("  Length: ${chunk.length} bytes")
        println("  Tokens: ~${chunk.tokenCount}\n")
    }
}

/**
 * Example showing encoder API (for when video encoding is implemented)
 */
fun encoderExample() {
    println("=== Memory Encoder Example ===\n")

    val qrGenerator = JvmQRCodeGenerator()
    val videoEncoder = JvmVideoEncoder()

    val encoder = MemoryEncoder(
        qrGenerator = qrGenerator,
        videoEncoder = videoEncoder,
        chunkSize = 256
    )

    println("Encoder created")
    println("QR Capabilities: ${qrGenerator.getCapabilities()}")

    val stats = encoder.getStats()
    println("\nEncoder Stats:")
    println("  - Total chunks: ${stats.totalChunks}")
    println("  - Is encoding: ${stats.isEncoding}")
}
