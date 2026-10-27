package com.kvid.embed

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * kvid's once-only telemetry policy on iOS (implementation plan, R2): what [OrtIosRuntime.initialize] does
 * with `ORT_DISABLE_TELEMETRY`, and that `DisableTelemetryEvents` succeeded. It claims nothing about the
 * network; that is a separate, manual probe.
 *
 * The policy is decided once per process, so each environment case needs a fresh process. The Gradle test
 * runs `iosSimulatorArm64Telemetry{Unset,Truthy,Falsy,Empty}Test` (build.gradle.kts) each start a new
 * simulator process that runs only this class, with KVID_TELEMETRY_CASE naming the case. The test then
 * checks that nothing in the process initialized ONNX Runtime yet, applies the case's value as an app
 * would before its first embedder, initializes, and checks the outcome. In the ordinary test run
 * (KVID_TELEMETRY_CASE unset) other classes may have initialized ONNX Runtime first; the test then checks
 * that the recorded decision and the variable agree with the policy for whatever value the process had.
 */
@OptIn(ExperimentalForeignApi::class)
class TelemetryPolicyTest {
    private class Case(val name: String, val value: String?, val decision: TelemetryDecision)

    private val cases = listOf(
        Case("unset", null, TelemetryDecision.SET_BY_KVID),
        Case("truthy", " TRUE ", TelemetryDecision.KEPT_APP_OPT_OUT),
        Case("falsy", "0", TelemetryDecision.OVERRODE_APP_VALUE),
        Case("empty", "", TelemetryDecision.OVERRODE_APP_VALUE)
    )

    private fun current(): String? = getenv(ORT_DISABLE_TELEMETRY)?.toKString()

    @Test fun truthyValuesFollowOnnxRuntime130() {
        for (value in listOf("1", "true", "TRUE", "True", "yes", "YES", "on", "On", "y", "Y", " 1", "1 ", "\ttrue\n", "\u000B\u000Cyes\r", "  on  ")) {
            assertTrue(isOrtTelemetryOptOut(value), "'$value' should disable telemetry")
        }
        for (value in listOf("", " ", "0", "false", "no", "off", "n", "2", "11", "yes please", "tru", "t", "enable", "1.0", "\u00A01", "ｙ")) {
            assertFalse(isOrtTelemetryOptOut(value), "'$value' should not disable telemetry")
        }
    }

    @Test fun decisionFollowsTheInitialValue() {
        for (case in cases) assertEquals(case.decision, telemetryDecision(case.value), "case ${case.name}")
        for (value in listOf("false", "no", "off", " 0 ", "2")) assertEquals(TelemetryDecision.OVERRODE_APP_VALUE, telemetryDecision(value), "'$value'")
    }

    @Test fun thisProcessFollowsThePolicy() {
        val caseName = testEnvironment("KVID_TELEMETRY_CASE")
        val case = caseName?.let { name -> cases.firstOrNull { it.name == name } ?: fail("unknown KVID_TELEMETRY_CASE '$name'") }
        if (case != null) {
            assertNull(OrtIosRuntime.telemetryPolicy, "ONNX Runtime was set up before this case ran; the case needs a fresh process")
            if (case.value == null) assertEquals(0, unsetenv(ORT_DISABLE_TELEMETRY))
            else assertEquals(0, setenv(ORT_DISABLE_TELEMETRY, case.value, 1))
            assertEquals(case.value, current())
        }
        val runtime = OrtIosRuntime.initialize()
        // Published only after DisableTelemetryEvents returned success.
        assertNotNull(OrtIosRuntime.initialized)
        val policy = assertNotNull(OrtIosRuntime.telemetryPolicy)
        val final = current()
        assertEquals(telemetryDecision(policy.initial), policy.decision)
        when (policy.decision) {
            TelemetryDecision.SET_BY_KVID, TelemetryDecision.OVERRODE_APP_VALUE -> assertEquals("1", final)
            TelemetryDecision.KEPT_APP_OPT_OUT -> assertEquals(policy.initial, final, "kvid changed the app's opt-out")
        }
        if (case != null) {
            assertEquals(case.value, policy.initial)
            assertEquals(case.decision, policy.decision)
        }
        assertEquals(runtime, OrtIosRuntime.initialize(), "a second initialize must return the published runtime")
        assertEquals(final, current(), "a second initialize must not change the variable")
        println(
            "[kvid-telemetry] case=${case?.name ?: "shared-process"} initial=${quote(policy.initial)} decision=${policy.decision} " +
                "final=${quote(final)} disable_telemetry_events=ok ort=${runtime.version}"
        )
    }

    private fun quote(value: String?) = if (value == null) "unset" else "'" + value.replace("\n", "\\n").replace("\t", "\\t") + "'"
}
