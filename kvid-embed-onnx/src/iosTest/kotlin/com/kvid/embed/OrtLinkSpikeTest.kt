package com.kvid.embed

import cnames.structs.OrtEnv
import cnames.structs.OrtSession
import cnames.structs.OrtSessionOptions
import cnames.structs.OrtStatus
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
import platform.posix.size_tVar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Throwaway link spike (implementation plan, Task 2): the cinterop links ONNX Runtime into the simulator
 * test binary, the C API answers, and a session opens over the real model. Replaced by the backend's tests.
 */
@OptIn(ExperimentalForeignApi::class)
class OrtLinkSpikeTest {
    @Test
    fun apiEnvironmentAndSession() {
        val base = assertNotNull(OrtGetApiBase(), "OrtGetApiBase returned null")
        val api = assertNotNull(base.pointed.GetApi!!(ORT_API_VERSION.toUInt()), "GetApi($ORT_API_VERSION) returned null")
        val version = assertNotNull(base.pointed.GetVersionString!!(), "GetVersionString returned null").toKString()
        println("[kvid-ort-spike] api=$ORT_API_VERSION version=$version")
        assertEquals("1.30.0", version)
        val ort = api.pointed

        memScoped {
            val envOut = alloc<CPointerVar<OrtEnv>>()
            ort.check(ort.CreateEnv!!(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "kvid-spike".cstr.ptr, envOut.ptr))
            val env = assertNotNull(envOut.value)
            try {
                ort.check(ort.DisableTelemetryEvents!!(env))
                println("[kvid-ort-spike] env created, telemetry events disabled")

                val dir = testEnvironment("KVID_EMBEDDING_FIXTURE_DIR")
                if (dir == null) {
                    println("[kvid-ort-spike] KVID_EMBEDDING_FIXTURE_DIR is not set: no session")
                    return
                }
                val optionsOut = alloc<CPointerVar<OrtSessionOptions>>()
                ort.check(ort.CreateSessionOptions!!(optionsOut.ptr))
                val options = assertNotNull(optionsOut.value)
                try {
                    val sessionOut = alloc<CPointerVar<OrtSession>>()
                    ort.check(ort.CreateSession!!(env, "$dir/model.onnx".cstr.ptr, options, sessionOut.ptr))
                    val session = assertNotNull(sessionOut.value)
                    try {
                        val inputs = alloc<size_tVar>()
                        val outputs = alloc<size_tVar>()
                        ort.check(ort.SessionGetInputCount!!(session, inputs.ptr))
                        ort.check(ort.SessionGetOutputCount!!(session, outputs.ptr))
                        println("[kvid-ort-spike] session over $dir/model.onnx: inputs=${inputs.value} outputs=${outputs.value}")
                        assertEquals(3uL, inputs.value)
                    } finally {
                        ort.ReleaseSession!!(session)
                    }
                } finally {
                    ort.ReleaseSessionOptions!!(options)
                }
            } finally {
                ort.ReleaseEnv!!(env)
            }
        }
        println("[kvid-ort-spike] released session, options and env")
    }

    private fun OrtApi.check(status: CPointer<OrtStatus>?) {
        if (status == null) return
        val message = GetErrorMessage!!(status)?.toKString()
        ReleaseStatus!!(status)
        fail("ONNX Runtime: $message")
    }
}
