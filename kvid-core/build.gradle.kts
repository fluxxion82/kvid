import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    jvmToolchain(17)

    jvm()

    android {
        namespace = "com.kvid.core"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()   // 23: required by androidx.sqlite 2.7 (ADR 0001)

        // Host-side (JVM) unit tests: runs commonTest on the host.
        withHostTestBuilder {}

        // Device-side (instrumented) tests: kvid-core/src/androidDeviceTest.
        withDeviceTestBuilder { sourceSetTreeName = "test" }
    }

    // iosX64 (Intel simulator) is not published by androidx.sqlite 2.7.1 (ADR 0001).
    // Swift consumers use the KvidCore XCFramework: ./gradlew :kvid-core:assembleKvidCoreReleaseXCFramework
    val xcFramework = XCFramework("KvidCore")
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "KvidCore"
            binaryOption("bundleId", "com.kvid.core")
            isStatic = true
            xcFramework.add(this)
        }
    }

    // Debug Kotlin/Native test binaries are unoptimized, so their timings overstate app costs. This
    // adds an optimized test binary and the task iosSimulatorArm64ReleaseTest, used for measurements.
    iosSimulatorArm64 {
        binaries.test(listOf(NativeBuildType.RELEASE))
        testRuns.create("release") {
            setExecutionSourceFrom(binaries.getTest(NativeBuildType.RELEASE))
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
            api(libs.androidx.sqlite)
            implementation(libs.androidx.sqlite.bundled)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmMain.dependencies {
            implementation(libs.google.zxing.core)
            implementation(libs.google.zxing.javase)
        }

        androidMain.dependencies {
            implementation(libs.google.zxing.core)
        }

        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.ext.junit)
        }
    }
}

// Print failing tests with their messages and causes to the console so CI logs are diagnosable
// without downloading the HTML/XML reports. Applies to JVM, Android host and Kotlin/Native test tasks.
// Standard output is shown too so the `[kvid-measure]` lines reach the CI log.
tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED, TestLogEvent.STANDARD_OUT)
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

// Keep rendering tests enabled by default. The CI simulator cannot currently render
// Core Image QR images; opt out explicitly rather than ignoring tests on every host.
val skipIosQrRendering = providers.environmentVariable("KVID_SKIP_IOS_QR_RENDERING_TESTS")
    .map { it == "true" }.orElse(false)
tasks.withType<KotlinNativeTest>().configureEach {
    if (name.startsWith("ios") && skipIosQrRendering.get()) {
        val renderingTests = listOf(
            "testBasicQRCodeGeneration",
            "testQRCodeWithDifferentErrorCorrection",
            "testQRCodeLargeData",
            "testPixelDataGrayscale",
            "testQRCodeSquare"
        )
        renderingTests.forEach {
            filter.excludeTestsMatching("com.kvid.core.IosQRCodeGeneratorTest.$it")
        }
        doFirst {
            logger.warn(
                "Excluding five iOS QR rendering tests: KVID_SKIP_IOS_QR_RENDERING_TESTS=true; " +
                    "Core Image createCGImage returns nil on the tested simulators. " +
                    "Unset the variable to run them (docs/ROADMAP.md Appendix A)."
            )
        }
    }
}

// Publication: every Kotlin Multiplatform target (JVM, Android, iOS arm64 and simulator arm64) plus the
// root module metadata. This stages unsigned artifacts into build/staging-repo. A Central release
// additionally needs a verified namespace and signing configuration before bundle upload.
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(javadocJar)
        pom {
            name.set("kvid-core")
            description.set("Embedded, searchable document store for Kotlin Multiplatform: one portable SQLite file with versioned documents and offline full-text search.")
            url.set("https://github.com/fluxxion82/kvid")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
            developers {
                developer {
                    id.set("fluxxion82")
                    name.set("fluxxion82")
                }
            }
            scm {
                url.set("https://github.com/fluxxion82/kvid")
                connection.set("scm:git:https://github.com/fluxxion82/kvid.git")
            }
        }
    }
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-repo"))
        }
    }
}
