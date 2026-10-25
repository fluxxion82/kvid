#!/usr/bin/env bash
# Consumer check of the KvidEmbed XCFramework (implementation plan, Task 8, R7, R8 and R13). Run by
# scripts/check-local.sh's ios-framework part, locally and in CI's iOS job.
#
# Usage: check.sh XCFRAMEWORK FIXTURE_DIR PYTHON [OUT_DIR]
#   XCFRAMEWORK  build/XCFrameworks/release/KvidEmbed.xcframework of this module
#   FIXTURE_DIR  an embedding fixture (scripts/make_embedding_fixture.py) with model.onnx and vocab.txt
#   PYTHON       a Python with kvid-embed-onnx/scripts/requirements.txt installed (check-local's venv)
#   OUT_DIR      work directory, replaced (default: kvid-embed-onnx-ios-framework/build/swift-check)
#
# Steps, each a hard failure:
#  1. Copies the XCFramework and main.swift into an empty consumer directory. The links below pass only
#     `-F <that copy>`: no runtime cache, no ONNX Runtime headers or archives, no Gradle build directory.
#     The copy must contain no ONNX Runtime header.
#  2. Links main.swift for the simulator (arm64-apple-ios15.1-simulator) and the device
#     (arm64-apple-ios15.1, linked only: not run, no signing identity) with `-framework KvidEmbed` and no
#     other flags; any linker warning fails (a newer object would only warn).
#  3. vtool: both binaries have minos 15.1; otool: no member of either framework archive is newer.
#  4. The link maps (OUT_DIR/consumer/*.map): _OrtGetApiBase comes from KvidEmbed.framework/KvidEmbed,
#     and no linked object comes from outside the consumer directory except Xcode's SDKs and toolchain.
#  5. Runs the simulator binary with `xcrun simctl spawn` (KVID_SIMULATOR names a device, else a booted
#     iPhone, else the newest available iPhone, booted here and shut down afterwards) on "hello world".
#  6. Compares its first three values with Python ONNX Runtime on the same model files (expected.py,
#     computed now, not hard-coded), within 1e-5 absolute, and its dimensions with 384.
# Prints [kvid-swift-check] lines; the last one is "[kvid-swift-check] PASSED ...".
set -euo pipefail

[ $# -ge 3 ] && [ $# -le 4 ] || { sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 64; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
XCF="$(cd "$1" && pwd)"
FIXTURE="$(cd "$2" && pwd)"
PY="$(cd "$(dirname "$3")" && pwd)/$(basename "$3")"  # absolute, symlink kept (a venv)
OUT="${4:-$HERE/../build/swift-check}"
TEXT="hello world"
TOLERANCE=1e-5
MINOS=15.1

fail() { echo "[kvid-swift-check] FAILED: $*"; exit 1; }
[ "$(basename "$XCF")" = KvidEmbed.xcframework ] || fail "$XCF is not KvidEmbed.xcframework"
for f in model.onnx vocab.txt; do [ -f "$FIXTURE/$f" ] || fail "no $f in $FIXTURE"; done
[ -x "$PY" ] || fail "no Python at $3"

rm -rf "$OUT"
mkdir -p "$OUT/consumer"
OUT="$(cd "$OUT" && pwd)"
CONSUMER="$OUT/consumer"
cp -R "$XCF" "$CONSUMER/"
cp "$HERE/main.swift" "$CONSUMER/"
headers="$(find "$CONSUMER" -iname 'onnxruntime*.h' -o -iname 'libonnxruntime*' | head -5)"
[ -z "$headers" ] || fail "the framework copy contains ONNX Runtime headers or archives: $headers"
cd "$CONSUMER"

# 1-2. Link.
link() {  # slice sdk target binary
  local slice="$1" sdk="$2" target="$3" binary="$4"
  echo "[kvid-swift-check] linking $binary: xcrun --sdk $sdk swiftc -target $target -F KvidEmbed.xcframework/$slice -framework KvidEmbed"
  xcrun --sdk "$sdk" swiftc -target "$target" -F "KvidEmbed.xcframework/$slice" -framework KvidEmbed main.swift -o "$binary" \
    -Xlinker -map -Xlinker "$binary.map" > "$binary.link.log" 2>&1 || { cat "$binary.link.log"; fail "linking $binary"; }
  if grep -iE 'warning|error' "$binary.link.log"; then fail "the $binary link printed warnings (above)"; fi
}
link ios-arm64-simulator iphonesimulator "arm64-apple-ios$MINOS-simulator" kvid-swift-check-sim
link ios-arm64 iphoneos "arm64-apple-ios$MINOS" kvid-swift-check-device

# 3. Deployment target.
minos_of() { vtool -show-build "$1" | awk '$1 == "minos" { print $2 }'; }
for binary in kvid-swift-check-sim kvid-swift-check-device; do
  m="$(minos_of "$binary")"
  platform="$(vtool -show-build "$binary" | awk '$1 == "platform" { print $2 }')"
  echo "[kvid-swift-check] $binary: platform $platform minos $m"
  [ "$m" = "$MINOS" ] || fail "$binary has minos $m, expected $MINOS"
done
for slice in ios-arm64 ios-arm64-simulator; do
  members="$(otool -l "KvidEmbed.xcframework/$slice/KvidEmbed.framework/KvidEmbed" | awk '$1 == "minos" { print $2 }' | sort -u | tr '\n' ' ')"
  echo "[kvid-swift-check] $slice framework archive members: minos $members"
  for m in $members; do
    [ "$(printf '%s\n%s\n' "$m" "$MINOS" | sort -V | tail -1)" = "$MINOS" ] || fail "$slice has an object with minos $m, newer than $MINOS"
  done
done

# 4. Link maps.
for binary in kvid-swift-check-sim kvid-swift-check-device; do
  "$PY" -I - "$binary.map" "$CONSUMER" <<'PY' || fail "link map of $binary"
import re, sys
path, consumer = sys.argv[1], sys.argv[2]
objects, symbol_file, counts, section = {}, None, {}, None
for line in open(path, encoding="utf-8", errors="replace"):
    line = line.rstrip("\n")
    if line.startswith("# Object files:"):
        section = "objects"; continue
    if line.startswith("# Symbols:"):
        section = "symbols"; continue
    if line.startswith("# Dead Stripped Symbols:"):
        section = "dead"; continue
    if line.startswith("#"):
        if section == "objects":
            section = None
        continue
    if section == "objects":
        m = re.match(r"\[\s*(\d+)\] (.*)$", line)
        if m:
            objects[int(m.group(1))] = m.group(2)
    elif section == "symbols":
        m = re.match(r"0x[0-9A-Fa-f]+\t0x[0-9A-Fa-f]+\t\[\s*(\d+)\] (.*)$", line)
        if m:
            index = int(m.group(1))
            counts[index] = counts.get(index, 0) + 1
            if m.group(2) == "_OrtGetApiBase":
                symbol_file = objects.get(index)
allowed = (consumer + "/", "/Applications/Xcode", "/Library/Developer/", "/usr/lib/", "/System/", "linker synthesized", "/var/folders/", "/private/var/folders/")
outside = [o for o in objects.values() if not o.startswith(allowed) and not o.startswith("KvidEmbed.xcframework/")]
if outside:
    sys.exit(f"objects from outside the consumer directory: {outside}")
leaked = [o for o in objects.values() if ("onnxruntime" in o.lower() or ".cache/kvid" in o) and "KvidEmbed.framework/KvidEmbed(" not in o]
if leaked:
    sys.exit(f"ONNX Runtime objects not from KvidEmbed: {leaked}")
if symbol_file is None:
    sys.exit("_OrtGetApiBase is not in the link map")
if "KvidEmbed.framework/KvidEmbed(" not in symbol_file:
    sys.exit(f"_OrtGetApiBase comes from {symbol_file}, not from KvidEmbed")
framework_symbols = sum(n for i, n in counts.items() if "KvidEmbed.framework/KvidEmbed(" in objects.get(i, ""))
name = path.rsplit("/", 1)[-1]
print(f"[kvid-swift-check] {name}: _OrtGetApiBase from {symbol_file.split('/consumer/')[-1]}; {framework_symbols} symbols from KvidEmbed objects")
PY
done

# 5. Run on a simulator.
UDID="${KVID_SIMULATOR:-}"
BOOTED_HERE=""
cleanup() { [ -z "$BOOTED_HERE" ] || xcrun simctl shutdown "$BOOTED_HERE" > /dev/null 2>&1 || true; }
trap cleanup EXIT
if [ -z "$UDID" ]; then
  UDID="$(xcrun simctl list devices available -j | "$PY" -I -c '
import json, re, sys
devices = json.load(sys.stdin)["devices"]
def version(runtime):
    m = re.search(r"iOS-(\d+)-(\d+)", runtime)
    return (int(m.group(1)), int(m.group(2))) if m else None
iphones = [(version(r), d) for r, ds in devices.items() if version(r) for d in ds if d["name"].startswith("iPhone")]
booted = [d for v, d in iphones if d["state"] == "Booted"]
newest = sorted(iphones, key=lambda vd: vd[0], reverse=True)
pick = booted[0] if booted else (newest[0][1] if newest else None)
print(pick["udid"] if pick else "")')"
  [ -n "$UDID" ] || fail "no available iPhone simulator"
fi
state="$(xcrun simctl list devices -j | "$PY" -I -c 'import json, sys
for ds in json.load(sys.stdin)["devices"].values():
    for d in ds:
        if d["udid"] == sys.argv[1] or d["name"] == sys.argv[1]: print(d["state"]); sys.exit()' "$UDID")"
[ -n "$state" ] || fail "no simulator $UDID"
if [ "$state" != Booted ]; then
  echo "[kvid-swift-check] booting simulator $UDID"
  xcrun simctl bootstatus "$UDID" -b > "$OUT/boot.log" 2>&1 || { tail -5 "$OUT/boot.log"; fail "booting $UDID"; }
  BOOTED_HERE="$UDID"
fi
echo "[kvid-swift-check] running kvid-swift-check-sim on simulator $UDID"
set +e
xcrun simctl spawn "$UDID" "$CONSUMER/kvid-swift-check-sim" "$FIXTURE/model.onnx" "$FIXTURE/vocab.txt" "$TEXT" > "$OUT/run.log" 2>&1
code=$?
set -e
cat "$OUT/run.log"
[ "$code" -eq 0 ] || fail "kvid-swift-check-sim exited with $code"
actual="$(grep -F '[kvid-swift-check] model=' "$OUT/run.log" | tail -1)"
[ -n "$actual" ] || fail "kvid-swift-check-sim printed no result line"

# 6. Compare with Python ONNX Runtime.
expected="$("$PY" -I "$HERE/expected.py" "$FIXTURE/model.onnx" "$FIXTURE/vocab.txt" "$TEXT")"
echo "[kvid-swift-check] expected (Python ONNX Runtime, expected.py): $expected"
"$PY" -I - "$actual" "$expected" "$TOLERANCE" <<'PY' || fail "the Swift consumer's vector differs from Python ONNX Runtime"
import sys
actual, expected, tolerance = sys.argv[1], sys.argv[2], float(sys.argv[3])
def fields(line):
    return dict(part.split("=", 1) for part in line.split() if "=" in part)
a, e = fields(actual), fields(expected)
if a["dims"] != e["dims"] or a["dims"] != "384":
    sys.exit(f"dimensions: Swift {a['dims']}, Python {e['dims']}, expected 384")
av = [float(v) for v in a["first"].split(",")][:3]
ev = [float(v) for v in e["first"].split(",")][:3]
diff = max(abs(x - y) for x, y in zip(av, ev))
print(f"[kvid-swift-check] first 3: swift={av} python={ev} max_abs_diff={diff:.3e} tolerance={tolerance:g}")
if diff > tolerance:
    sys.exit(f"max |diff| {diff} > {tolerance}")
PY
echo "[kvid-swift-check] PASSED: static KvidEmbed linked for simulator and device (minos $MINOS), _OrtGetApiBase from KvidEmbed, simulator run matches Python ONNX Runtime"
