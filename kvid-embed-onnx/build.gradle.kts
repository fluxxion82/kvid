import java.time.Duration
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// prepareOnnxRuntimeIos and verifyOnnxRuntimeIos: the pinned ONNX Runtime iOS archive (scripts/runtime-pins.json).
apply(from = "onnxruntime-ios.gradle.kts")

// Optional embedding support for kvid-core (ADR 0002). Apps that use only lexical search do not depend
// on this module. Common code holds the BERT WordPiece tokenizer and the embedding pipeline; the JVM and
// Android run it with ONNX Runtime's Java API, iOS with its C API through cinterop (OnnxEmbedder, an expect
// class with one actual in src/jvmAndAndroidMain and one in iosMain).
val telemetryCases = listOf("unset", "truthy", "falsy", "empty")
fun telemetryTask(case: String) = "iosSimulatorArm64Telemetry${case.replaceFirstChar { it.uppercase() }}Test"

kotlin {
    jvmToolchain(17)

    // kotlin.concurrent.atomics (BackendHooks, the iOS backend and the tests) is still marked experimental.
    compilerOptions {
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        // OnnxEmbedder is an expect class (Beta in Kotlin 2.5), with actuals for the JVM/Android and iOS.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm()

    android {
        namespace = "com.kvid.embed"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        // 24, not kvid's 23: onnxruntime-android 1.30.0 declares minSdkVersion 24 (owner decision).
        minSdk = libs.versions.androidEmbedMinSdk.get().toInt()

        // Device-side (instrumented) tests run commonTest (and androidDeviceTest) on an emulator or device;
        // fixtures are pushed to the device and passed as instrumentation arguments (see the CI jobs).
        withDeviceTestBuilder { sourceSetTreeName = "test" }

        // onnxruntime-android ships about 134 MB of native libraries for four ABIs, which the device-test
        // APK packages in full. -Pkvid.deviceTestAbi=arm64-v8a (or x86_64) keeps only the device's ABI, so
        // the APK fits on small emulator disks. The library's AAR has no native libraries of its own and
        // is unaffected; apps choose their ABIs as usual.
        providers.gradleProperty("kvid.deviceTestAbi").orNull?.let { abi ->
            packaging {
                jniLibs.excludes += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").filter { it != abi }.map { "lib/$it/**" }
            }
        }
    }

    // ONNX Runtime for iOS through the C API (src/nativeInterop/cinterop/onnxruntime.def), linked from the
    // pinned, verified archive that onnxruntime-ios.gradle.kts prepares. ORT's minimum is iOS 15.1, above
    // Kotlin/Native's default of 15.0, so every binary of this module is linked for 15.1; the linker would
    // otherwise warn that ORT's objects were built for a newer iOS than the binary.
    val onnxRuntimeDir = layout.buildDirectory.dir("onnxruntime/ios")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("onnxruntime") {
            includeDirs(onnxRuntimeDir.map { it.dir("Headers") })
            extraOpts("-libraryPath", onnxRuntimeDir.get().dir(target.name).asFile.absolutePath)
        }
        target.binaries.configureEach {
            freeCompilerArgs += "-Xoverride-konan-properties=osVersionMin.ios_arm64=15.1;osVersionMin.ios_simulator_arm64=15.1"
        }
    }

    // TelemetryPolicyTest checks the once-only telemetry policy (implementation plan, R2), which needs a
    // fresh process per environment case: one test run per case, each its own simulator process that runs
    // only that class with KVID_TELEMETRY_CASE set. Tasks iosSimulatorArm64Telemetry{Unset,Truthy,Falsy,Empty}Test.
    // Debug Kotlin/Native test binaries are unoptimized, so their timings overstate app costs (Milestone 3:
    // 10 to 40 times). This adds an optimized test binary and iosSimulatorArm64ReleaseTest for measurements.
    iosSimulatorArm64 {
        binaries.test(listOf(NativeBuildType.RELEASE))
        testRuns.create("release") {
            setExecutionSourceFrom(binaries.getTest(NativeBuildType.RELEASE))
        }
        telemetryCases.forEach { case ->
            testRuns.create("telemetry" + case.replaceFirstChar { it.uppercase() }) {
                setExecutionSourceFrom(binaries.getTest(NativeBuildType.DEBUG))
                filter { includeTestsMatching("com.kvid.embed.TelemetryPolicyTest") }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kvid-core"))
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
        }

        // The JVM jar and the Android AAR expose the same ai.onnxruntime Java API, so the actual OnnxEmbedder
        // is one source directory compiled into both targets (each sees exactly one actual). IntelliJ reports
        // duplicate content roots and assigns the directory to one module; compilation is unaffected. The
        // tests are in commonTest and run on every target.
        jvmMain {
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies { implementation(libs.onnxruntime.jvm) }
        }
        androidMain {
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies { implementation(libs.onnxruntime.android) }
        }
        getByName("androidDeviceTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.ext.junit)
            }
        }
    }
}

// Reference fixtures are passed by directory: KVID_TOKENIZER_FIXTURE_DIR (scripts/make_tokenizer_fixture.py)
// and KVID_EMBEDDING_FIXTURE_DIR (scripts/make_embedding_fixture.py, with model.onnx and vocab.txt). CI sets
// KVID_REQUIRE_*_FIXTURE=true so a missing fixture fails instead of skipping the parity tests, and
// KVID_EMBEDDING_MEASURE=true to print the embedding measurements; KVID_EMBEDDING_TUNE names one
// configuration for OnnxEmbedderTuningTest. On Android devices all of them are
// instrumentation arguments:
// -Pandroid.testInstrumentationRunnerArguments.KVID_TOKENIZER_FIXTURE_DIR=/data/local/tmp/...
val fixtureVariables = listOf(
    "KVID_TOKENIZER_FIXTURE_DIR",
    "KVID_REQUIRE_TOKENIZER_FIXTURE",
    "KVID_EMBEDDING_FIXTURE_DIR",
    "KVID_REQUIRE_EMBEDDING_FIXTURE",
    "KVID_EMBEDDING_MEASURE",
    "KVID_EMBEDDING_TUNE"
)
val fixtureEnvironment = fixtureVariables.mapNotNull { name -> providers.environmentVariable(name).orNull?.let { name to it } }
val fixtureDirs = listOf("tokenizerFixture" to "KVID_TOKENIZER_FIXTURE_DIR", "embeddingFixture" to "KVID_EMBEDDING_FIXTURE_DIR")
    .mapNotNull { (property, name) -> providers.environmentVariable(name).orNull?.let { property to it } }
// With a fixture required or measurements requested, the tests must run and print: Gradle's build cache
// and up-to-date checks would otherwise replay an earlier result without any console output.
val mustExecute = listOf("KVID_REQUIRE_TOKENIZER_FIXTURE", "KVID_REQUIRE_EMBEDDING_FIXTURE", "KVID_EMBEDDING_MEASURE")
    .any { providers.environmentVariable(it).orNull == "true" } || providers.environmentVariable("KVID_EMBEDDING_TUNE").isPresent
tasks.withType<AbstractTestTask>().configureEach {
    fixtureVariables.filter { !it.endsWith("_DIR") }.forEach { name ->
        inputs.property(name, providers.environmentVariable(name).orElse("false"))
    }
    fixtureDirs.forEach { (property, dir) ->
        if (file(dir).isDirectory) inputs.dir(dir).withPropertyName(property).withPathSensitivity(PathSensitivity.RELATIVE)
    }
    if (mustExecute) {
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("fixture parity and measurements must execute") { true }
    }
}
tasks.withType<Test>().configureEach {
    fixtureEnvironment.forEach { (name, value) -> environment(name, value) }
}
tasks.withType<KotlinNativeTest>().configureEach {
    // Simulator processes only receive variables with the SIMCTL_CHILD_ prefix.
    fixtureEnvironment.forEach { (name, value) ->
        environment(name, value)
        environment("SIMCTL_CHILD_$name", value)
    }
}

telemetryCases.forEach { case ->
    tasks.withType<KotlinNativeTest>().matching { it.name == telemetryTask(case) }.configureEach {
        environment("KVID_TELEMETRY_CASE", case)
        environment("SIMCTL_CHILD_KVID_TELEMETRY_CASE", case)
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("each telemetry case must run in a fresh process") { true }
    }
}

tasks.withType<AbstractTestTask>().configureEach {
    // runTest's timeout cannot stop a native wait or inference that is stuck; this bounds the whole task.
    timeout.set(Duration.ofMinutes(90))
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED, TestLogEvent.STANDARD_OUT)
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

// Every iOS cinterop and link task uses the runtime only after verifyOnnxRuntimeIos has checked it against
// the pins (it runs on every build). macOS hosts only: elsewhere the Apple tasks are skipped, and JVM and
// Android builds never have these tasks in their graph, so they never download the runtime.
if (System.getProperty("os.name").startsWith("Mac")) {
    tasks.withType<CInteropProcess>().configureEach { dependsOn("verifyOnnxRuntimeIos") }
    tasks.withType<KotlinNativeLink>().configureEach { dependsOn("verifyOnnxRuntimeIos") }
}
