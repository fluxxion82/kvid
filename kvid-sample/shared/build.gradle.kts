import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// The kvid notes sample: a Compose Multiplatform UI and model shared by the desktop (JVM), Android and
// iOS apps. The Android app lives in :kvid-sample:androidApp and the iOS app in kvid-sample/iosApp.
kotlin {
    jvmToolchain(17)

    jvm("desktop")

    android {
        namespace = "com.kvid.sample.shared"
        compileSdk = libs.versions.sampleCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        // No host tests: the bundled SQLite natives do not load on the host JVM. The model tests run on
        // the desktop JVM and the iOS simulator.
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "KvidSample"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The app modules construct a StoreSession and call NotesApp, so the store and the Compose
            // runtime are part of this module's API.
            api(project(":kvid-core"))
            api(libs.compose.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.kvid.sample.MainKt"
    }
}

tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
