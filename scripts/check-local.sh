#!/usr/bin/env bash
# Local pre-push check that mirrors .github/workflows/ci.yml. Run it before every push.
#
# Usage: scripts/check-local.sh [--skip-ios] [--skip-android] [--with-sample] [--only PART[,PART]...]
#
# Parts, in order (each runs in its own `bash -euo pipefail` process; its output goes to
# build/check-local/PART.log and to the console):
#   python          virtual environment build/check-local/venv with kvid-embed-onnx/scripts/requirements.txt
#   fixtures        model download and digest check (kvid-embed-onnx/scripts/download_model.py) into
#                   ${KVID_MODEL_CACHE:-$HOME/.cache/kvid/models}, then the tokenizer fixture and the
#                   embedding fixture, written to build/check-local/{tokenizer,embedding}-fixture
#   jvm             ./gradlew build (iOS test tasks and the KvidEmbed framework excluded) and
#                   :kvid-examples:compileKotlin with the fixtures required; then the JUnit XML of
#                   :kvid-embed-onnx:jvmTest must show the parity classes passed, not skipped. It also writes
#                   the portable store build/check-local/portable-store (OnnxEmbedderPortabilityTest) that
#                   the ios part reopens on the simulator
#   portable-store  (only with --only; for CI's iOS job, which has no jvm part) the JVM test that writes
#                   the portable store, alone, with the jvm part's gate for it
#   ios             (macOS with xcrun simctl) the iosSimulatorArm64 tasks CI runs; kvid-embed-onnx's with
#                   the fixtures required and the measurement on, then scripts/verify_device_tests.py on
#                   its fresh simulator XML and a check for the parity and measurement lines. It reopens
#                   the portable store written on the JVM (run the jvm or portable-store part first)
#   ios-framework   (macOS with xcrun simctl) the KvidEmbed XCFramework (kvid-embed-onnx-ios-framework, static,
#                   ONNX Runtime inside) and a Swift consumer linked against a copy of it for the simulator and
#                   the device, run on a simulator and compared with Python ONNX Runtime
#                   (kvid-embed-onnx-ios-framework/swift-check/check.sh); KvidCore's XCFramework must contain no
#                   ONNX Runtime symbols
#   android         (one adb device) core and embed device tests with the fixtures pushed, the
#                   measurement run, and scripts/verify_device_tests.py on the JUnit XML
#   sample-ios      (--with-sample; macOS with xcodegen) build and smoke-test the iOS sample app
#   sample-android  (--with-sample; one adb device) installDebug and smoke-test the Android sample app
# --only takes part names (or `sample` for both sample parts), repeatable or comma separated. Parts
# that need an earlier part are skipped when that part failed in the same run.
#
# Result: every selected part is RAN, SKIPPED (with the reason) or FAILED. Exit code 0 when every
# part ran and passed, 2 when nothing failed but something was skipped (PARTIAL; never a full pass),
# 1 when anything failed, 64 for a usage error. A run with --only reports only the selected parts.
#
# Android device: ANDROID_SERIAL if set, else the only `adb devices` entry in state `device`. With
# several devices and no ANDROID_SERIAL, or none, the android parts are SKIPPED.
#
# Embedding tests: the embedding fixture requirements (KVID_EMBEDDING_FIXTURE_DIR,
# KVID_REQUIRE_EMBEDDING_FIXTURE, KVID_EMBEDDING_MEASURE; the required classes, listed per part; the
# measurement runs) switch on when kvid-embed-onnx/src/commonTest/kotlin/com/kvid/embed/OnnxEmbedderParityTest.kt
# exists. The fixture itself is always built. Required classes: jvm OnnxEmbedderParityTest,
# OnnxEmbedderTest, OnnxEmbedderStoreTest, OnnxEmbedderMeasurementTest, OnnxEmbedderPortabilityTest (its
# [kvid-portable] wrote line); android the first three and TelemetryProviderTest (Android only), then
# OnnxEmbedderMeasurementTest in its own run; ios the jvm list (OnnxEmbedderPortabilityTest with its
# [kvid-portable] reopened line for the JVM's store) plus TelemetryPolicyTest (iOS only), then
# TelemetryPolicyTest once per environment case in its own simulator process, and OnnxEmbedderTuningTest
# with KVID_EMBEDDING_TUNE=corpus=mixed+batch=4+sort=on.
# BertTokenizerParityTest is required everywhere. Gradle runs one invocation at a time; extra Gradle arguments
# come from KVID_GRADLE_ARGS (default --stacktrace), the Python for the venv from KVID_PYTHON.
set -uo pipefail

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
ROOT="$(cd "$(dirname "$SELF")/.." && pwd)"
cd "$ROOT" || exit 1
OUT="$ROOT/build/check-local"
SCRIPTS="$ROOT/kvid-embed-onnx/scripts"
VENV="$OUT/venv"
PY="$VENV/bin/python"
TOKENIZER_FIXTURE="$OUT/tokenizer-fixture"
EMBEDDING_FIXTURE="$OUT/embedding-fixture"
# A store indexed on the JVM and reopened on the iOS simulator (OnnxEmbedderPortabilityTest; plan, R12).
PORTABLE_STORE="$OUT/portable-store"
MODEL_CACHE="${KVID_MODEL_CACHE:-$HOME/.cache/kvid/models}"
DEVICE_TOKENIZER_DIR=/data/local/tmp/kvid-tokenizer-fixture
DEVICE_EMBEDDING_DIR=/data/local/tmp/kvid-embedding-fixture
GRADLE_ARGS="${KVID_GRADLE_ARGS---stacktrace}"
PARTS="python fixtures jvm portable-store ios ios-framework android sample-ios sample-android"
PROPS=-Pandroid.testInstrumentationRunnerArguments

# The Core Image simulator defect is quarantined the same way in CI.
export KVID_SKIP_IOS_QR_RENDERING_TESTS=true

# A stable file of the embedding test suite (the tests run from commonTest on every platform).
EMBEDDING_TESTS_MARKER=kvid-embed-onnx/src/commonTest/kotlin/com/kvid/embed/OnnxEmbedderParityTest.kt
have_embedding_tests() { [ -f "$ROOT/$EMBEDDING_TESTS_MARKER" ]; }

# kvid-embed-onnx's TelemetryPolicyTest cases, one fresh simulator process (Gradle test run) each.
TELEMETRY_CASES="unset truthy falsy empty"
telemetry_task() { echo "iosSimulatorArm64Telemetry$(echo "${1:0:1}" | tr '[:lower:]' '[:upper:]')${1:1}Test"; }
# The tuning configuration the ios part runs and gates (implementation plan, R9).
IOS_TUNE_CONFIG=corpus=mixed+batch=4+sort=on

# ----------------------------------------------------------------------------------------------
# Parts (run in child processes with -e)
# ----------------------------------------------------------------------------------------------

skip() { echo "$*" > "$OUT/$PART.skip"; echo "SKIPPED: $*"; exit 3; }

gradle() {
  # shellcheck disable=SC2086
  ./gradlew "$@" $GRADLE_ARGS
}

require_fixtures() {
  [ -f "$TOKENIZER_FIXTURE/cases.jsonl" ] || { echo "no tokenizer fixture in $TOKENIZER_FIXTURE; run the fixtures part first"; exit 1; }
  if have_embedding_tests; then
    [ -f "$EMBEDDING_FIXTURE/embeddings.jsonl" ] || { echo "no embedding fixture in $EMBEDDING_FIXTURE; run the fixtures part first"; exit 1; }
  fi
}

model_dir() {
  "$PY" -I -c 'import json, os, sys
pins = json.load(open(sys.argv[1]))
print(os.path.join(sys.argv[2], pins["model"].split("/")[-1].lower() + "-" + pins["revision"]))' "$SCRIPTS/model-pins.json" "$MODEL_CACHE"
}

pinned() { "$PY" -I -c 'import json, sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$SCRIPTS/model-pins.json" "$1"; }

sha256_of() { "$PY" -I -c 'import hashlib, sys
h = hashlib.sha256()
with open(sys.argv[1], "rb") as f:
    for block in iter(lambda: f.read(1 << 20), b""):
        h.update(block)
print(h.hexdigest())' "$1"; }

part_python() {
  local python_bin="${KVID_PYTHON:-python3}"
  command -v "$python_bin" > /dev/null || { echo "$python_bin not found; set KVID_PYTHON"; exit 1; }
  [ -x "$PY" ] || "$python_bin" -m venv "$VENV"
  "$PY" --version
  "$PY" -m pip install --quiet --disable-pip-version-check -r "$SCRIPTS/requirements.txt"
  "$PY" -I -c 'import numpy, onnxruntime, tokenizers; print("numpy", numpy.__version__, "onnxruntime", onnxruntime.__version__, "tokenizers", tokenizers.__version__)'
}

part_fixtures() {
  [ -x "$PY" ] || { echo "no virtual environment at $VENV; run the python part first"; exit 1; }
  local dir
  dir="$(model_dir)"
  echo "model directory: $dir"
  "$PY" -I "$SCRIPTS/download_model.py" "$dir"
  rm -rf "$TOKENIZER_FIXTURE" "$EMBEDDING_FIXTURE"
  "$PY" -I "$SCRIPTS/make_tokenizer_fixture.py" "$TOKENIZER_FIXTURE" --tokenizer-json "$dir/tokenizer.json" --vocab "$dir/vocab.txt" \
    --revision "$(pinned revision)"
  "$PY" -I "$SCRIPTS/make_embedding_fixture.py" "$EMBEDDING_FIXTURE" --model-dir "$dir"
}

part_jvm() {
  require_fixtures
  export KVID_TOKENIZER_FIXTURE_DIR="$TOKENIZER_FIXTURE" KVID_REQUIRE_TOKENIZER_FIXTURE=true
  local required=(--require kvid-embed-onnx:com.kvid.embed.BertTokenizerParityTest)
  if have_embedding_tests; then
    export KVID_EMBEDDING_FIXTURE_DIR="$EMBEDDING_FIXTURE" KVID_REQUIRE_EMBEDDING_FIXTURE=true
    export KVID_EMBEDDING_MEASURE=true  # measurements are part of a full run, as in CI
    export KVID_PORTABLE_STORE_OUT="$PORTABLE_STORE"
    rm -rf "$PORTABLE_STORE"
    required+=(--require kvid-embed-onnx:com.kvid.embed.OnnxEmbedderParityTest --require kvid-embed-onnx:com.kvid.embed.OnnxEmbedderTest
               --require kvid-embed-onnx:com.kvid.embed.OnnxEmbedderStoreTest --require kvid-embed-onnx:com.kvid.embed.OnnxEmbedderMeasurementTest
               --require kvid-embed-onnx:com.kvid.embed.OnnxEmbedderPortabilityTest)
  fi
  # On macOS `build` also runs the iOS simulator tests (check depends on allTests); the ios part runs them.
  local excludes=()
  if [ "$(uname)" = Darwin ]; then
    # The KvidEmbed framework links are the ios-framework part's.
    excludes=(-x :kvid-core:iosSimulatorArm64Test -x :kvid-core:iosSimulatorArm64ReleaseTest -x :kvid-embed-onnx-ios-framework:assemble
              -x :kvid-embed-onnx-ios-framework:iosSimulatorArm64Test
              -x :kvid-embed-onnx:iosSimulatorArm64Test -x :kvid-embed-onnx:iosSimulatorArm64ReleaseTest
              -x :kvid-sample:shared:iosSimulatorArm64Test)
    for tcase in $TELEMETRY_CASES; do excludes+=(-x ":kvid-embed-onnx:$(telemetry_task "$tcase")"); done
  fi
  local results=kvid-embed-onnx/build/test-results/jvmTest
  rm -rf "$results"  # results must come from this run (executed, or restored from the build cache)
  gradle build ${excludes[@]+"${excludes[@]}"}
  gradle :kvid-examples:compileKotlin
  echo "---- [kvid-*] lines from the JUnit output (copied, not used as the gate) ----"
  grep -ho '\[kvid-[a-z-]*\][^<]*' "$results"/*.xml || echo "none"
  echo "---- JUnit gate ----"
  "$PY" -I scripts/verify_device_tests.py --glob '{module}/build/test-results/jvmTest/*.xml' --module kvid-embed-onnx "${required[@]}"
  grep -qF '[kvid-tokenizer] cases=' "$results"/TEST-com.kvid.embed.BertTokenizerParityTest.xml \
    || { echo "BertTokenizerParityTest passed without printing its parity line (skipped?)"; exit 1; }
  if have_embedding_tests; then
    grep -qF '[kvid-embedding] cases=' "$results"/TEST-com.kvid.embed.OnnxEmbedderParityTest.xml \
      || { echo "OnnxEmbedderParityTest passed without printing its parity line (skipped?)"; exit 1; }
    grep -qF '[kvid-embedding-measure]' "$results"/TEST-com.kvid.embed.OnnxEmbedderMeasurementTest.xml \
      || { echo "OnnxEmbedderMeasurementTest passed without printing its measurement line (skipped?)"; exit 1; }
    check_portable_store_written "$results/TEST-com.kvid.embed.OnnxEmbedderPortabilityTest.xml"
  fi
}

# The writer half of OnnxEmbedderPortabilityTest ran in this run: its line, and the store and its
# expected results, both newer than the run's start (the directory was removed before it).
check_portable_store_written() {
  grep -o '\[kvid-portable\] wrote [^<]*' "$1" \
    || { echo "OnnxEmbedderPortabilityTest passed without writing the portable store (skipped?)"; exit 1; }
  [ -f "$PORTABLE_STORE/notes.kvid" ] && [ -f "$PORTABLE_STORE/expected.json" ] \
    || { echo "no notes.kvid and expected.json in $PORTABLE_STORE"; exit 1; }
  echo "portable store: $(ls -l "$PORTABLE_STORE" | awk 'NR > 1 { print $NF, $5 " bytes" }' | tr '\n' ' ')"
}

part_portable_store() {
  require_fixtures
  have_embedding_tests || skip "no embedding tests ($EMBEDDING_TESTS_MARKER)"
  local results=kvid-embed-onnx/build/test-results/jvmTest since
  rm -rf "$results" "$PORTABLE_STORE"
  since="$(date +%s)"
  KVID_EMBEDDING_FIXTURE_DIR="$EMBEDDING_FIXTURE" KVID_REQUIRE_EMBEDDING_FIXTURE=true KVID_PORTABLE_STORE_OUT="$PORTABLE_STORE" \
    gradle :kvid-embed-onnx:jvmTest --tests com.kvid.embed.OnnxEmbedderPortabilityTest
  "$PY" -I scripts/verify_device_tests.py --glob '{module}/build/test-results/jvmTest/*.xml' --module kvid-embed-onnx --since "$since" \
    --require kvid-embed-onnx:com.kvid.embed.OnnxEmbedderPortabilityTest
  check_portable_store_written "$results/TEST-com.kvid.embed.OnnxEmbedderPortabilityTest.xml"
}

part_ios() {
  [ "$(uname)" = Darwin ] || skip "not macOS"
  command -v xcrun > /dev/null && xcrun simctl help > /dev/null 2>&1 || skip "xcrun simctl is not available"
  require_fixtures
  gradle :kvid-core:iosSimulatorArm64Test
  gradle :kvid-core:iosSimulatorArm64ReleaseTest --tests com.kvid.store.SearchMeasurementTest

  # Kotlin/Native result classes are named iosSimulatorArm64Test.<package>.<Class>, so the requirements
  # name them in full (a com.kvid.embed.<Class> requirement would match nothing).
  local results=kvid-embed-onnx/build/test-results/iosSimulatorArm64Test
  local class=iosSimulatorArm64Test.com.kvid.embed
  local required=(--require "kvid-embed-onnx:$class.BertTokenizerParityTest")
  local since
  if have_embedding_tests; then
    [ -f "$PORTABLE_STORE/notes.kvid" ] && [ -f "$PORTABLE_STORE/expected.json" ] \
      || { echo "no portable store in $PORTABLE_STORE; run the jvm part (or --only portable-store) first"; exit 1; }
    grep -q '"writer": *"jvm/' "$PORTABLE_STORE/expected.json" \
      || { echo "$PORTABLE_STORE/expected.json was not written on the JVM"; exit 1; }
  fi
  since="$(date +%s)"
  (
    export KVID_TOKENIZER_FIXTURE_DIR="$TOKENIZER_FIXTURE" KVID_REQUIRE_TOKENIZER_FIXTURE=true
    if have_embedding_tests; then
      export KVID_EMBEDDING_FIXTURE_DIR="$EMBEDDING_FIXTURE" KVID_REQUIRE_EMBEDDING_FIXTURE=true KVID_EMBEDDING_MEASURE=true
      export KVID_PORTABLE_STORE_IN="$PORTABLE_STORE" KVID_REQUIRE_PORTABLE_STORE=true
    fi
    rm -rf "$results"
    gradle :kvid-embed-onnx:iosSimulatorArm64Test
  )
  if have_embedding_tests; then
    required+=(--require "kvid-embed-onnx:$class.OnnxEmbedderParityTest" --require "kvid-embed-onnx:$class.OnnxEmbedderTest"
               --require "kvid-embed-onnx:$class.OnnxEmbedderStoreTest" --require "kvid-embed-onnx:$class.OnnxEmbedderMeasurementTest"
               --require "kvid-embed-onnx:$class.TelemetryPolicyTest" --require "kvid-embed-onnx:$class.OnnxEmbedderPortabilityTest")
  fi
  echo "---- [kvid-*] lines from the simulator XML (copied, not used as the gate) ----"
  grep -ho '\[kvid-[a-z-]*\][^<]*' "$results"/*.xml || echo "none"
  echo "---- simulator XML gate ----"
  # The results directory was removed before the run, and --since rejects files older than the run.
  "$PY" -I scripts/verify_device_tests.py --module kvid-embed-onnx --glob '{module}/build/test-results/iosSimulatorArm64Test/*.xml' \
    --since "$since" "${required[@]}"
  grep -qF '[kvid-tokenizer] cases=' "$results/TEST-$class.BertTokenizerParityTest.xml" \
    || { echo "BertTokenizerParityTest passed without printing its parity line (skipped?)"; exit 1; }
  if have_embedding_tests; then
    grep -qF '[kvid-embedding] cases=' "$results/TEST-$class.OnnxEmbedderParityTest.xml" \
      || { echo "OnnxEmbedderParityTest passed without printing its parity line (skipped?)"; exit 1; }
    grep -qF '[kvid-embedding-measure]' "$results/TEST-$class.OnnxEmbedderMeasurementTest.xml" \
      || { echo "OnnxEmbedderMeasurementTest passed without printing its measurement line (skipped?)"; exit 1; }
    grep -o '\[kvid-portable\] reopened writer=jvm/[^<]*' "$results/TEST-$class.OnnxEmbedderPortabilityTest.xml" \
      || { echo "OnnxEmbedderPortabilityTest passed without reopening the JVM's portable store"; exit 1; }

    # The telemetry policy per environment case, each in a fresh simulator process (its own Gradle test
    # run and result directory), gated on its own fresh XML and its [kvid-telemetry] line.
    local tasks=() tcase task
    for tcase in $TELEMETRY_CASES; do tasks+=(":kvid-embed-onnx:$(telemetry_task "$tcase")"); done
    since="$(date +%s)"
    for tcase in $TELEMETRY_CASES; do rm -rf "kvid-embed-onnx/build/test-results/$(telemetry_task "$tcase")"; done
    gradle "${tasks[@]}"
    for tcase in $TELEMETRY_CASES; do
      task="$(telemetry_task "$tcase")"
      "$PY" -I scripts/verify_device_tests.py --module kvid-embed-onnx --glob "{module}/build/test-results/$task/*.xml" --since "$since" \
        --require "kvid-embed-onnx:$task.com.kvid.embed.TelemetryPolicyTest"
      grep -F "[kvid-telemetry] case=$tcase " "kvid-embed-onnx/build/test-results/$task/TEST-$task.com.kvid.embed.TelemetryPolicyTest.xml" \
        || { echo "TelemetryPolicyTest ($tcase) passed without printing its line for that case"; exit 1; }
    done

    # One tuning configuration, explicitly. The test task replaces its result directory, which is why
    # the main run was verified first.
    since="$(date +%s)"
    rm -rf "$results"
    (
      export KVID_EMBEDDING_FIXTURE_DIR="$EMBEDDING_FIXTURE" KVID_REQUIRE_EMBEDDING_FIXTURE=true KVID_EMBEDDING_TUNE="$IOS_TUNE_CONFIG"
      gradle :kvid-embed-onnx:iosSimulatorArm64Test --tests com.kvid.embed.OnnxEmbedderTuningTest
    )
    "$PY" -I scripts/verify_device_tests.py --module kvid-embed-onnx --glob '{module}/build/test-results/iosSimulatorArm64Test/*.xml' \
      --since "$since" --require "kvid-embed-onnx:$class.OnnxEmbedderTuningTest"
    grep -o '\[kvid-embedding-tune\][^<]*' "$results/TEST-$class.OnnxEmbedderTuningTest.xml" \
      || { echo "OnnxEmbedderTuningTest passed without printing its [kvid-embedding-tune] line"; exit 1; }

    # The measurement again with the optimized test binary, gated on its own fresh results: a debug
    # measurement never satisfies this gate (implementation plan, R9).
    local release=kvid-embed-onnx/build/test-results/iosSimulatorArm64ReleaseTest
    local release_class=iosSimulatorArm64ReleaseTest.com.kvid.embed
    since="$(date +%s)"
    rm -rf "$release"
    (
      export KVID_EMBEDDING_FIXTURE_DIR="$EMBEDDING_FIXTURE" KVID_REQUIRE_EMBEDDING_FIXTURE=true KVID_EMBEDDING_MEASURE=true
      gradle :kvid-embed-onnx:iosSimulatorArm64ReleaseTest --tests com.kvid.embed.OnnxEmbedderMeasurementTest
    )
    "$PY" -I scripts/verify_device_tests.py --module kvid-embed-onnx --glob '{module}/build/test-results/iosSimulatorArm64ReleaseTest/*.xml' \
      --since "$since" --require "kvid-embed-onnx:$release_class.OnnxEmbedderMeasurementTest"
    grep -o '\[kvid-embedding-measure\][^<]*' "$release/TEST-$release_class.OnnxEmbedderMeasurementTest.xml" \
      || { echo "the optimized OnnxEmbedderMeasurementTest passed without printing its measurement line"; exit 1; }
  fi

  gradle :kvid-sample:shared:iosSimulatorArm64Test
}

part_ios_framework() {
  [ "$(uname)" = Darwin ] || skip "not macOS"
  command -v xcrun > /dev/null && xcrun simctl help > /dev/null 2>&1 || skip "xcrun simctl is not available"
  xcrun --sdk iphonesimulator --find swiftc > /dev/null 2>&1 || skip "no swiftc for the iOS simulator SDK"
  [ -x "$PY" ] || { echo "no virtual environment at $VENV; run the python part first"; exit 1; }
  [ -f "$EMBEDDING_FIXTURE/model.onnx" ] || { echo "no embedding fixture in $EMBEDDING_FIXTURE; run the fixtures part first"; exit 1; }
  local release=kvid-embed-onnx-ios-framework/build/XCFrameworks/release
  local core=kvid-core/build/XCFrameworks/release/KvidCore.xcframework
  rm -rf "$release" "$core"  # both must come from this run
  gradle :kvid-embed-onnx-ios-framework:assembleKvidEmbedReleaseXCFramework :kvid-core:assembleKvidCoreReleaseXCFramework
  cmp "$release/onnxruntime-LICENSE" kvid-embed-onnx/build/onnxruntime/ios/LICENSE \
    || { echo "no ONNX Runtime LICENSE next to KvidEmbed.xcframework"; exit 1; }
  echo "ONNX Runtime licence: $release/onnxruntime-LICENSE"
  # kvid-core's own (lexical-only) framework stays free of ONNX Runtime (implementation plan, R7).
  local slice ort total
  for slice in "$core"/ios-*/KvidCore.framework/KvidCore; do
    total="$(nm -j "$slice" | grep -c '^_' || true)"
    ort="$(nm -j "$slice" | grep -ciE '^_Ort|onnxruntime' || true)"
    echo "$slice: $total symbols, $ort ONNX Runtime symbols"
    [ "$ort" -eq 0 ] || { echo "KvidCore contains ONNX Runtime symbols"; exit 1; }
    [ "$total" -gt 0 ] || { echo "nm found no symbols in $slice"; exit 1; }
  done
  bash kvid-embed-onnx-ios-framework/swift-check/check.sh "$release/KvidEmbed.xcframework" "$EMBEDDING_FIXTURE" "$PY"
}

# Sets ANDROID_SERIAL to the selected device, or skips the part. Also records the device in $OUT/android-device.txt.
select_device() {
  command -v adb > /dev/null || skip "adb not found"
  local ready count
  ready="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    printf '%s\n' "$ready" | grep -qxF "$ANDROID_SERIAL" || skip "ANDROID_SERIAL=$ANDROID_SERIAL is not an adb device in state 'device'"
  else
    count="$(printf '%s' "$ready" | awk 'END { print NR }')"
    [ "$count" -ge 1 ] || skip "no adb device in state 'device'"
    [ "$count" -eq 1 ] || skip "$count adb devices ($(echo $ready)); set ANDROID_SERIAL to choose one"
    export ANDROID_SERIAL="$ready"
  fi
  local model api
  model="$(adb shell getprop ro.product.model | tr -d '\r')"
  api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "device: serial=$ANDROID_SERIAL model=$model api=$api"
  echo "serial=$ANDROID_SERIAL model=$model api=$api" > "$OUT/android-device.txt"
}

# Copies a fixture directory to the device file by file. The model is not pushed again when the
# device already has a file with the same SHA-256.
push_fixture() {
  local src="$1" dest="$2" keep_model=false name
  if [ -f "$src/model.onnx" ]; then
    local local_sha device_sha
    local_sha="$(sha256_of "$src/model.onnx")"
    device_sha="$(adb shell "sha256sum $dest/model.onnx 2> /dev/null" | tr -d '\r' | awk '{ print $1 }' || true)"
    [ "$local_sha" = "$device_sha" ] && keep_model=true
  fi
  if $keep_model; then
    for f in "$src"/*; do
      name="$(basename "$f")"
      [ "$name" = model.onnx ] || adb shell rm -rf "$dest/$name"
    done
  else
    adb shell rm -rf "$dest"  # an existing target directory would make `adb push` nest the copy
  fi
  adb shell mkdir -p "$dest"
  for f in "$src"/*; do
    name="$(basename "$f")"
    if $keep_model && [ "$name" = model.onnx ]; then
      echo "$dest/model.onnx already matches, not pushed"
    else
      adb push "$f" "$dest/$name"
    fi
  done
}

part_android() {
  require_fixtures
  select_device
  local since
  since="$(date +%s)"
  rm -rf kvid-core/build/outputs/androidTest-results/connected kvid-embed-onnx/build/outputs/androidTest-results/connected

  adb logcat -G 16M
  adb logcat -c
  gradle :kvid-core:connectedAndroidDeviceTest "$PROPS.package=com.kvid.store"
  adb logcat -d > "$OUT/logcat-core.txt"
  grep -F "[kvid-measure]" "$OUT/logcat-core.txt" || echo "no [kvid-measure] line in logcat"

  push_fixture "$TOKENIZER_FIXTURE" "$DEVICE_TOKENIZER_DIR"
  # Package only the device's ABI: with all four ONNX Runtime ABIs the test APK is about 143 MB and does
  # not fit on small emulator disks.
  local abi
  abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  local args=("-Pkvid.deviceTestAbi=$abi" "$PROPS.KVID_TOKENIZER_FIXTURE_DIR=$DEVICE_TOKENIZER_DIR" "$PROPS.KVID_REQUIRE_TOKENIZER_FIXTURE=true")
  local required=(--require kvid-embed-onnx:com.kvid.embed.BertTokenizerParityTest)
  if have_embedding_tests; then
    push_fixture "$EMBEDDING_FIXTURE" "$DEVICE_EMBEDDING_DIR"
    args+=("$PROPS.KVID_EMBEDDING_FIXTURE_DIR=$DEVICE_EMBEDDING_DIR" "$PROPS.KVID_REQUIRE_EMBEDDING_FIXTURE=true")
    required+=(--require kvid-embed-onnx:com.kvid.embed.OnnxEmbedderParityTest --require kvid-embed-onnx:com.kvid.embed.OnnxEmbedderTest
               --require kvid-embed-onnx:com.kvid.embed.OnnxEmbedderStoreTest --require kvid-embed-onnx:com.kvid.embed.TelemetryProviderTest)
  fi
  adb logcat -c
  gradle :kvid-embed-onnx:connectedAndroidDeviceTest "${args[@]}"
  adb logcat -d > "$OUT/logcat-embed.txt"
  grep -E '\[kvid-(tokenizer|embedding)\]' "$OUT/logcat-embed.txt" || echo "no [kvid-tokenizer]/[kvid-embedding] line in logcat"
  grep -qF '[kvid-tokenizer] cases=' "$OUT/logcat-embed.txt" || echo "WARNING: no [kvid-tokenizer] cases= line in logcat (the JUnit XML below is the gate)"

  echo "---- JUnit gate (connected results of this run) ----"
  "$PY" -I scripts/verify_device_tests.py --since "$since" "${required[@]}"
  rm -rf "$OUT/android-results"
  mkdir -p "$OUT/android-results"
  cp -R kvid-core/build/outputs/androidTest-results/connected "$OUT/android-results/kvid-core"
  cp -R kvid-embed-onnx/build/outputs/androidTest-results/connected "$OUT/android-results/kvid-embed-onnx"
  echo "results: $(cd kvid-embed-onnx/build/outputs/androidTest-results/connected && find . -name '*.xml' | head -3 | tr '\n' ' ')" >> "$OUT/android-device.txt"

  if have_embedding_tests; then
    # A separate instrumentation run, so earlier tests do not inflate the process memory it reports.
    # The connected task replaces its results directory, which is why the verification above came first.
    local since_measure
    since_measure="$(date +%s)"
    adb logcat -c
    gradle :kvid-embed-onnx:connectedAndroidDeviceTest "$PROPS.class=com.kvid.embed.OnnxEmbedderMeasurementTest" \
      "${args[@]}" "$PROPS.KVID_EMBEDDING_MEASURE=true"
    adb logcat -d > "$OUT/logcat-measure.txt"
    grep -F '[kvid-embedding-measure]' "$OUT/logcat-measure.txt" || { echo "no [kvid-embedding-measure] line in logcat"; exit 1; }
    "$PY" -I scripts/verify_device_tests.py --module kvid-embed-onnx --since "$since_measure" \
      --require kvid-embed-onnx:com.kvid.embed.OnnxEmbedderMeasurementTest
  fi
}

part_sample_ios() {
  [ "$(uname)" = Darwin ] || skip "not macOS"
  command -v xcodegen > /dev/null || skip "xcodegen is not installed"
  command -v xcodebuild > /dev/null || skip "xcodebuild is not installed"
  (cd kvid-sample/iosApp && xcodegen generate \
    && xcodebuild -project KvidNotes.xcodeproj -target KvidNotes -configuration Debug -sdk iphonesimulator \
         ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO build)
  bash kvid-sample/iosApp/smoke-test.sh kvid-sample/iosApp/build/Debug-iphonesimulator/KvidNotes.app
}

part_sample_android() {
  select_device
  gradle :kvid-sample:androidApp:installDebug
  bash kvid-sample/androidApp/smoke-test.sh
}

if [ "${1:-}" = __part ]; then
  set -euo pipefail
  PART="$2"
  case "$PART" in
    python) part_python ;;
    fixtures) part_fixtures ;;
    jvm) part_jvm ;;
    portable-store) part_portable_store ;;
    ios) part_ios ;;
    ios-framework) part_ios_framework ;;
    android) part_android ;;
    sample-ios) part_sample_ios ;;
    sample-android) part_sample_android ;;
    *) echo "unknown part $PART"; exit 64 ;;
  esac
  exit 0
fi

# ----------------------------------------------------------------------------------------------
# Driver
# ----------------------------------------------------------------------------------------------

SKIP_IOS=false
SKIP_ANDROID=false
WITH_SAMPLE=false
ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-ios) SKIP_IOS=true ;;
    --skip-android) SKIP_ANDROID=true ;;
    --with-sample) WITH_SAMPLE=true ;;
    --only)
      [ $# -ge 2 ] || { echo "--only needs a part name"; exit 64; }
      shift
      for name in $(echo "$1" | tr ',' ' '); do
        if [ "$name" = sample ]; then name="sample-ios sample-android"; fi
        for each in $name; do ONLY="$ONLY,$each"; done
      done
      ;;
    -h | --help) sed -n '2,/^set -uo/p' "$SELF" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option $1 (see --help)"; exit 64 ;;
  esac
  shift
done
if [ -n "$ONLY" ]; then
  ONLY="$ONLY,"
  for name in $(echo "$ONLY" | tr ',' ' '); do
    case " $PARTS " in *" $name "*) ;; *) echo "unknown part '$name' (parts: $PARTS)"; exit 64 ;; esac
  done
fi

mkdir -p "$OUT"
rm -f "$OUT/android-device.txt"

names=()
statuses=()
notes=()
seconds=()

status_of() {
  local i=0
  while [ "$i" -lt "${#names[@]}" ]; do
    if [ "${names[$i]}" = "$1" ]; then echo "${statuses[$i]}"; return; fi
    i=$((i + 1))
  done
}

record() { names+=("$1"); statuses+=("$2"); notes+=("$3"); seconds+=("$4"); }

selected() {
  if [ -n "$ONLY" ]; then
    case "$ONLY" in *",$1,"*) return 0 ;; *) return 1 ;; esac
  fi
  case "$1" in
    sample-*) $WITH_SAMPLE ;;
    portable-store) return 1 ;;  # the jvm part writes the store in a full run
    *) return 0 ;;
  esac
}

dependency_of() {
  case "$1" in
    fixtures) echo python ;;
    jvm | portable-store | ios | ios-framework | android) echo fixtures ;;
  esac
}

echo "check-local: $(git rev-parse --short HEAD 2> /dev/null || echo '?') on $(uname -sm), started $(date '+%Y-%m-%d %H:%M:%S')"
if have_embedding_tests; then
  echo "embedding tests present ($EMBEDDING_TESTS_MARKER): the embedding fixture and classes are required"
else
  echo "no $EMBEDDING_TESTS_MARKER: only the tokenizer fixture and BertTokenizerParityTest are required"
fi

for part in $PARTS; do
  selected "$part" || continue
  log="$OUT/$part.log"
  case "$part" in
    ios | ios-framework | sample-ios) if $SKIP_IOS; then record "$part" SKIPPED "--skip-ios" 0; continue; fi ;;
  esac
  case "$part" in
    android | sample-android) if $SKIP_ANDROID; then record "$part" SKIPPED "--skip-android" 0; continue; fi ;;
  esac
  dep="$(dependency_of "$part")"
  if [ -n "$dep" ] && [ "$(status_of "$dep")" = FAILED ]; then
    record "$part" SKIPPED "the $dep part failed" 0
    continue
  fi
  echo
  echo "==== $part ===="
  rm -f "$OUT/$part.skip"
  started="$(date +%s)"
  "$BASH" "$SELF" __part "$part" 2>&1 | tee "$log"
  code="${PIPESTATUS[0]}"
  elapsed=$(( $(date +%s) - started ))
  case "$code" in
    0) record "$part" RAN "" "$elapsed" ;;
    3) record "$part" SKIPPED "$(cat "$OUT/$part.skip" 2> /dev/null || echo 'no reason recorded')" "$elapsed" ;;
    *) record "$part" FAILED "exit code $code" "$elapsed" ;;
  esac
done

echo
echo "==== check-local summary ===="
failed=0
skipped=0
for ((i = 0; i < ${#names[@]}; i++)); do
  line="$(printf '%-15s %-8s %4ss' "${names[$i]}" "${statuses[$i]}" "${seconds[$i]}")"
  case "${statuses[$i]}" in
    RAN) line="$line  build/check-local/${names[$i]}.log" ;;
    SKIPPED) skipped=$((skipped + 1)); line="$line  (${notes[$i]})" ;;
    FAILED) failed=$((failed + 1)); line="$line  ${notes[$i]}; build/check-local/${names[$i]}.log" ;;
  esac
  echo "$line"
done
[ -f "$OUT/android-device.txt" ] && echo "Android device: $(tr '\n' ' ' < "$OUT/android-device.txt")"
scope=""
[ -n "$ONLY" ] && scope="; --only $(echo "${ONLY#,}" | sed 's/,$//'), other parts were not run"
if [ "$failed" -gt 0 ]; then
  echo "RESULT: FAILED ($failed part(s) failed, $skipped skipped$scope)"
  exit 1
elif [ "$skipped" -gt 0 ]; then
  echo "RESULT: PARTIAL ($skipped part(s) skipped; this is not a full pass$scope)"
  exit 2
fi
echo "RESULT: PASSED${scope:+ (${scope#; })}"
exit 0
