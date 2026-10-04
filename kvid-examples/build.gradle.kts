plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":kvid-core"))
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("com.kvid.examples.BasicExampleKt")
}
