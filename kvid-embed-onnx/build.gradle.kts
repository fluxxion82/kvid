import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Optional embedding support for kvid-core (ADR 0002). Apps that use only lexical search do not depend
// on this module. It starts with the BERT WordPiece tokenizer; the ONNX Runtime embedder follows.
kotlin {
    jvmToolchain(17)

    jvm()

    android {
        namespace = "com.kvid.embed"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        // Tokenizer parity runs on the JVM and the iOS simulator; Android device parity comes with the
        // embedder, which needs model files pushed to the device anyway.
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// The Hugging Face reference fixture (scripts/make_tokenizer_fixture.py) is passed by directory. CI sets
// KVID_REQUIRE_TOKENIZER_FIXTURE=true so a missing fixture fails instead of skipping the parity test.
val fixtureEnvironment = listOf("KVID_TOKENIZER_FIXTURE_DIR", "KVID_REQUIRE_TOKENIZER_FIXTURE")
    .mapNotNull { name -> providers.environmentVariable(name).orNull?.let { name to it } }
val fixtureDir = providers.environmentVariable("KVID_TOKENIZER_FIXTURE_DIR").orNull
tasks.withType<AbstractTestTask>().configureEach {
    // The fixture is an input, so a cached or up-to-date result never stands in for a parity run.
    inputs.property("tokenizerFixtureRequired", providers.environmentVariable("KVID_REQUIRE_TOKENIZER_FIXTURE").orElse("false"))
    if (fixtureDir != null && file(fixtureDir).isDirectory) {
        inputs.dir(fixtureDir).withPropertyName("tokenizerFixture").withPathSensitivity(PathSensitivity.RELATIVE)
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

tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED, TestLogEvent.STANDARD_OUT)
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
