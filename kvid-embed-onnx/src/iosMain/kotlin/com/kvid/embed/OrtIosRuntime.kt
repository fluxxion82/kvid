package com.kvid.embed

import cnames.structs.OrtEnv
import com.kvid.embed.onnxruntime.ORT_API_VERSION
import com.kvid.embed.onnxruntime.OrtApi
import com.kvid.embed.onnxruntime.OrtGetApiBase
import com.kvid.embed.onnxruntime.OrtLoggingLevel
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSLock
import platform.posix.errno
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.strerror
import kotlin.concurrent.atomics.AtomicReference

/** The environment variable ONNX Runtime's POSIX telemetry provider reads when it initializes. */
internal const val ORT_DISABLE_TELEMETRY = "ORT_DISABLE_TELEMETRY"

/**
 * Whether ONNX Runtime 1.30 treats [value] of `ORT_DISABLE_TELEMETRY` as true. A copy of
 * `IsTelemetryDisabledByEnvironment` in onnxruntime/core/platform/telemetry_environment.h at v1.30.0:
 *
 * ```
 * // True if ORT_DISABLE_TELEMETRY is set to a truthy value (1/true/yes/on/y, case-insensitive).
 * // The POSIX 1DS provider latches this full opt-out during initialization. ...
 * inline bool IsTelemetryDisabledByEnvironment() {
 *   const std::string value = telemetry_detail::ToLowerAscii(
 *       telemetry_detail::TrimAscii(telemetry_detail::GetTelemetryEnv("ORT_DISABLE_TELEMETRY")));
 *   return value == "1" || value == "true" || value == "yes" || value == "on" || value == "y";
 * }
 * ```
 *
 * `TrimAscii` strips characters for which `std::isspace` holds in the C locale (space, `\t`, `\n`, `\v`,
 * `\f`, `\r`) from both ends; `ToLowerAscii` lowercases with `std::tolower`, so only ASCII letters. An
 * unset variable reads as the empty string, which is not true.
 */
internal fun isOrtTelemetryOptOut(value: String): Boolean {
    val trimmed = value.trim { it == ' ' || it == '\t' || it == '\n' || it == '\u000B' || it == '\u000C' || it == '\r' }
    val lower = buildString(trimmed.length) { for (c in trimmed) append(if (c in 'A'..'Z') c + ('a' - 'A') else c) }
    return lower == "1" || lower == "true" || lower == "yes" || lower == "on" || lower == "y"
}

/** What [OrtIosRuntime.initialize] did with `ORT_DISABLE_TELEMETRY` (implementation plan, R2). */
internal enum class TelemetryDecision {
    /** It was unset; kvid set it to `1`. */
    SET_BY_KVID,
    /** The app had set a value ONNX Runtime treats as true; kept. */
    KEPT_APP_OPT_OUT,
    /**
     * The app had set another value (also empty), which would leave ONNX Runtime's uploader running; kvid
     * replaced it with `1`. A network probe on the simulator (October 26) showed that with such a value
     * `DisableTelemetryEvents` alone still lets ONNX Runtime upload its process-information event.
     */
    OVERRODE_APP_VALUE
}

internal fun telemetryDecision(initial: String?): TelemetryDecision = when {
    initial == null -> TelemetryDecision.SET_BY_KVID
    isOrtTelemetryOptOut(initial) -> TelemetryDecision.KEPT_APP_OPT_OUT
    else -> TelemetryDecision.OVERRODE_APP_VALUE
}

/** The variable as kvid found it ([initial], null when unset) and what it did. */
internal class TelemetryPolicy(val initial: String?, val decision: TelemetryDecision)

/**
 * The C API table, the process-wide environment and the linked runtime's version. It exists only after
 * `DisableTelemetryEvents(env)` returned success.
 */
@OptIn(ExperimentalForeignApi::class)
internal class OrtRuntime(val api: OrtApi, val env: CPointer<OrtEnv>, val version: String)

/**
 * Once-only, process-wide ONNX Runtime setup for iOS (implementation plan, R2 and R12). [initialize] runs
 * before kvid's first call into ONNX Runtime, under a lock, and publishes the result only after every
 * step succeeded:
 *
 * 1. The telemetry policy, before `OrtGetApiBase` is called: a value ONNX Runtime treats as true is kept,
 *    and anything else (unset, empty, or a false value) is replaced with `1`, checking `setenv`. Decided
 *    once per process; kvid never changes the variable afterwards. The 1.30 binary's POSIX 1DS provider
 *    latches the variable when the first environment is created.
 * 2. `GetApi(ORT_API_VERSION)` (30), which returns null when the linked runtime lacks that version, and
 *    the runtime's version string.
 * 3. One `OrtEnv` for the process, never released: ONNX Runtime supports one environment per process,
 *    and releasing it while an embedder is open would be undefined. Then `DisableTelemetryEvents(env)`,
 *    whose status is checked. If it fails, the unpublished environment is released and the next call
 *    tries again.
 */
@OptIn(ExperimentalForeignApi::class)
internal object OrtIosRuntime {
    private val lock = NSLock()
    private val ready = AtomicReference<OrtRuntime?>(null)
    private val policy = AtomicReference<TelemetryPolicy?>(null)

    /** The telemetry decision of this process, or null before the first [initialize]. */
    val telemetryPolicy: TelemetryPolicy? get() = policy.load()

    /** The initialized runtime, or null before the first successful [initialize]. */
    val initialized: OrtRuntime? get() = ready.load()

    fun initialize(): OrtRuntime {
        ready.load()?.let { return it }
        lock.lock()
        try {
            ready.load()?.let { return it }
            if (policy.load() == null) policy.store(applyTelemetryPolicy())
            val runtime = createRuntime()
            ready.store(runtime)
            return runtime
        } finally {
            lock.unlock()
        }
    }

    private fun applyTelemetryPolicy(): TelemetryPolicy {
        val initial = getenv(ORT_DISABLE_TELEMETRY)?.toKString()
        val decision = telemetryDecision(initial)
        if (decision != TelemetryDecision.KEPT_APP_OPT_OUT) {
            check(setenv(ORT_DISABLE_TELEMETRY, "1", 1) == 0) {
                "could not set $ORT_DISABLE_TELEMETRY=1 before initializing ONNX Runtime: ${strerror(errno)?.toKString()}"
            }
        }
        return TelemetryPolicy(initial, decision)
    }

    private fun createRuntime(): OrtRuntime {
        val base = OrtGetApiBase() ?: throw IllegalStateException("ONNX Runtime: OrtGetApiBase returned null")
        val version = base.pointed.GetVersionString?.invoke()?.toKString() ?: "unknown"
        val apiPointer = base.pointed.GetApi!!(ORT_API_VERSION.toUInt())
            ?: throw IllegalStateException("ONNX Runtime $version does not provide C API version $ORT_API_VERSION")
        val api = apiPointer.pointed
        return memScoped {
            val envOut = alloc<CPointerVar<OrtEnv>>()
            envOut.value = null
            api.errorOf(api.CreateEnv!!(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "kvid".cstr.ptr, envOut.ptr))?.let {
                envOut.value?.let { env -> api.ReleaseEnv!!(env) }
                throw IllegalStateException("ONNX Runtime $version could not create its environment: $it")
            }
            val env = envOut.value ?: throw IllegalStateException("ONNX Runtime $version returned no environment")
            api.errorOf(api.DisableTelemetryEvents!!(env))?.let {
                api.ReleaseEnv!!(env)
                throw IllegalStateException("ONNX Runtime $version: DisableTelemetryEvents failed: $it")
            }
            OrtRuntime(api, env, version)
        }
    }
}
