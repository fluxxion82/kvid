package com.kvid.embed

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The module's manifest removes onnxruntime-android's TelemetryInitializer (owner decision). The test
 * APK merges the library manifest the way an app does, so the provider must be absent from it.
 */
class TelemetryProviderTest {
    @Test fun onnxRuntimeTelemetryProviderIsRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().context
        @Suppress("DEPRECATION")
        val providers = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS).providers
        val names = providers?.map { it.name }.orEmpty()
        println("[kvid] providers of ${context.packageName}: $names")
        assertFalse("ai.onnxruntime.TelemetryInitializer" in names, "the telemetry provider is still registered: $names")
    }
}
