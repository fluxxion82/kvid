"""Counts JUnit XML test results for the Android device-test jobs and fails when they are not trustworthy.

Usage:
  python3 -I scripts/verify_device_tests.py [--module kvid-core --module kvid-embed-onnx]
      [--since EPOCH_SECONDS] [--require kvid-embed-onnx:com.kvid.embed.OnnxEmbedderTest ...]
      [--glob 'MODULE/build/outputs/androidTest-results/connected/**/*.xml'] [--root DIR]

The connected device-test task prints no counts or test names and passes with zero tests, so this reads
its JUnit XML. It scans only the glob (default: <module>/build/outputs/androidTest-results/connected/**/*.xml;
{module} is replaced by the module name), never other build output such as JVM results, and with
--since ignores files whose modification time is not after that Unix time (the start of the run), so
results left by an earlier run never count. For every module it prints the counts and each failure, and
it fails when a module has no results, has a failed test, or misses a requirement.

--require MODULE:CLASS (repeatable) names a test class (fully qualified, or a simple name) that must have
at least one test case in MODULE, with none failed and none skipped. The same arguments work for JVM
results, for example --glob '{module}/build/test-results/jvmTest/*.xml'.

Note: the Gradle task replaces the connected results directory on every run, so verify one run
before starting the next (scripts/check-local.sh does).
"""
import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_GLOB = "{module}/build/outputs/androidTest-results/connected/**/*.xml"


def first_line(element):
    text = (element.get("message") or element.text or "").strip().splitlines()
    return text[0][:300] if text else ""


def class_matches(wanted, actual):
    return actual == wanted or ("." not in wanted and actual.endswith("." + wanted))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--module", action="append", dest="modules", help="module directory (repeatable; default kvid-core and kvid-embed-onnx)")
    ap.add_argument("--since", type=float, help="Unix time of the run's start; older result files are ignored")
    ap.add_argument("--require", action="append", default=[], metavar="MODULE:CLASS")
    ap.add_argument("--glob", default=DEFAULT_GLOB)
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))), help="repository root (default: parent of scripts/)")
    args = ap.parse_args()
    modules = args.modules or ["kvid-core", "kvid-embed-onnx"]

    requirements = []
    for item in args.require:
        module, separator, wanted = item.partition(":")
        if not separator or not module or not wanted:
            sys.exit(f"--require expects MODULE:CLASS, got {item!r}")
        if module not in modules:
            sys.exit(f"--require {item}: {module} is not one of the scanned modules {modules}")
        requirements.append((module, wanted))

    problems = []
    cases_by_module = {}  # module -> list of (classname, name, status) where status is pass|fail|skip
    for module in modules:
        run = failed = skipped = stale = 0
        cases = cases_by_module[module] = []
        pattern = os.path.join(args.root, args.glob.format(module=module))
        for path in sorted(glob.glob(pattern, recursive=True)):
            if args.since is not None and os.path.getmtime(path) <= args.since:
                stale += 1
                continue
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError:
                continue
            found = list(root.iter("testcase"))
            if not found:
                continue
            bad = skip = 0
            device = ""
            for prop in root.iter("property"):
                if prop.get("name") in ("device", "model") and prop.get("value"):
                    device = device or prop.get("value")
            for case in found:
                problem = case.find("failure") if case.find("failure") is not None else case.find("error")
                if problem is not None:
                    bad += 1
                    status = "fail"
                    print(f"FAILED {case.get('classname')}.{case.get('name')}: {first_line(problem)}")
                elif case.find("skipped") is not None:
                    skip += 1
                    status = "skip"
                else:
                    status = "pass"
                cases.append((case.get("classname") or "", case.get("name") or "", status))
            print(f"{os.path.relpath(path, args.root)}: {len(found)} tests, {bad} failed, {skip} skipped" + (f" (device {device})" if device else ""))
            run += len(found)
            failed += bad
            skipped += skip
        extra = f", ignored {stale} stale result file(s)" if stale else ""
        print(f"{module} tests: {run} run, {failed} failed, {skipped} skipped{extra}")
        if run == 0:
            problems.append(f"no {'fresh ' if args.since is not None else ''}test results for {module} ({args.glob.format(module=module)}); zero tests ran there")
        if failed:
            problems.append(f"{failed} {module} test(s) failed")

    for module, wanted in requirements:
        matching = [c for c in cases_by_module[module] if class_matches(wanted, c[0])]
        count = lambda status: sum(1 for c in matching if c[2] == status)
        print(f"required {module}:{wanted}: {len(matching)} tests, {count('fail')} failed, {count('skip')} skipped")
        if not matching:
            problems.append(f"required test class {wanted} has no results in {module}")
        elif count("fail"):
            problems.append(f"required test class {wanted} has {count('fail')} failed test(s)")
        elif count("skip"):
            problems.append(f"required test class {wanted} has {count('skip')} skipped test(s)")

    if problems:
        sys.exit("; ".join(problems))
    print("device test verification passed")


if __name__ == "__main__":
    main()
