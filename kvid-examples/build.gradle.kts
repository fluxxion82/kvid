@file:Suppress("DSL_SCOPE_VIOLATION")

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":kvid-core"))
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("com.kvid.examples.BasicExampleKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.kvid.examples.BasicExampleKt"
    }
}
