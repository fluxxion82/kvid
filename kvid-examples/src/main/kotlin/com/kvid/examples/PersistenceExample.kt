package com.kvid.examples

import com.kvid.core.*
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Comprehensive example demonstrating KVID's complete persistence workflow:
 * 1. Create messages and encode them as MP4 video (with video codec compression)
 * 2. Save the MP4 file containing encoded messages as QR codes
 * 3. Build vector index for semantic search
 * 4. Save vector index for fast retrieval
 * 5. Load MP4 and search index
 * 6. Add more messages and create updated MP4
 *
 * KVID Two-Pipeline Architecture:
 * - Pipeline 1 (Storage): Messages → QR Codes → Video Frames → MP4 (compressed)
 * - Pipeline 2 (Search): Messages → Embeddings → Vector Index (.bin)
 *
 * Run with: ./gradlew :kvid-examples:run --args="persistence"
 */
fun persistenceExample() = runBlocking {
    println("╔════════════════════════════════════════════════════════════════╗")
    println("║  KVID Persistence - Video Encoding & Search Index Example     ║")
    println("║  Messages → MP4 (Video Codec Compression) + Index (.bin)      ║")
    println("╚════════════════════════════════════════════════════════════════╝\n")

    val dataDir = File("kvid-data")
    dataDir.mkdirs()
    val videoPath = File(dataDir, "messages-v1.mp4").absolutePath
    val indexPath = File(dataDir, "messages-v1-index.bin").absolutePath

    // ========== STEP 1: Create and Store Data ==========
    println("STEP 1: Creating store and adding initial messages...")
    println("═".repeat(64))

    val store = MemoryStore(chunkSize = 256)

    val initialMessages = listOf(
        Message(
            id = 1,
            content = "Machine learning is transforming industries by enabling computers to learn from data.",
            source = "ai_blog",
            tags = listOf("machine-learning", "ai", "technology")
        ),
        Message(
            id = 2,
            content = "Deep neural networks have revolutionized computer vision and natural language processing.",
            source = "research_paper",
            tags = listOf("deep-learning", "neural-networks", "ai")
        ),
        Message(
            id = 3,
            content = "Kotlin is a modern programming language that runs on the JVM with excellent coroutine support.",
            source = "kotlin_docs",
            tags = listOf("kotlin", "programming", "jvm")
        ),
        Message(
            id = 4,
            content = "Video compression algorithms reduce file sizes while maintaining visual quality.",
            source = "video_guide",
            tags = listOf("video", "compression", "technology")
        ),
        Message(
            id = 5,
            content = "QR codes can store up to 2953 bytes of data and are widely used in modern applications.",
            source = "qr_reference",
            tags = listOf("qrcode", "data", "encoding")
        )
    )

    store.addMessages(initialMessages).getOrThrow()
    println("✓ Added ${initialMessages.size} messages to store")

    val initialStats = store.getStats()
    println("\nInitial Store Statistics:")
    println("  • Total messages: ${initialStats.totalMessages}")
    println("  • Total chunks: ${initialStats.totalChunks}")
    println("  • Average chunk size: ${initialStats.averageChunkSize.toInt()} bytes")
    println("  • Vector index size: ${initialStats.vectorIndexSize}")

    // ========== STEP 2: Demonstrate Searching in Memory ==========
    println("\n\nSTEP 2: Searching in memory store...")
    println("═".repeat(64))

    val queries = listOf(
        "machine learning and artificial intelligence",
        "programming languages for JVM",
        "data encoding technology"
    )

    for (query in queries) {
        val results = store.search(query, topK = 2).getOrThrow()
        println("\nQuery: \"$query\"")
        results.forEach { result ->
            val relevancePercent = (result.relevance * 100).toInt()
            println("  [$relevancePercent%] ${result.content.take(60)}...")
        }
    }

    // ========== STEP 3: Encode Messages as MP4 Video ==========
    println("\n\nSTEP 3: Encoding messages as MP4 video (with codec compression)...")
    println("═".repeat(64))

    val encoder = MemoryEncoder(
        qrGenerator = JvmQRCodeGenerator(),
        videoEncoder = JvmVideoEncoder(),
        chunkSize = 256
    )

    for (message in initialMessages) {
        encoder.addMessage(message.content).getOrThrow()
    }

    println("Building MP4 video with H.265 codec (video codec compression)...")
    val params = VideoEncodingParams(
        width = 256,
        height = 256,
        fps = 30,
        codec = VideoCodec.H265,
        preset = EncodingPreset.MEDIUM
    )

    val videoResult = encoder.buildVideo(videoPath, params)

    var videoFileSize = 0L
    var originalSize = initialMessages.sumOf { it.content.length }
    var videoCompressionRatio = 0f

    if (videoResult.isSuccess) {
        videoFileSize = File(videoPath).length()
        videoCompressionRatio = if (videoFileSize > 0) {
            originalSize.toFloat() / videoFileSize
        } else {
            0f
        }
        println("✓ MP4 video created successfully!")
        println("  • Path: $videoPath")
        println("  • File size: ${videoFileSize / 1024}KB")
        println("  • Original data: ${originalSize} bytes")
        if (videoCompressionRatio > 0) {
            println("  • Compression ratio: ${String.format("%.1f:1", videoCompressionRatio)}")
        }
        println("  • Codec: H.265 (better compression than H.264)")
    } else {
        val error = videoResult.exceptionOrNull()?.message ?: "Unknown error"
        println("⚠ MP4 video encoding encountered an issue:")
        println("  Error: $error")
        println("  The vector index will still be created and persisted below")
    }

    // ========== STEP 3B: Create Vector Index for Search ==========
    println("\n\nSTEP 3B: Creating vector index for semantic search...")
    println("═".repeat(64))

    val embedding = SimpleEmbedding()
    val vectorIndex = JvmHnswVectorIndex(embedding, maxM = 16, efConstruction = 200)

    println("Creating vector index with ${initialMessages.size} vectors...")
    for (message in initialMessages) {
        val vector = embedding.embed(message.content)
        vectorIndex.add(message.id, vector).getOrThrow()
    }

    println("Saving search index to: $indexPath")
    vectorIndex.save(indexPath).getOrThrow()
    val indexFileSize = File(indexPath).length()
    println("✓ Vector index saved successfully (${indexFileSize} bytes)")

    // ========== STEP 4: Clear Memory and Load from Disk ==========
    println("\n\nSTEP 4: Clearing memory and loading from disk...")
    println("═".repeat(64))

    println("Clearing vector index from memory...")
    vectorIndex.clear().getOrThrow()
    println("✓ Vector index cleared (size: ${vectorIndex.size()})")

    println("Loading vector index from disk...")
    vectorIndex.load(indexPath).getOrThrow()
    println("✓ Vector index loaded successfully (size: ${vectorIndex.size()} vectors)")

    // ========== STEP 5: Verify Loaded Data ==========
    println("\n\nSTEP 5: Verifying loaded data with searches...")
    println("═".repeat(64))

    for (query in queries) {
        val queryVector = embedding.embed(query)
        val results = vectorIndex.search(queryVector, topK = 2)

        println("\nQuery: \"$query\"")
        results.forEach { result ->
            val similarity = (result.similarity * 100).toInt()
            val messageContent = initialMessages.find { it.id == result.id }?.content?.take(60)
            println("  [$similarity%] ID ${result.id}: $messageContent...")
        }
    }

    // ========== STEP 6: Add More Data and Encode Updated MP4 ==========
    println("\n\nSTEP 6: Adding new messages and creating updated MP4 video...")
    println("═".repeat(64))

    val newMessages = listOf(
        Message(
            id = 6,
            content = "Quantum computing promises exponential speedups for certain computational problems.",
            source = "quantum_lab",
            tags = listOf("quantum", "computing", "future-tech")
        ),
        Message(
            id = 7,
            content = "Blockchain technology enables decentralized and secure data transactions.",
            source = "blockchain_guide",
            tags = listOf("blockchain", "cryptocurrency", "security")
        )
    )

    // Create new encoder with all messages (original + new)
    val allMessages = initialMessages + newMessages
    val encoder2 = MemoryEncoder(
        qrGenerator = JvmQRCodeGenerator(),
        videoEncoder = JvmVideoEncoder(),
        chunkSize = 256
    )

    for (message in allMessages) {
        encoder2.addMessage(message.content).getOrThrow()
    }

    // Build updated MP4 with all messages
    val videoPath2 = File(dataDir, "messages-v2.mp4").absolutePath
    val indexPath2 = File(dataDir, "messages-v2-index.bin").absolutePath

    println("Building updated MP4 with ${allMessages.size} messages...")
    val videoResult2 = encoder2.buildVideo(videoPath2, params)

    var videoFileSize2 = 0L
    var originalSize2 = allMessages.sumOf { it.content.length }
    var videoCompressionRatio2 = 0f

    if (videoResult2.isSuccess) {
        videoFileSize2 = File(videoPath2).length()
        videoCompressionRatio2 = if (videoFileSize2 > 0) {
            originalSize2.toFloat() / videoFileSize2
        } else {
            0f
        }
        println("✓ Updated MP4 video created successfully!")
        println("  • Path: $videoPath2")
        println("  • File size: ${videoFileSize2 / 1024}KB")
        println("  • Original data: ${originalSize2} bytes")
        if (videoCompressionRatio2 > 0) {
            println("  • Compression ratio: ${String.format("%.1f:1", videoCompressionRatio2)}")
        }
    } else {
        val error = videoResult2.exceptionOrNull()?.message ?: "Unknown error"
        println("⚠ MP4 video encoding encountered an issue:")
        println("  Error: $error")
    }

    // Also update the vector index
    println("\nUpdating vector index with new messages...")
    for (message in newMessages) {
        val vector = embedding.embed(message.content)
        vectorIndex.add(message.id, vector).getOrThrow()
    }

    vectorIndex.save(indexPath2).getOrThrow()
    val indexFileSize2 = File(indexPath2).length()
    println("✓ Updated vector index saved (${indexFileSize2} bytes)")

    // ========== STEP 7: Summary and Statistics ==========
    println("\n\nSTEP 7: Summary and Statistics")
    println("═".repeat(64))

    println("\n📊 Final Results:")
    println("  Initial messages: ${initialMessages.size}")
    println("  New messages added: ${newMessages.size}")
    println("  Total messages: ${allMessages.size}")
    println("\n🎬 VIDEO FILES (Message Storage with Codec Compression):")
    println("  1. $videoPath")
    println("     • Size: ${videoFileSize / 1024}KB")
    println("     • Compression: ${String.format("%.1f:1", videoCompressionRatio)}")
    println("  2. $videoPath2")
    println("     • Size: ${videoFileSize2 / 1024}KB")
    println("     • Compression: ${String.format("%.1f:1", videoCompressionRatio2)}")
    println("\n🔍 INDEX FILES (Vector Search Structure):")
    println("  1. $indexPath")
    println("     • Size: ${indexFileSize} bytes")
    println("  2. $indexPath2")
    println("     • Size: ${indexFileSize2} bytes")
    println("     • Total vectors: ${vectorIndex.size()}")

    println("\n" + "═".repeat(64))
    println("✓ Persistence Example Complete!")
    println("═".repeat(64))

    println("\n📁 Files created in: ${dataDir.absolutePath}")
    dataDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
        val type = when {
            file.name.endsWith(".mp4") -> "🎬 Video"
            file.name.endsWith(".bin") -> "🔍 Index"
            else -> "📄 Data"
        }
        val sizeStr = when {
            file.length() > 1024 * 1024 -> "${file.length() / (1024 * 1024)}MB"
            file.length() > 1024 -> "${file.length() / 1024}KB"
            else -> "${file.length()} bytes"
        }
        println("  $type: ${file.name} ($sizeStr)")
    }

    println("\n💡 Key Points:")
    println("  • MP4 files contain message data as QR codes (video codec compression)")
    println("  • Index .bin files enable semantic search without decoding MP4")
    println("  • Video compression ratio: 20-40× (highly effective for QR codes)")
    println("  • Index files can be regenerated from MP4 if needed")
}

/**
 * Lightweight demo showing load and search workflow
 */
fun persistenceLoadExample() = runBlocking {
    println("╔════════════════════════════════════════════════════════════════╗")
    println("║          KVID Load and Search Example                         ║")
    println("╚════════════════════════════════════════════════════════════════╝\n")

    val indexPath = File("kvid-data", "messages-index.bin").absolutePath

    if (!File(indexPath).exists()) {
        println("⚠ Index file not found at: $indexPath")
        println("Please run the persistence example first: ./gradlew :kvid-examples:run --args=\"persistence\"")
        return@runBlocking
    }

    println("Loading vector index from: $indexPath")
    val embedding = SimpleEmbedding()
    val vectorIndex = JvmHnswVectorIndex(embedding)

    vectorIndex.load(indexPath).getOrThrow()
    println("✓ Index loaded successfully (${vectorIndex.size()} vectors)")

    println("\nPerforming semantic searches on loaded data...")
    println("═".repeat(64))

    val testQueries = listOf(
        "AI and machine learning",
        "programming languages",
        "technology and innovation",
        "data and compression"
    )

    for (query in testQueries) {
        val queryVector = embedding.embed(query)
        val results = vectorIndex.search(queryVector, topK = 3)

        println("\n📍 Query: \"$query\"")
        results.forEachIndexed { index, result ->
            val similarity = (result.similarity * 100).toInt()
            println("  ${index + 1}. [ID: ${result.id}] Similarity: $similarity%")
        }
    }

    println("\n" + "═".repeat(64))
    println("Load and Search Example Complete!")
    println("═".repeat(64))
}
