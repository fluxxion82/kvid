#!/usr/bin/env bash
# Compile an isolated consumer against the staged publication (JVM variant), without project dependencies.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
GROUP=$(awk -F= '$1 == "group" { print $2 }' "$ROOT/gradle.properties")
VERSION=$(awk -F= '$1 == "version" { print $2 }' "$ROOT/gradle.properties")
KOTLIN=$(sed -n 's/^kotlin = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")
mkdir -p "$WORK/src/main/kotlin"
cat > "$WORK/settings.gradle.kts" <<'GRADLE'
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
    plugins { kotlin("jvm") version providers.gradleProperty("kotlinVersion").get() }
}
rootProject.name = "kvid-publication-consumer"
GRADLE
cat > "$WORK/build.gradle.kts" <<'GRADLE'
plugins { kotlin("jvm") }
repositories {
    maven { url = uri(providers.gradleProperty("kvidRepository").get()) }
    google()
    mavenCentral()
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("${providers.gradleProperty("kvidGroup").get()}:kvid-core:${providers.gradleProperty("kvidVersion").get()}")
}
GRADLE
cat > "$WORK/src/main/kotlin/Consumer.kt" <<'KOTLIN'
import com.kvid.store.Document
import com.kvid.store.PutOptions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun options() = PutOptions(title = "Consumer", metadata = JsonObject(mapOf("source" to JsonPrimitive("smoke"))))
fun metadata(document: Document): JsonObject? = document.version.metadata
KOTLIN
"$ROOT/gradlew" --project-dir "$WORK" \
    -PkvidRepository="$ROOT/kvid-core/build/staging-repo" \
    -PkvidGroup="$GROUP" -PkvidVersion="$VERSION" -PkotlinVersion="$KOTLIN" \
    compileKotlin --no-daemon --console=plain
