package com.kvid.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * High-level API for storing and searching messages/data
 */
class MemoryStore(
    private val chunkSize: Int = 512,
    private val embedModel: SemanticEmbedding = SimpleEmbedding(),
    private val vectorIndex: VectorIndex = InMemoryVectorIndex(SimpleEmbedding())
) {
    private val chunker = TextChunker(chunkSize = chunkSize)
    private val chunks = mutableListOf<StoredChunk>()
    private val metadata = mutableMapOf<Int, ChunkMetadata>()

    /**
     * Add messages to the store
     */
    suspend fun addMessages(messages: List<Message>): Result<Unit> {
        return try {
            for (message in messages) {
                addMessage(message)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Add a single message
     */
    suspend fun addMessage(message: Message): Result<Int> {
        return try {
            val textChunks = chunker.chunk(message.content)
            val chunkIds = mutableListOf<Int>()

            for (textChunk in textChunks) {
                val chunkId = chunks.size
                val embedding = embedModel.embed(textChunk.content)

                chunks.add(
                    StoredChunk(
                        id = chunkId,
                        content = textChunk.content,
                        embedding = embedding,
                        messageId = message.id
                    )
                )

                metadata[chunkId] = ChunkMetadata(
                    messageId = message.id,
                    timestamp = message.timestamp,
                    source = message.source,
                    sequenceNumber = textChunk.sequenceNumber,
                    tags = message.tags
                )

                vectorIndex.add(chunkId, embedding).getOrThrow()
                chunkIds.add(chunkId)
            }

            Result.success(message.id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Search for similar messages
     */
    suspend fun search(query: String, topK: Int = 5): Result<List<SearchResult>> {
        return try {
            val queryEmbedding = embedModel.embed(query)
            val vectorResults = vectorIndex.search(queryEmbedding, topK * 2) // Search more to deduplicate by message

            val resultsByMessage = mutableMapOf<Int, Pair<StoredChunk, ChunkMetadata>>()

            for (vectorResult in vectorResults) {
                if (resultsByMessage.size >= topK) break

                val chunk = chunks.getOrNull(vectorResult.id) ?: continue
                val meta = metadata[vectorResult.id] ?: continue

                resultsByMessage.getOrPut(chunk.messageId) { Pair(chunk, meta) }
            }

            val results = resultsByMessage.values.map { (chunk, meta) ->
                SearchResult(
                    messageId = chunk.messageId,
                    content = chunk.content,
                    relevance = embedModel.similarity(queryEmbedding, chunk.embedding),
                    timestamp = meta.timestamp,
                    source = meta.source
                )
            }

            Result.success(results.sortedByDescending { it.relevance })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get statistics about stored data
     */
    fun getStats(): StoreStats {
        return StoreStats(
            totalChunks = chunks.size,
            totalMessages = metadata.values.map { it.messageId }.toSet().size,
            vectorIndexSize = vectorIndex.size(),
            averageChunkSize = if (chunks.isEmpty()) 0.0 else chunks.map { it.content.length }.average()
        )
    }

    /**
     * Export all chunks as JSON string
     * Returns a simple JSON-like representation of the chunks
     */
    fun exportIndex(): String {
        if (chunks.isEmpty()) {
            return "{ \"chunks\": [] }"
        }

        // Manually build JSON since @Serializable on local classes has issues
        val jsonChunks = chunks.map { chunk ->
            """{"id":${chunk.id},"content":"${chunk.content.replace("\"", "\\\"")}","messageId":${chunk.messageId}}"""
        }.joinToString(",")

        return """{ "chunks": [$jsonChunks] }"""
    }

    /**
     * Clear all data
     */
    suspend fun clear(): Result<Unit> {
        return try {
            chunks.clear()
            metadata.clear()
            vectorIndex.clear().getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

@OptIn(ExperimentalTime::class)
@Serializable
data class Message(
    val id: Int,
    val content: String,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    val source: String = "default",
    val tags: List<String> = emptyList()
)

@Serializable
data class StoredChunk(
    val id: Int,
    val content: String,
    @Serializable(with = FloatArraySerializer::class)
    val embedding: FloatArray,
    val messageId: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredChunk) return false
        if (id != other.id) return false
        if (content != other.content) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (messageId != other.messageId) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + content.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + messageId
        return result
    }
}

data class ChunkMetadata(
    val messageId: Int,
    val timestamp: Long,
    val source: String,
    val sequenceNumber: Int,
    val tags: List<String> = emptyList()
)

data class SearchResult(
    val messageId: Int,
    val content: String,
    val relevance: Float,  // 0-1, higher is better
    val timestamp: Long,
    val source: String
)

data class StoreStats(
    val totalChunks: Int,
    val totalMessages: Int,
    val vectorIndexSize: Int,
    val averageChunkSize: Double
)

/**
 * Serialize FloatArray as a list of floats for multiplatform compatibility.
 */
object FloatArraySerializer : KSerializer<FloatArray> {
    private val delegate = ListSerializer(Float.serializer())

    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: FloatArray) {
        delegate.serialize(encoder, value.toList())
    }

    override fun deserialize(decoder: Decoder): FloatArray {
        return delegate.deserialize(decoder).toFloatArray()
    }
}
