package com.kvid.examples

import com.kvid.core.*
import kotlinx.coroutines.*
import kotlin.system.measureTimeMillis

/**
 * Advanced example demonstrating performance characteristics and batch operations
 */
fun advancedExample() = runBlocking {
    println("=== KVID Advanced Example ===\n")

    val store = MemoryStore(chunkSize = 512)

    println("Generating synthetic messages...")
    val messages = generateSyntheticMessages(100)
    println("Generated ${messages.size} messages\n")

    println("Measuring batch add performance...")
    val addTime = measureTimeMillis {
        store.addMessages(messages).getOrThrow()
    }
    println("Added ${messages.size} messages in ${addTime}ms")
    println("Average: ${addTime / messages.size}ms per message\n")

    val stats = store.getStats()
    println("Store Statistics:")
    println("  - Messages: ${stats.totalMessages}")
    println("  - Chunks: ${stats.totalChunks}")
    println("  - Vector index size: ${stats.vectorIndexSize}")
    println("  - Avg chunk size: ${stats.averageChunkSize.toInt()} bytes\n")

    println("Measuring search performance...")
    val searchQueries = listOf(
        "artificial intelligence",
        "data science",
        "machine learning algorithms",
        "neural networks",
        "deep learning models"
    )

    for (query in searchQueries) {
        val searchTime = measureTimeMillis {
            store.search(query, topK = 10).getOrThrow()
        }
        println("  Search for '$query': ${searchTime}ms")
    }

    println("\n=== Advanced Example Complete ===")
}

/**
 * Example demonstrating embedding similarity
 */
suspend fun embeddingExample() {
    println("=== Embedding Example ===\n")

    val embedding = SimpleEmbedding()

    val text1 = "The quick brown fox jumps over the lazy dog"
    val text2 = "A fast auburn fox leaps over an inactive canine"
    val text3 = "Python is a programming language"

    println("Text 1: \"$text1\"")
    println("Text 2: \"$text2\"")
    println("Text 3: \"$text3\"\n")

    val emb1 = embedding.embed(text1)
    val emb2 = embedding.embed(text2)
    val emb3 = embedding.embed(text3)

    println("Similarity scores:")
    println("  Text 1 vs Text 2: ${embedding.similarity(emb1, emb2)} (similar meaning)")
    println("  Text 1 vs Text 3: ${embedding.similarity(emb1, emb3)} (different topics)")
    println("  Text 2 vs Text 3: ${embedding.similarity(emb2, emb3)} (different topics)\n")

    println("=== Embedding Example Complete ===")
}

/**
 * Example showing vector index operations
 */
fun vectorIndexExample() = runBlocking {
    println("=== Vector Index Example ===\n")

    val embedding = SimpleEmbedding()
    val index = InMemoryVectorIndex(embedding)

    println("Adding vectors to index...")
    val texts = listOf(
        "machine learning",
        "deep learning networks",
        "artificial intelligence systems",
        "cooking recipes",
        "baking instructions"
    )

    for ((id, text) in texts.withIndex()) {
        val vector = embedding.embed(text)
        index.add(id, vector).getOrThrow()
    }
    println("Added ${index.size()} vectors\n")

    println("Searching index...")
    val queryText = "neural networks and AI"
    val queryVector = embedding.embed(queryText)
    val results = index.search(queryVector, topK = 3)

    println("Query: \"$queryText\"")
    println("Top results:")
    results.forEach { result ->
        println("  - Text: \"${texts[result.id]}\"")
        println("    Similarity: ${result.similarity}")
    }

    println("\n=== Vector Index Example Complete ===")
}

/**
 * Generate synthetic messages for testing
 */
fun generateSyntheticMessages(count: Int): List<Message> {
    val topics = listOf(
        "machine learning", "data science", "artificial intelligence",
        "deep learning", "neural networks", "python programming",
        "kotlin development", "android apps", "software engineering",
        "database design", "cloud computing", "web development",
        "video processing", "image recognition", "natural language processing"
    )

    val adjectives = listOf(
        "fast", "efficient", "scalable", "reliable", "secure",
        "modern", "innovative", "advanced", "powerful", "elegant"
    )

    return (1..count).map { id ->
        val topic = topics.random()
        val adjective = adjectives.random()
        val content = """
            This is a message about $adjective $topic.
            It contains useful information and insights.
            The content demonstrates various concepts and techniques.
            Topics covered include ${topics.shuffled().take(2).joinToString(" and ")}.
        """.trimIndent()

        Message(
            id = id,
            content = content,
            source = "synthetic_${id % 5}",
            tags = listOf(topic, adjective)
        )
    }
}
