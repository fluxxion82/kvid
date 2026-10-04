plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Android notes app: hosts the shared NotesApp UI from :kvid-sample:shared.
android {
    namespace = "com.kvid.sample.android"
    compileSdk = libs.versions.sampleCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.kvid.sample"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":kvid-sample:shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
}
