// A Swift command-line consumer of the KvidEmbed XCFramework (implementation plan, Task 8 and R13): it
// opens an OnnxEmbedder through the Kotlin Objective-C export and embeds one text, so the ONNX Runtime
// path inside the static framework is live and cannot be dead-stripped. check.sh links it for the
// simulator and the device and runs the simulator build with `xcrun simctl spawn`.
//
// Usage: kvid-swift-check MODEL_ONNX VOCAB_TXT [TEXT]   (TEXT defaults to "hello world")
// Prints one line: [kvid-swift-check] model=... dims=N first=v0,v1,v2,... norm=...
// Exits 1 on any error. Kotlin's suspend functions are exported with completion handlers, which Swift
// imports as async functions: OnnxEmbedder.Companion.open(...) and OnnxEmbedder.embed(texts:).
import Foundation
import KvidEmbed

let args = CommandLine.arguments
guard args.count == 3 || args.count == 4 else {
    FileHandle.standardError.write("usage: kvid-swift-check MODEL_ONNX VOCAB_TXT [TEXT]\n".data(using: .utf8)!)
    exit(64)
}
let text = args.count == 4 ? args[3] : "hello world"

do {
    // The exported API has no default arguments: the model and the options are passed explicitly, with
    // OnnxOptions' Kotlin defaults.
    let options = OnnxOptions(intraOpThreads: nil, maxBatchSize: 4, sortByLength: true, cpuMemoryArena: true, memoryPattern: true)
    let embedder = try await OnnxEmbedder.companion.open(
        modelPath: args[1], vocabPath: args[2], model: BertEmbeddingModel.companion.BGE_SMALL_EN_V1_5, options: options)
    // spec is kvid-core's EmbeddingSpec, exported by the same framework.
    let spec = embedder.spec
    let vectors = try await embedder.embed(texts: [text])
    embedder.close()
    guard vectors.count == 1 else {
        print("[kvid-swift-check] error: \(vectors.count) vectors for one text")
        exit(1)
    }
    let vector = vectors[0]
    let size = Int(vector.size)
    var norm = 0.0
    var first: [String] = []
    for i in 0..<size {
        let v = Double(vector.get(index: Int32(i)))
        norm += v * v
        if i < 8 { first.append(String(format: "%.8f", v)) }
    }
    print("[kvid-swift-check] model=\(spec.model) dims=\(size) spec_dims=\(spec.dimensions) first=\(first.joined(separator: ",")) norm=\(String(format: "%.6f", norm.squareRoot()))")
    exit(size == Int(spec.dimensions) ? 0 : 1)
} catch {
    print("[kvid-swift-check] error: \(error)")
    exit(1)
}
