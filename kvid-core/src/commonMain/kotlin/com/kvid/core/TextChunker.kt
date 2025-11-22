package com.kvid.core

/**
 * Intelligent text chunking with semantic awareness
 */
class TextChunker(
    val chunkSize: Int = 512,
    val overlapSize: Int = 32,
    val preserveSentences: Boolean = true
) {
    /**
     * Chunk text into semantically meaningful pieces
     */
    fun chunk(text: String): List<TextChunk> {
        if (text.isBlank()) return emptyList()

        val chunks = mutableListOf<TextChunk>()
        var currentPos = 0

        while (currentPos < text.length) {
            val endPos = minOf(currentPos + chunkSize, text.length)
            var actualEnd = endPos

            if (preserveSentences && actualEnd < text.length) {
                val nextSentenceEnd = findNextSentenceBoundary(text, actualEnd)
                if (nextSentenceEnd > actualEnd && nextSentenceEnd - currentPos < chunkSize * 1.5) {
                    actualEnd = nextSentenceEnd
                }
            }

            val chunkText = text.substring(currentPos, actualEnd).trim()
            if (chunkText.isNotEmpty()) {
                chunks.add(
                    TextChunk(
                        content = chunkText,
                        startOffset = currentPos,
                        endOffset = actualEnd,
                        sequenceNumber = chunks.size
                    )
                )
            }

            if (actualEnd >= text.length) {
                break
            }

            val nextStart = (actualEnd - overlapSize).coerceAtLeast(0)
            currentPos = if (nextStart > currentPos) nextStart else actualEnd
        }

        return chunks
    }

    /**
     * Split already-chunked text into smaller pieces
     */
    fun rechunk(chunks: List<String>): List<TextChunk> {
        return chunks.flatMapIndexed { index, text ->
            val subChunks = chunk(text)
            subChunks.mapIndexed { subIndex, chunk ->
                chunk.copy(parentChunkIndex = index, sequenceNumber = index * 1000 + subIndex)
            }
        }
    }

    private fun findNextSentenceBoundary(text: String, pos: Int): Int {
        val sentenceEnds = listOf('.', '!', '?')
        var currentPos = pos
        while (currentPos < text.length) {
            if (text[currentPos] in sentenceEnds) {
                var endPos = currentPos + 1
                while (endPos < text.length && text[endPos].isWhitespace()) {
                    endPos++
                }
                return endPos
            }
            currentPos++
        }
        return text.length
    }

    /**
     * Estimate token count (rough approximation)
     */
    companion object {
        fun estimateTokens(text: String): Int {
            return (text.length / 4) + 1
        }

        fun estimateChunkTokens(chunk: String): Int {
            return estimateTokens(chunk)
        }
    }
}

data class TextChunk(
    val content: String,
    val startOffset: Int,
    val endOffset: Int,
    val sequenceNumber: Int,
    val parentChunkIndex: Int = -1,
    val metadata: Map<String, String> = emptyMap()
) {
    val length: Int get() = content.length
    val tokenCount: Int get() = TextChunker.estimateTokens(content)
}
