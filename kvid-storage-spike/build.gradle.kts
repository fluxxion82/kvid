import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// Throwaway verification module for ADR 0001 (docs/adr/0001-storage-engine.md).
// It exists to answer, on every target, whether one SQLite build with FTS5 gives
// the primitives the persistence contract needs. Milestone 2 replaces it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    jvmToolchain(17)

    jvm()

    android {
        namespace = "com.kvid.spike"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        // androidx.sqlite 2.7 requires API 23; kvid-core is still at 21 (ADR 0001, consequences).
        minSdk = 23
        // No host or device test builders: the bundled driver's Android artifact targets device ABIs,
        // and whether it also ships host natives is one of the facts this spike is meant to establish
        // (ADR 0001, verification). Android compiles; JVM and iOS run the tests.
    }

    // androidx.sqlite 2.7.1 publishes iosArm64 and iosSimulatorArm64 only (no iosX64 / Intel simulator
    // variant), so the spike drops iosX64. kvid-core still declares it; see ADR 0001 consequences.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.androidx.sqlite)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        showStandardStreams = true   // the spike reports versions, compile options and timings via stdout
    }
}
