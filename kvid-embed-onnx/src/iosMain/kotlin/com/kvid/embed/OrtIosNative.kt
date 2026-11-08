package com.kvid.embed

import cnames.structs.OrtStatus
import com.kvid.embed.onnxruntime.OrtApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.invoke
import kotlinx.cinterop.toKString
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * The kinds of native object kvid creates through the ONNX Runtime C API (implementation plan, R5). The
 * process-wide `OrtEnv` (once-only state, never released), the borrowed default allocator and the
 * borrowed tensor view of `CastTypeInfoToTensorInfo` are not counted: kvid never releases them.
 */
internal enum class NativeKind(val release: String) {
    SESSION_OPTIONS("ReleaseSessionOptions"),
    SESSION("ReleaseSession"),
    MEMORY_INFO("ReleaseMemoryInfo"),
    TYPE_INFO("ReleaseTypeInfo"),
    TENSOR_TYPE_AND_SHAPE_INFO("ReleaseTensorTypeAndShapeInfo"),
    /** Input and output names returned by the default allocator; freed with its `AllocatorFree`. */
    ALLOCATOR_STRING("AllocatorFree"),
    VALUE("ReleaseValue"),
    /** Error statuses, released after their message was copied. */
    STATUS("ReleaseStatus"),
    /** The backend's own `nativeHeap` copies of the names and the pointer arrays `Run` reads. */
    HEAP("nativeHeap.free")
}

/** Per-kind counts of live native objects in this process; failure-path tests check they return to their baseline. */
internal object NativeObjects {
    private val counts = Array(NativeKind.entries.size) { AtomicInt(0) }

    fun created(kind: NativeKind) {
        counts[kind.ordinal].incrementAndFetch()
    }

    fun released(kind: NativeKind) {
        counts[kind.ordinal].decrementAndFetch()
    }

    fun live(kind: NativeKind): Int = counts[kind.ordinal].load()

    fun snapshot(): Map<NativeKind, Int> = NativeKind.entries.associateWith { live(it) }
}

/**
 * The native objects one operation created, released in reverse order of creation, each exactly once,
 * and only what was actually created: a caller registers each object right after the call that returned
 * it. Not thread-safe; one ledger belongs to one thread at a time.
 */
internal class NativeLedger {
    private class Entry(val kind: NativeKind, val release: () -> Unit)

    private val entries = ArrayList<Entry>()

    fun <T : Any> add(kind: NativeKind, value: T, release: (T) -> Unit): T {
        entries += Entry(kind) { release(value) }
        NativeObjects.created(kind)
        return value
    }

    /** Moves every entry into a new ledger, which then owns them (an open handing its objects to the backend). */
    fun transfer(): NativeLedger = NativeLedger().also { it.entries += entries; entries.clear() }

    /** Releases everything in reverse order; every release runs even if an earlier one throws, and the first error is rethrown. */
    fun releaseAll() {
        var first: Throwable? = null
        for (entry in entries.asReversed()) {
            try {
                entry.release()
            } catch (e: Throwable) {
                if (first == null) first = e
            } finally {
                NativeObjects.released(entry.kind)
            }
        }
        entries.clear()
        first?.let { throw it }
    }

    /** [releaseAll] on an error path: a release failure must not hide [cause]. */
    fun releaseAllAfter(cause: Throwable) {
        try {
            releaseAll()
        } catch (e: Throwable) {
            cause.addSuppressed(e)
        }
    }
}

/**
 * The message of [status], or null for success (a null status). The message is copied to a Kotlin string
 * before the status is released, because it lives inside the status.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun OrtApi.errorOf(status: CPointer<OrtStatus>?): String? {
    if (status == null) return null
    NativeObjects.created(NativeKind.STATUS)
    try {
        return GetErrorMessage!!(status)?.toKString() ?: "(no message)"
    } finally {
        ReleaseStatus!!(status)
        NativeObjects.released(NativeKind.STATUS)
    }
}

/** Throws [IllegalStateException] naming [what] when [status] is an error. */
@OptIn(ExperimentalForeignApi::class)
internal fun OrtApi.check(status: CPointer<OrtStatus>?, what: String) {
    errorOf(status)?.let { throw IllegalStateException("ONNX Runtime $what failed: $it") }
}

/** Points in [OnnxEmbedder.open] and in each inference where the failure-path tests inject a failure. */
internal enum class OrtStep {
    /** After the session was created (options and session live). */
    SESSION,
    /** After the first input name was read (an allocator string live). */
    INPUT_INSPECTION,
    /** After the output's type information was read (a type info live). */
    OUTPUT_INSPECTION,
    /** After the CPU memory info was created (everything the backend owns live). */
    MEMORY_INFO,
    /** Inside an inference, after its input tensors were created. */
    RUN_INPUTS,
    /** Inside an inference, after the output value and its type and shape were read. */
    RUN_OUTPUT
}

/** Test seam: [hook] runs at each [OrtStep]; it may throw, or cancel the caller. Null outside tests. */
internal object OrtIosFaults {
    val hook = AtomicReference<((OrtStep) -> Unit)?>(null)

    fun reach(step: OrtStep) {
        hook.load()?.invoke(step)
    }
}
