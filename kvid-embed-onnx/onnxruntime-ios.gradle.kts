// ONNX Runtime for iOS: the pinned onnxruntime-c pod archive (scripts/runtime-pins.json), downloaded on
// demand and never committed. Applied by build.gradle.kts, which wires the cinterop and link tasks to
// verifyOnnxRuntimeIos on macOS hosts only, so JVM and Android builds never download anything.
//
// prepareOnnxRuntimeIos (cacheable) produces build/onnxruntime/ios:
//   Headers/onnxruntime_c_api.h and the headers it includes, LICENSE, the two source slice binaries
//   (onnxruntime.xcframework/<slice>/onnxruntime.framework/onnxruntime), and one archive per Kotlin
//   target, <target>/libonnxruntime.a: the ios-arm64 slice as is, the simulator slice thinned to arm64.
//   The ZIP lives in ${KVID_RUNTIME_CACHE:-~/.cache/kvid/runtime}/onnxruntime-c-<version>/, shared by
//   worktrees. Download (4 attempts with backoff), extraction and thinning run under a cross-process lock
//   in that directory; the tree is built in a temporary directory and renamed into place atomically.
//
// verifyOnnxRuntimeIos (never up-to-date, never cached) checks the SHA-256 of the ZIP and of every
// produced file against the pins on every build, also when the producer was up-to-date or restored from
// the build cache. A derived file that does not match is deleted and recreated from the verified ZIP once;
// a ZIP that does not match is deleted and downloaded again once. A second mismatch fails, naming the file.

import groovy.json.JsonSlurper
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject

/** runtime-pins.json, parsed. */
@Suppress("UNCHECKED_CAST")
class OnnxRuntimePins(file: File) {
    class Target(val name: String, val slice: String, val thin: String?, val sha256: String) {
        val path = "$name/libonnxruntime.a"
    }

    val version: String
    val url: String
    val sha256: String
    val bytes: Long
    val files: Map<String, String>
    val targets: Map<String, Target>

    init {
        val json = JsonSlurper().parse(file) as Map<String, Any?>
        version = json["version"] as String
        val archive = json["archive"] as Map<String, Any?>
        url = archive["url"] as String
        sha256 = archive["sha256"] as String
        bytes = (archive["bytes"] as Number).toLong()
        files = json["files"] as Map<String, String>
        targets = (json["targets"] as Map<String, Map<String, Any?>>).mapValues { (name, t) ->
            Target(name, t["slice"] as String, t["thin"] as String?, t["sha256"] as String)
        }
        targets.values.forEach { require(it.slice in files) { "$file: target ${it.name} names slice ${it.slice}, which is not in \"files\"" } }
    }

    val zipName get() = url.substringAfterLast('/')
}

abstract class OnnxRuntimeIosTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pinsFile: RegularFileProperty

    /** ${KVID_RUNTIME_CACHE:-~/.cache/kvid/runtime}; an input, as the plan asks (not relocatable across machines). */
    @get:Input
    abstract val cacheRoot: Property<String>

    /** The Kotlin targets to produce archives for. */
    @get:Input
    abstract val targetNames: ListProperty<String>

    @get:Inject
    abstract val exec: ExecOperations

    protected fun pins() = OnnxRuntimePins(pinsFile.get().asFile)

    protected fun selected(pins: OnnxRuntimePins): List<OnnxRuntimePins.Target> = targetNames.get().map {
        pins.targets[it] ?: throw GradleException("${pinsFile.get().asFile}: no runtime target \"$it\" (pinned: ${pins.targets.keys})")
    }

    /** Every file the runtime directory must hold, with its pinned digest; sources before derived archives. */
    protected fun expected(pins: OnnxRuntimePins): Map<String, String> =
        pins.files + selected(pins).associate { it.path to it.sha256 }

    protected fun cacheDir(pins: OnnxRuntimePins) = File(cacheRoot.get(), "onnxruntime-c-${pins.version}")

    protected fun zipFile(pins: OnnxRuntimePins) = File(cacheDir(pins), pins.zipName)

    /** Runs [block] holding an exclusive lock on the cache directory, shared with other Gradle processes and worktrees. */
    protected fun <T> withCacheLock(pins: OnnxRuntimePins, block: () -> T): T {
        val dir = cacheDir(pins).apply { mkdirs() }
        val lockFile = File(dir, ".lock")
        // FileChannel.lock excludes other processes; the monitor excludes other threads of this daemon,
        // which would otherwise get OverlappingFileLockException.
        synchronized(lockFile.canonicalPath.intern()) {
            FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { return block() }
            }
        }
    }

    protected fun sha256(file: File): String? {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Downloads the archive to the cache: up to 4 attempts with backoff on network errors; a digest mismatch is final. */
    protected fun download(pins: OnnxRuntimePins, target: File) {
        val partial = File(target.parentFile, target.name + ".part")
        val attempts = 4
        for (attempt in 1..attempts) {
            logger.lifecycle("onnxruntime: downloading ${pins.url} (attempt $attempt of $attempts)")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                val connection = URI(pins.url).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 60_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "kvid-gradle-runtime")
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        val permanent = code in 400..499 && code != 408 && code != 429
                        val message = "HTTP $code for ${pins.url}"
                        if (permanent) throw GradleException("${pins.zipName}: download failed: $message")
                        throw IOException(message)
                    }
                    connection.inputStream.use { input ->
                        partial.outputStream().use { out ->
                            val buffer = ByteArray(1 shl 20)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                size += n
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                if (size != pins.bytes) throw IOException("truncated or oversized body: $size bytes, pinned ${pins.bytes}")
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != pins.sha256) {
                    partial.delete()
                    throw GradleException("${target}: digest mismatch for ${pins.url}\n  expected ${pins.sha256}\n  got      $actual")
                }
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                logger.lifecycle("onnxruntime: downloaded and verified ${pins.zipName} (${pins.sha256})")
                return
            } catch (error: IOException) {
                partial.delete()
                if (attempt == attempts) throw GradleException("${pins.zipName}: download failed (attempt $attempt of $attempts): $error\n  ${pins.url}", error)
                val delay = 1L shl attempt
                logger.warn("onnxruntime: attempt $attempt failed ($error); retrying in ${delay}s")
                Thread.sleep(delay * 1000)
            }
        }
    }

    /** Returns the cached, verified ZIP. A missing ZIP is downloaded; one that does not match is deleted and downloaded once. */
    protected fun verifiedZip(pins: OnnxRuntimePins): File {
        val zip = zipFile(pins)
        File(zip.parentFile, zip.name + ".part").delete()
        val actual = sha256(zip)
        when (actual) {
            pins.sha256 -> logger.info("onnxruntime: ${zip.name} verified ($actual)")
            null -> download(pins, zip)
            else -> {
                logger.warn("onnxruntime: $zip does not match the pin (expected ${pins.sha256}, got $actual); downloading it again once")
                zip.delete()
                download(pins, zip)
            }
        }
        // download() verifies while streaming; this re-reads what is on disk.
        val final = sha256(zip)
        if (final != pins.sha256) throw GradleException("$zip: does not match runtime-pins.json\n  expected ${pins.sha256}\n  got      $final")
        return zip
    }

    /** Writes [path] (relative to [root]) from the verified [zip]: a file of the archive, or a target's archive from its slice under [root]. */
    protected fun materialize(pins: OnnxRuntimePins, zip: File, root: File, path: String) {
        val out = File(root, path)
        out.parentFile.mkdirs()
        val temp = File(out.parentFile, ".${out.name}.tmp")
        temp.delete()
        val target = pins.targets.values.firstOrNull { it.path == path }
        if (target == null) {
            ZipFile(zip).use { archive ->
                val entry = archive.getEntry(path) ?: throw GradleException("$zip has no entry $path")
                archive.getInputStream(entry).use { input -> temp.outputStream().use { input.copyTo(it, 1 shl 20) } }
            }
        } else {
            val slice = File(root, target.slice)
            if (target.thin == null) {
                slice.copyTo(temp, overwrite = true)
            } else {
                exec.exec { commandLine("xcrun", "lipo", slice.absolutePath, "-thin", target.thin, "-output", temp.absolutePath) }
            }
        }
        Files.move(temp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** The paths under [root] whose digest differs from the pin (or that are missing). */
    protected fun mismatches(root: File, expected: Map<String, String>): List<String> =
        expected.filter { (path, sha) -> sha256(File(root, path)) != sha }.keys.toList()

    protected fun mismatchReport(root: File, expected: Map<String, String>, paths: List<String>) = paths.joinToString("\n") { path ->
        "${File(root, path)}: does not match runtime-pins.json\n  expected ${expected[path]}\n  got      ${sha256(File(root, path)) ?: "(missing)"}"
    }

    protected fun lipoVersion(): String {
        val out = java.io.ByteArrayOutputStream()
        val lipo = java.io.ByteArrayOutputStream()
        exec.exec { commandLine("xcrun", "--find", "lipo"); standardOutput = lipo }
        exec.exec { commandLine("what", lipo.toString().trim()); standardOutput = out; isIgnoreExitValue = true }
        return out.toString().lines().drop(1).joinToString(" ") { it.trim() }.trim()
    }
}

/** Downloads, extracts and thins the runtime into [runtimeDir]. Cacheable: its outputs are verified by verifyOnnxRuntimeIos anyway. */
@CacheableTask
abstract class PrepareOnnxRuntimeIos : OnnxRuntimeIosTask() {
    @get:OutputDirectory
    abstract val runtimeDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val pins = pins()
        val expected = expected(pins)
        val out = runtimeDir.get().asFile
        val lipo = lipoVersion()
        withCacheLock(pins) {
            val zip = verifiedZip(pins)
            val temp = File(out.parentFile, ".${out.name}.tmp-${ProcessHandle.current().pid()}")
            temp.deleteRecursively()
            temp.mkdirs()
            try {
                expected.keys.forEach { materialize(pins, zip, temp, it) }
                val bad = mismatches(temp, expected)
                if (bad.isNotEmpty()) {
                    // Fresh from a verified ZIP, so the pins themselves disagree with the archive.
                    throw GradleException("onnxruntime: files extracted from the verified ZIP do not match runtime-pins.json:\n" +
                        mismatchReport(temp, expected, bad).replace(temp.path, out.path))
                }
                File(temp, "provenance.json").writeText(
                    groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(mapOf(
                        "runtime" to "onnxruntime-c", "version" to pins.version, "url" to pins.url, "sha256" to pins.sha256,
                        "files" to expected.toSortedMap(), "lipo" to lipo, "unzip" to "java.util.zip (JDK ${System.getProperty("java.version")})"
                    ))) + "\n"
                )
                out.deleteRecursively()
                Files.move(temp.toPath(), out.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temp.deleteRecursively()
            }
            logger.lifecycle("onnxruntime: prepared ${expected.size} files in $out (lipo: $lipo)")
        }
    }
}

/** Checks the runtime against the pins on every build; recreates a mismatching file once, then fails naming it. */
abstract class VerifyOnnxRuntimeIos : OnnxRuntimeIosTask() {
    /** Produced by prepareOnnxRuntimeIos; internal because this task never takes part in up-to-date checks. */
    @get:Internal
    abstract val runtimeDir: DirectoryProperty

    @TaskAction
    fun verify() {
        val pins = pins()
        val expected = expected(pins)
        val root = runtimeDir.get().asFile
        withCacheLock(pins) {
            val zip = verifiedZip(pins)
            val bad = mismatches(root, expected)
            if (bad.isNotEmpty()) {
                logger.warn("onnxruntime: recreating from the verified ZIP once:\n" + mismatchReport(root, expected, bad))
                // Sources first: a target archive is made from its (re-verified) slice.
                val sources = bad.filter { it in pins.files }
                sources.forEach { File(root, it).delete(); materialize(pins, zip, root, it) }
                val targets = (bad - sources.toSet()) +
                    selected(pins).filter { it.slice in sources && it.path !in bad }.map { it.path }
                targets.forEach { File(root, it).delete(); materialize(pins, zip, root, it) }
                val still = mismatches(root, expected)
                if (still.isNotEmpty()) throw GradleException("onnxruntime: mismatch after recreating from the verified ZIP:\n" + mismatchReport(root, expected, still))
            }
            logger.lifecycle("onnxruntime: verified ${pins.zipName} and ${expected.size} files in $root")
        }
    }
}

val onnxRuntimePins = layout.projectDirectory.file("scripts/runtime-pins.json")
val onnxRuntimeCache = providers.environmentVariable("KVID_RUNTIME_CACHE").filter { it.isNotEmpty() }
    .orElse(providers.systemProperty("user.home").map { "$it/.cache/kvid/runtime" })
val onnxRuntimeDir = layout.buildDirectory.dir("onnxruntime/ios")
val onnxRuntimeTargets = listOf("iosArm64", "iosSimulatorArm64")

val prepareOnnxRuntimeIos = tasks.register<PrepareOnnxRuntimeIos>("prepareOnnxRuntimeIos") {
    group = "onnxruntime"
    description = "Downloads (cached), extracts and thins the pinned ONNX Runtime iOS archive."
    pinsFile.set(onnxRuntimePins)
    cacheRoot.set(onnxRuntimeCache)
    targetNames.set(onnxRuntimeTargets)
    runtimeDir.set(onnxRuntimeDir)
    onlyIf("macOS host") { System.getProperty("os.name").startsWith("Mac") }
}

tasks.register<VerifyOnnxRuntimeIos>("verifyOnnxRuntimeIos") {
    group = "onnxruntime"
    description = "Checks the ONNX Runtime iOS archive and every file derived from it against runtime-pins.json."
    dependsOn(prepareOnnxRuntimeIos)
    pinsFile.set(onnxRuntimePins)
    cacheRoot.set(onnxRuntimeCache)
    targetNames.set(onnxRuntimeTargets)
    runtimeDir.set(onnxRuntimeDir)
    doNotTrackState("integrity of the runtime is checked on every build")
    onlyIf("macOS host") { System.getProperty("os.name").startsWith("Mac") }
}
