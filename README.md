# KVID

KVID is a Kotlin Multiplatform library that stores text as QR codes inside MP4 video frames and keeps the data searchable through semantic embeddings and vector indexes.

## Relation to MemVid

KVID is a Kotlin Multiplatform port and evolution of [MemVid](https://github.com/yourusername/memvid), originally written in Python. While MemVid focused on server-side LLM memory systems, KVID extends the concept to mobile and cross-platform environments with native support for Android, iOS, and JVM. Both projects share the core innovation of using video compression for efficient text storage with QR codes, but KVID adds multiplatform capabilities, hardware-accelerated encoding, and enhanced semantic search features.

## What KVID Provides
- QR code generation and decoding across JVM/Android/iOS
- Video encoding/decoding adapters (FFmpeg/MediaCodec/VideoToolbox)
- Text chunking, embeddings, and in-memory vector indexes
- High-level APIs: `MemoryStore`, `MemoryEncoder`, and `MemoryDecoder`

## Prerequisites
- **FFmpeg** (optional, for JVM video encoding)

## Modules
- `kvid-core/` – shared APIs and platform wiring under `src/commonMain`, `androidMain`, `jvmMain`, and `iosMain`
- `kvid-examples/` – runnable samples and benchmarks invoked via `./gradlew :kvid-examples:run`

## Build and Test
```bash
./gradlew build            # Build everything and run tests
./gradlew :kvid-core:test  # Core tests only
```

## Quick Usage (MemoryStore)
```kotlin
import com.kvid.core.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val store = MemoryStore(chunkSize = 256)
    store.addMessages(
        listOf(
            Message(id = 1, content = "Hello, world"),
            Message(id = 2, content = "Semantic search is handy")
        )
    ).getOrThrow()

    val results = store.search("greeting", topK = 3).getOrThrow()
    results.forEach { println("${(it.relevance * 100).toInt()}% → ${it.content}") }
}
```

## Examples
Run examples from the repo root:
```bash
./gradlew :kvid-examples:run --args="<example>"
```

### Available Examples
- **Default** (no args): Basic memory-store demo with semantic search and stats
- **`persistence`**: Create → search → save (.bin) → load → update flow; files land in `kvid-examples/kvid-data/`
- **`persistence-load`**: Load an existing index (run `persistence` first) and perform searches
- **`advanced`**: Batch insert and search timing over synthetic data
- **`chunking`**: Inspect how `TextChunker` splits and annotates text
- **`vector-index`**: Work directly with `InMemoryVectorIndex`
- **`embedding`**: Compare semantic similarity scores between texts
- **`benchmark`**: Standard benchmarks (~3 minutes) - text chunking, QR generation, embeddings, vector search, video encoding/decoding
- **`benchmark-advanced`**: Advanced benchmarks (~15 minutes) - memory profiling, scalability testing, parameter tuning
- **`benchmark-stress`**: Stress benchmarks (~20 minutes) - large dataset handling, long-running operations, memory leak detection
- **`qrtest`**: Basic QR-code sanity check

## Persistence

KVID allows you to save and load vector indexes for persistent storage:

### Save and Load Workflow
```bash
# Step 1: Create and save data
./gradlew :kvid-examples:run --args="persistence"

# Step 2: Load and search saved data
./gradlew :kvid-examples:run --args="persistence-load"
```

### Code Example
```kotlin
// Create and save
val store = MemoryStore(chunkSize = 256)
store.addMessages(messages).getOrThrow()

val embedding = SimpleEmbedding()
val index = JvmHnswVectorIndex(embedding)
// Add vectors...
index.save("/path/to/index.bin").getOrThrow()

// Load and search
val index = JvmHnswVectorIndex(embedding)
index.load("/path/to/index.bin").getOrThrow()
val results = index.search(queryVector, topK = 5)
```

**File Locations**: Saved indexes are stored in `kvid-examples/kvid-data/` with `.bin` extension (e.g., `messages-index.bin`).

## Architecture

### Data Flow

**Encoding Pipeline:**
```
Text Messages → TextChunker → QR Codes → Video Frames → MP4 File
                     ↓
               Embeddings → Vector Index
```

**Search Pipeline:**
```
User Query → Embedding → Vector Search → Frame IDs → QR Decode → Messages
```

### Platform Abstraction
KVID uses Kotlin Multiplatform with platform-specific implementations:

- **commonMain/**: Interfaces and core logic (TextChunker, SemanticEmbedding, etc.)
- **androidMain/**: MediaCodec for hardware-accelerated video encoding
- **jvmMain/**: FFmpeg wrapper for video encoding
- **iosMain/**: VideoToolbox and AVFoundation for video encoding

### Key Components
- **QRCodeGenerator**: Converts text to QR code images (ZXing on JVM/Android, Core Image on iOS)
- **VideoEncoder/Decoder**: Handles MP4 video creation and frame extraction
- **SemanticEmbedding**: Generates vector representations of text
- **VectorIndex**: Stores and searches embedding vectors (InMemoryVectorIndex, HNSW)
- **TextChunker**: Splits text into semantic chunks with configurable size and overlap

## API Quick Reference

### Create a Store
```kotlin
val store = MemoryStore(chunkSize = 512)
```

### Add and Search Messages
```kotlin
// Add messages
store.addMessages(listOf(
    Message(id = 1, content = "Your message here", source = "user_1")
)).getOrThrow()

// Search
val results = store.search("query", topK = 5).getOrThrow()
results.forEach { println("${it.relevance}: ${it.content}") }
```

### Text Chunking
```kotlin
val chunker = TextChunker(
    chunkSize = 512,
    overlapSize = 32,
    preserveSentences = true
)
val chunks = chunker.chunk("Your long text...")
```

### Embeddings
```kotlin
val embedding = SimpleEmbedding()
val vector = embedding.embed("Hello world")
val similarity = embedding.similarity(vec1, vec2)
```

### Video Encoding (when available)
```kotlin
val encoder = MemoryEncoder(
    qrGenerator = JvmQRCodeGenerator(),
    videoEncoder = JvmVideoEncoder(),
    chunkSize = 512
)
encoder.addMessage("Message 1").getOrThrow()
encoder.buildVideo("output.mp4", params).getOrThrow()
```

## Performance Benchmarks

KVID includes comprehensive benchmarking:

### Typical Performance (modern hardware)
- **Text Chunking**: 20,000+ chunks/sec
- **QR Generation**: 100+ QR codes/sec
- **Embedding (Single)**: 100+ embeddings/sec
- **Vector Search (HNSW)**: 20-67 queries/sec
- **Video Compression**: 50:1 to 150:1 ratio
- **Memory per Vector**: 1,800-2,500 bytes

### Benchmark Commands
```bash
./gradlew :kvid-examples:run --args="benchmark"           # Quick test (~3 min)
./gradlew :kvid-examples:run --args="benchmark-advanced"  # Deep analysis (~15 min)
./gradlew :kvid-examples:run --args="benchmark-stress"    # Stress test (~20 min)
```

## Android Support

### MediaCodec Integration
KVID uses MediaCodec for hardware-accelerated video encoding on Android (API 21+):

**Features:**
- Hardware-accelerated encoding when available
- Automatic fallback to software encoding
- Support for H.264, H.265, VP9, and AV1 codecs
- RGB to YUV420 color space conversion
- Frame-by-frame encoding with proper timestamps

**Usage:**
```kotlin
val encoder = AndroidVideoEncoder()
encoder.initialize(VideoEncodingParams(256, 256, 30, VideoCodec.H264))
encoder.addFrame(frameData, frameNumber).getOrThrow()
val stats = encoder.finalize("/path/to/output.mp4").getOrThrow()
```

**Codec Detection:**
```kotlin
val codecs = getAvailableVideoCodecs()  // ["H.264", "H.265", "VP9"]
```

## iOS Support

### Native Framework Integration
KVID uses native Apple frameworks for iOS (iOS 11.0+):

**Components:**
- **QR Generation**: Core Image's CIFilter
- **Video Encoding**: AVFoundation + VideoToolbox (H.264/H.265)
- **Video Decoding**: AVFoundation's frame extraction
- **QR Decoding**: Vision Framework's barcode detection

**Features:**
- Hardware-accelerated video encoding/decoding
- No external dependencies (native frameworks only)
- Full async/coroutine support

**Usage:**
```kotlin
val generator = IosQRCodeGenerator()
val encoder = IosVideoEncoder()
val decoder = IosVideoDecoder()
val qrDecoder = IosQRCodeDecoder()
```

## Status
Core APIs, QR generation, chunking, embeddings, and in-memory search are implemented. Video encoding/decoding is production-ready on Android and iOS, with JVM support via FFmpeg. See the examples section above for the latest runnable flows.

MIT License
