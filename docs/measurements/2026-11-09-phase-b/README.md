# Phase B of the mobile indexing study: Pixel 8 parity and screening (November 9, 2026; incomplete)

Protocol: "Phase B commands" in [docs/plans/mobile-indexing.md](../../plans/mobile-indexing.md). Run by the local session. **The phone was unplugged after the fifteenth screening run, so this archive is partial**; what is missing is listed at the end.

| | |
|---|---|
| Code | `phase-5-mobile-indexing` at `586cbde3e12c50cde62aff882ee139b699e6546f`, clean tree |
| Test APK | `kvid-embed-onnx-androidTest.apk`, built with `-Pkvid.deviceTestAbi=arm64-v8a`, sha256 `2eb4691c…c6b959`; the same APK for every run, one fresh instrumentation process per run |
| Runtime | `onnxruntime-android` 1.30.0, CPU provider |
| Device | Pixel 8 (`shiba`), `google/shiba/shiba:17/CP3A.261005.005/16271449:user/release-keys`; the test reports `android-37/Pixel_8/arm64-v8a` |
| State | Screen on, launcher in the foreground, on external power at 80% and not charging (`charge_status=4`) for every timed run, battery saver off |
| Model files | Built from `build_variants.py` in a `linux/amd64` container (python 3.12, ONNX Runtime 1.30.0). All seven digests equal Phase A's `variants.json` |

Times are UTC; the runs span 23:41 on November 9 to 00:30 on November 10.

## Files

| File | Content |
|---|---|
| `parity-x86-reference.txt` | `OnnxEmbedderParityTest`, all seven variants against the container-built x86 fixtures: the plan's test |
| `parity-arm-reference.txt` | The three int8 variants against fixtures written on macOS arm64: a diagnostic, not the plan's test |
| `parity-failures/` | Full instrumentation output of the three failures |
| `parity-thermal.txt` | Battery temperature and thermal status around each parity run (skin temperature was not read yet) |
| `reference-drift.txt` | The fixture vectors of the two builders compared with each other and with fp32 |
| `variants-docker-x86.json` | `variants.json` of the container build (drift figures differ from Phase A's; see below) |
| `idle-baseline.txt` | The idle cool-down that set the cooling rule |
| `screening-session1.txt` | Every raw `[kvid-embedding-tune]` line, with thermal readings before, every 15 s during, and after |
| `screening-gate.txt`, `screening-thermal.txt` | The wait before each run and the reading that passed the gate; one line per run |
| `driver/` | The scripts that ran it (`queue.sh` reads `q-screen.txt`; `run-parity.sh` is the earlier `run.sh` used for parity). They write `raw.log`, `gate.log` and `thermal.log`; the copies here are renamed to `.txt` because the repository ignores `*.log` |

## 1. Parity on the Pixel 8

Threshold: cosine 0.9999 on all 194 fixture texts, single and batched.

| Variant | Against the x86 reference: minimum cosine | Results below 0.9999 | Verdict | Against the macOS arm64 reference |
|---|---|---|---|---|
| `bge-fp32` | 1.00000000 | 0 | pass | |
| `bge-fp32-opt` | 1.00000000 | 0 | pass | |
| `minilm-fp32` | 1.00000000 | 0 | pass | |
| `minilm-fp32-opt` | 1.00000000 | 0 | pass | |
| `bge-int8` | 0.91568454 | 388 of 388 | **fail** | 1.00000000 |
| `bge-int8-all` | 0.99379598 | 342 of 388 | **fail** | 1.00000000 |
| `minilm-int8` | 0.95446194 | 388 of 388 | **fail** | 1.00000000 |

Under the plan's rule the three int8 variants are not portable and were **not timed**. On the phone, single and batched vectors agree for all of them (`batched_min_cosine=1.00000000`).

What the diagnostic adds:
- **ARM is self-consistent.** The phone reproduces the macOS arm64 Python fixtures to 6e-8, from the same model files (same digests). The difference is between CPU architectures, not between the phone and a desktop.
- **The container's x86 reference is itself the outlier for two variants.** The container runs under Rosetta with AVX2 and no VNNI. Its `bge-int8` vectors drift from fp32 to a minimum cosine of 0.9189, against 0.9871 recorded by the cloud session's Xeon in Phase A and 0.9873 on arm64 (`minilm-int8`: 0.9561, 0.9607 and 0.9640). The likely cause is the saturating AVX2 path for 8-bit products, which Codex's October review names as an x86 problem.
- **arm64 is close to the Phase A machine but not shown equal to it.** For `bge-int8` the mean drift from fp32 is 0.99635 on arm64 and 0.99636 in Phase A, and several texts agree to eight digits, but others differ (the Hebrew greeting: 0.98826 on arm64, 0.98710 in Phase A). Phase A's fixture vectors were not archived, so the cosine between the phone and the Phase A machine cannot be computed here.
- **`bge-int8-all`** drifts the same amount on all three machines (minimum 0.9122, 0.9122, 0.9121) yet its arm64 vectors are only 0.9938 from the container's.

So no int8 variant gives one vector per text across architectures at 0.9999, on any pair of machines measured. Phase A's int8 quality figures were measured on x86 and are not established for ARM; the drift figures suggest they would be similar for `bge-int8`, which is a guess until the evaluation is repeated on arm64.

A side finding: built natively on macOS arm64, `bge-fp32-opt` and `minilm-fp32-opt` do not reproduce their pinned digests (the offline graph optimizer writes a different file there), so the plan's build commands only work on x86. The phone ran the container-built files.

## 2. Screening (session 1)

Configurations left after parity: `bge-fp32@512` (baseline), `bge-fp32-opt@512`, `bge-fp32@256`. Defaults otherwise: batch 4, sorted, arena on, default threads, CPU, warm-up 8 notes.

**Cooling rule**, fixed before the first timed run from `idle-baseline.txt` (the phone idles at a skin temperature of 35.0 to 35.4 °C with the screen on): a run starts only at thermal status 0 with `VIRTUAL-SKIN` at or below 35.5 °C. Every run below met it; waits were 0 to 287 s. Battery temperature was 35.2 to 36.0 °C at every start.

Order: three blocks per corpus as a Latin square (ABC, CAB, BCA).

### `mixed`, 200 notes (24,836 tokens at 512; 19,761 at 256)

| Configuration | Per note, blocks 1 to 3 (ms) | Median (range) | Ratio to baseline in the same block | Peak PSS, blocks 1 to 3 (KiB) | PSS after close (KiB) |
|---|---|---|---|---|---|
| `bge-fp32@512` | 67.1, 72.4, 73.7 | 72.4 (67.1 to 73.7) | | 453,940; 437,055; 451,155 | 119,510; 90,255; 116,727 |
| `bge-fp32-opt@512` | 69.5, 69.8, 81.1 | 69.8 (69.5 to 81.1) | 1.04, 0.96, 1.10 | 411,828; 407,323; 391,283 | 114,264; 109,775; 93,006 |
| `bge-fp32@256` | 52.8, 52.5, 53.9 | 52.8 (52.5 to 53.9) | 0.79, 0.73, 0.73 | 313,103; 311,481; 321,596 | 97,311; 98,677; 87,384 |

### `m3`, 200 notes, every note at the limit (95,342 tokens at 512; 51,116 at 256). Two blocks of three

| Configuration | Per note, blocks 1 and 2 (ms) | Ratio to baseline in the same block | Peak PSS (KiB) |
|---|---|---|---|
| `bge-fp32@512` | 376.2, 369.1 | | 543,114; 534,718 |
| `bge-fp32-opt@512` | 372.1, 420.3 | 0.99, 1.14 | 429,130; 444,889 |
| `bge-fp32@256` | 160.6, 187.7 | 0.43, 0.51 | 505,880; 398,759 |

Reading, as screening evidence only (three runs, one session):
- **The optimized graph is not faster.** Its ratios to the baseline straddle 1 on both corpora. It does hold less memory: about 40,000 KiB less at peak on `mixed` and about 100,000 KiB less on `m3`.
- **256 tokens is faster in every block:** 0.73 to 0.79 of the baseline's time on `mixed`, where a fifth of the tokens are cut, and 0.43 to 0.51 on `m3`. On `mixed` it also peaks about 130,000 KiB lower.
- **The baseline's peak on `m3` is above 500 MB** in both runs (543,114 and 534,718 KiB, a sampled lower bound). One `bge-fp32@256` run on `m3` reached 505,880 KiB and the other 398,759; that spread needs the third block.
- **Against the 5-minute goal** (60 ms per note): on `mixed`, 72.4 ms per note is 6.0 minutes per 5,000 notes and 52.8 ms is 4.4 minutes. These are projections from a 200-note microbenchmark, which the plan does not accept as the answer; the 5,000-note store runs have not been made.
- **Heat:** the `mixed` runs end at a skin temperature of 36.2 to 37.5 °C; the `m3` runs at 38.3 to 38.9 °C, just under the phone's first throttling threshold (39.0 °C). One run crossed it: `bge-fp32-opt@512`, `m3`, block 1 reported thermal status 1 for its last 30 s.

## Not done

The phone was disconnected at about 00:31 UTC, between runs (no run was interrupted; the queue then waited 20 minutes and stopped).

| Planned | State |
|---|---|
| Timing of `bge-int8@512`, `bge-int8-all@512`, `minilm-int8@256` | Skipped by rule: parity failed |
| `m3` block 3 (three runs) | Not run |
| Threads 1, 2, 4 and XNNPACK on the best two | Not run |
| Edit latency (`latency=on`) | Not run |
| Finalist blocks in a second cooled session | Not run; session 1 gives three balanced blocks on `mixed` |
| 5,000-note `IndexingMeasurementTest`, baseline and finalists | Not run |
| Emulator harness check, mid-range phone | Not run: the local API 35 emulator's disk is full, and no second phone was attached |

The model files and the test APK are still on the phone under `/data/local/tmp/kvid-model-variants` (x86 fixtures) and `/data/local/tmp/kvid-arm-ref` (the arm64 fixtures for the three int8 variants).
