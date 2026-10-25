import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The framework a Swift app adds to use kvid with on-device embeddings (implementation plan, R13): one
// static XCFramework, KvidEmbed, that contains ONNX Runtime 1.30.0 (from kvid-embed-onnx's cinterop klib),
// kvid-core with its SQLite, and one Kotlin runtime, and exports the kvid-core and kvid-embed-onnx APIs.
// An app links it with "Do Not Embed" INSTEAD of KvidCore, never beside it (two Kotlin runtimes, two
// SQLite copies). No CocoaPods and no separate onnxruntime.xcframework. Unpublished: CI and
// scripts/check-local.sh (part ios-framework) build it and link a Swift consumer against it
// (swift-check/check.sh).
//
//   ./gradlew :kvid-embed-onnx-ios-framework:assembleKvidEmbedReleaseXCFramework
//   -> build/XCFrameworks/release/KvidEmbed.xcframework and, next to it, onnxruntime-LICENSE
//
// ONNX Runtime's minimum is iOS 15.1, above Kotlin/Native's default of 15.0, so both slices are built for
// 15.1 (swift-check/check.sh asserts it with vtool). kvid-core's own KvidCore XCFramework is unchanged
// and contains no ONNX Runtime.
kotlin {
    val xcFramework = XCFramework("KvidEmbed")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework(listOf(NativeBuildType.RELEASE)) {
            baseName = "KvidEmbed"
            binaryOption("bundleId", "com.sterlingalbury.kvid.embed")
            isStatic = true
            export(project(":kvid-core"))
            export(project(":kvid-embed-onnx"))
            xcFramework.add(this)
        }
        target.binaries.configureEach {
            freeCompilerArgs += "-Xoverride-konan-properties=osVersionMin.ios_arm64=15.1;osVersionMin.ios_simulator_arm64=15.1"
        }
    }

    sourceSets {
        iosMain.dependencies {
            api(project(":kvid-core"))
            api(project(":kvid-embed-onnx"))
        }
    }
}

// ONNX Runtime's licence travels with the framework that contains it (R7): the LICENSE file of the pinned,
// verified archive, which kvid-embed-onnx's verifyOnnxRuntimeIos checks on every build.
val onnxRuntimeLicense = rootProject.layout.projectDirectory.file("kvid-embed-onnx/build/onnxruntime/ios/LICENSE")
val copyOnnxRuntimeLicense by tasks.registering(Copy::class) {
    dependsOn(":kvid-embed-onnx:verifyOnnxRuntimeIos")
    from(onnxRuntimeLicense)
    rename { "onnxruntime-LICENSE" }
    into(layout.buildDirectory.dir("XCFrameworks/release"))
}
tasks.matching { it.name == "assembleKvidEmbedReleaseXCFramework" }.configureEach { finalizedBy(copyOnnxRuntimeLicense) }
