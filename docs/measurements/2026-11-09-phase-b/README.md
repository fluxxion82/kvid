# Phase B of the mobile indexing study: parity and timing on the Pixel 8 and the Pixel 4 XL (November 9, 2026; incomplete)

Protocol: "Phase B commands" in [docs/plans/mobile-indexing.md](../../plans/mobile-indexing.md). Run by the local session. **The Pixel 8 is the owner's daily phone and was attached twice, for about 50 and 60 minutes, so its part is partial**; what is missing is listed at the end. Sections 1 and 2 are the first attachment, section 3 the second, and section 4 the Pixel 4 XL, which the owner offered for the night.

| | |
|---|---|
| Code | `phase-5-mobile-indexing` at `586cbde3e12c50cde62aff882ee139b699e6546f`, clean tree |
| Test APK | `kvid-embed-onnx-androidTest.apk`, built with `-Pkvid.deviceTestAbi=arm64-v8a`, sha256 `2eb4691c…c6b959`; the same APK for every run, one fresh instrumentation process per run |
| Runtime | `onnxruntime-android` 1.30.0, CPU provider |
| Device | Pixel 8 (`shiba`), `google/shiba/shiba:17/CP3A.261005.005/16271449:user/release-keys`; the test reports `android-37/Pixel_8/arm64-v8a` |
| State | Screen on, launcher in the foreground, on external power at 80% and not charging (`charge_status=4`) for every timed run, battery saver off |
| Model files | Built from `build_variants.py` in a `linux/amd64` container (python 3.12, ONNX Runtime 1.30.0). All seven digests equal Phase A's `variants.json` |

Times are UTC; the Pixel 8 runs span 23:41 on November 9 to 02:03 on November 10.

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
| `pixel8-session2.txt` | Raw lines of the Pixel 8's later runs: `m3` block 3 and two sweep runs. From here on each run also records whether the launcher stayed in front |
| `pixel8-session2-gate.txt`, `pixel8-session2-thermal.txt`, `pixel8-session2-charge-wait.txt` | The gate and thermal lines of those runs, and the wait for charging to stop at 80% before them |
| `excluded-runs.txt` | The two runs excluded because the phone was in use, with the evidence |
| `pixel4xl/` | Pixel 4 XL: device state, parity against both references, the three failure outputs, the idle baseline and cooling rule, `screening*.txt` (first session), `session2*.txt` (sweep, edit latency, finalist blocks, 5,000 notes) and `xnnpack-crash/` (output of the two crashed runs and the crash log of a rerun) |
| `driver/` | The scripts that ran it (`queue.sh` reads a queue file such as `q-screen.txt` and calls `run.sh`; `run-parity.sh` is the earlier `run.sh` used for the Pixel 8's parity; `fg.sh` reports the app in front; `therm.sh` reads the temperatures; `p4xl-stage1.sh`, `p4xl-stage2.sh` and `p4xl-stage3.sh` are the Pixel 4 XL's three sessions, with queues `q-screen.txt`, `q-p4xl-2.txt` and `q-p4xl-3.txt`). They write `raw.log`, `gate.log` and `thermal.log`; the copies here are renamed to `.txt` because the repository ignores `*.log` |

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

### `m3`, 200 notes, every note at the limit (95,342 tokens at 512; 51,116 at 256)

Blocks 1 and 2 ran in session 1; block 3 ran 31 minutes later, after the phone had been away (section 3).

| Configuration | Per note, blocks 1 to 3 (ms) | Ratio to baseline in the same block | Peak PSS, blocks 1 to 3 (KiB) |
|---|---|---|---|
| `bge-fp32@512` | 376.2, 369.1, 458.0 (throttled) | | 543,114; 534,718; 529,425 |
| `bge-fp32-opt@512` | 372.1, excluded, 334.5 | 0.99, none, 0.73 (baseline throttled) | 429,130; excluded; 445,682 |
| `bge-fp32@256` | 160.6, 187.7, 190.0 | 0.43, 0.51, 0.41 (baseline throttled) | 505,880; 398,759; 497,164 |

- **Excluded:** `bge-fp32-opt@512` in block 2 (420.3 ms, 444,889 KiB). The phone was in use during the run; see `excluded-runs.txt`. The first version of this file counted it.
- **Throttled:** the baseline in block 3 ran last in its block and reported thermal status 1 from about 50 s in until the end (skin temperature up to 40.1 °C), although it started inside the cooling rule. Its 458.0 ms is kept and marked; the two block 3 ratios are therefore not like for like.

Reading, as screening evidence only (three runs per configuration and corpus):
- **The optimized graph is not shown to be faster.** Its ratios to the baseline straddle 1 on `mixed`. On `m3` only one pair is clean (0.99); in block 3 it took 334.5 ms against a throttled baseline. It does hold less memory: about 40,000 KiB less at peak on `mixed` and about 85,000 to 115,000 KiB less on `m3`.
- **256 tokens is faster in every block:** 0.73 to 0.79 of the baseline's time on `mixed`, where a fifth of the tokens are cut, and 0.43 and 0.51 on `m3` in the two clean blocks. On `mixed` it also peaks about 130,000 KiB lower.
- **The baseline's peak on `m3` is above 500 MB** in all three runs (529,425 to 543,114 KiB, a sampled lower bound). `bge-fp32@256` on `m3` reached 505,880, 398,759 and 497,164 KiB: two of three runs are at the limit too.
- **Against the 5-minute goal** (60 ms per note): on `mixed`, 72.4 ms per note is 6.0 minutes per 5,000 notes and 52.8 ms is 4.4 minutes. These are projections from a 200-note microbenchmark, which the plan does not accept as the answer; the 5,000-note store runs have not been made.
- **Heat:** the `mixed` runs end at a skin temperature of 36.2 to 37.5 °C; the `m3` runs at 38.3 to 38.9 °C, just under the phone's first throttling threshold (39.0 °C). Two runs crossed it: `bge-fp32-opt@512`, `m3`, block 1 reported thermal status 1 for its last 30 s, and the baseline in block 3 for most of the run.

## 3. The Pixel 8's later runs

The phone came back at 00:59. Before the runs the driver waited for charging to stop (the phone holds at 80%), so the state matches session 1.

| Time (UTC) | What happened |
|---|---|
| 01:01 to 01:07 | `m3` block 3, three runs, launcher in front throughout (table above) |
| 01:08 | `sweep-mixed-bge-fp32@512-default`: 74.1 ms per note, peak 458,455 KiB. Clean. The first run of the thread sweep |
| 01:09 | `sweep-mixed-bge-fp32@256-default`: 73.1 ms per note. **Excluded:** the owner picked the phone up 14 s into the run. This outlier (256 tokens had taken 52.5 to 53.9 ms three times) is how phone use was noticed |
| 01:10 | USB dropped for a moment and the queue stopped |
| 01:15 to 01:52 | **No runs, a driver bug.** The new foreground check (`fg.sh`) was written without its execute bit, so the gate never saw the launcher and waited. 37 minutes of attached time were lost |
| 01:52 | The 5,000-note indexing run of the baseline started (skin 30.2 °C, battery 29.7 °C, charging at 80%: cooler than any session 1 start, and nominally charging) |
| about 02:00 | The phone was unplugged during that run. No result line was received; the run is lost |

**How phone use is detected.** `dumpsys usagestats` lists when an activity came to the front. A run is excluded if any app other than the launcher or the test did so between its start and end, or if the launcher was not in front before or after. The logs record only "launcher" or "other", never the app. Checked back over session 1, this found one more disturbed run (`m3` block 2, above); the other 14 were clean.

## 4. Pixel 4 XL

The owner offered a Pixel 4 XL for the night. It fills the plan's "second physical phone" slot; the plan says the Pixel 8 decides, so its numbers are reported separately and never pooled.

| | |
|---|---|
| Device | Pixel 4 XL (`coral`), `google/coral/coral:13/TP1A.221005.002.B2/9382335:user/release-keys`, Snapdragon 855 (`SM8150`), 5,596,588 kB of memory; the test reports `android-33/Pixel_4_XL/arm64-v8a` |
| APK and model files | The same APK (sha256 `2eb4691c…c6b959`) and the same two fixture directories as the Pixel 8 |
| State during parity | 9 to 20 minutes after boot, charging from 15% to 25%, thermal status 1 at the start. Parity does not depend on speed |

### Parity (02:04 to 02:14)

| Variant | Against the x86 reference: minimum cosine | Results below 0.9999 | Verdict | Against the macOS arm64 reference |
|---|---|---|---|---|
| `bge-fp32` | 1.00000000 | 0 | pass | |
| `bge-fp32-opt` | 1.00000000 | 0 | pass | |
| `minilm-fp32` | 1.00000000 | 0 | pass | |
| `minilm-fp32-opt` | 1.00000000 | 0 | pass | |
| `bge-int8` | 0.91568454 | 388 of 388 | **fail** | 1.00000000 |
| `bge-int8-all` | 0.99379598 | 342 of 388 | **fail** | 1.00000000 |
| `minilm-int8` | 0.95446194 | 388 of 388 | **fail** | 1.00000000 |

Every figure equals the Pixel 8's to the last printed digit, including the largest absolute differences of the fp32 variants. Two ARM phones with different chips (Tensor G3 and Snapdragon 855) and Android versions (17 and 13) give the same int8 vectors as each other and as macOS arm64, and both miss the x86 reference by the same amount. The three int8 variants are **not timed** on this phone either.

### Timing: cooling rule

The rule was written to `pixel4xl/cooling-rule.txt` at 02:15, before any timed run: wait until charging ends, log an idle baseline, then start a run only at thermal status 0 with the skin sensor (`sdm-therm-monitor`; status 1 starts at 40.0 °C) at or below the highest of the last ten idle readings plus 0.5 °C.

| | |
|---|---|
| Charge wait | 02:15 to 03:45, from 27% to 94% (`pixel4xl/charge-wait.txt`). The phone was off the cable from 02:50 to 02:52 while the owner swapped phones |
| Idle baseline | 03:45 to 03:50, eleven readings 30 s apart, 29.8 to 30.0 °C, thermal status 0 (`pixel4xl/idle-baseline.txt`) |
| Rule | Highest idle reading 30.0 °C, so a run starts only at status 0 and skin at or below **30.5 °C** |
| Charging during the runs | The wait ended when the phone first reported "not charging" at 94%, but it went on topping up: the charge state alternated between 2 (charging) and 4 until 100%. The first run started at 96%, the second at 98%, the rest at 97% to 100% with state 4 or 5 (full). Each run's state is in `pixel4xl/screening-thermal.txt` |
| Screen and foreground | Screen on, launcher in front for every run; no run was disturbed and none was excluded |

### Screening (03:50 to 07:21)

Same 18-line queue as the Pixel 8 (`driver/q-screen.txt`): 200 notes, three blocks per corpus in the orders ABC, CAB, BCA, a fresh process per run. Raw lines are in `pixel4xl/screening.txt`. Thermal status stayed 0 in every run.

`mixed`, ms per note:

| Block | `bge-fp32@512` | `bge-fp32-opt@512` | `bge-fp32@256` | opt / baseline | 256 / baseline |
|---|---|---|---|---|---|
| 1 | 138.5 | 129.4 | 102.2 | 0.93 | 0.74 |
| 2 | 141.8 | 129.3 | 101.9 | 0.91 | 0.72 |
| 3 | 139.7 | 130.6 | 100.7 | 0.93 | 0.72 |

`m3`, ms per note:

| Block | `bge-fp32@512` | `bge-fp32-opt@512` | `bge-fp32@256` | opt / baseline | 256 / baseline |
|---|---|---|---|---|---|
| 1 | 578.6 | 526.8 | 272.0 | 0.91 | 0.47 |
| 2 | 575.4 | 532.8 | 272.7 | 0.93 | 0.47 |
| 3 | 574.8 | 525.9 | 273.1 | 0.91 | 0.48 |

Peak memory (`mem_peak_kb`, KiB), lowest to highest over the three blocks:

| Corpus | `bge-fp32@512` | `bge-fp32-opt@512` | `bge-fp32@256` |
|---|---|---|---|
| `mixed` | 427,651 to 435,499 | 387,691 to 389,387 | 277,417 to 283,224 |
| `m3` | 477,484 to 484,323 | 394,574 to 395,212 | 273,320 to 278,968 |

What the phone showed:

- **About twice as slow as the Pixel 8, far steadier.** Runs of one configuration differ by at most 2.4% (`bge-fp32@512` on `mixed`); the Pixel 8's `mixed` runs differed by up to 17%.
- **Same ranking.** The 256-token limit is 26% to 28% faster on `mixed` and 52% to 53% faster on `m3`. The optimized graph is 7% to 9% faster on both corpora, in every block, which is below the plan's 10% practical difference. No configuration reaches the 60 ms per note goal on this phone.
- **Memory.** Every peak is under the 500 MB limit; the baseline on `m3` is the closest at 484,323 KiB (473 MiB).
- **Cooling took most of the night.** A `mixed` run raised the skin sensor from 30.5 to about 34 °C and the next run could start 2 to 5 minutes later. An `m3` run raised it to 36 to 38 °C; the phone then needed 8 to 9 minutes to fall back to 30.5 °C in the first block and 20 to 30 minutes in the later ones (`pixel4xl/screening-gate.txt`).

### Second session (07:21 to 11:32): sweep, edit latency, finalist blocks, indexing

Same rule (status 0, skin at or below 30.5 °C before each run), script `driver/p4xl-stage2.sh`, queue `driver/q-p4xl-2.txt`, 30 runs. Raw lines: `pixel4xl/session2.txt`; starts: `pixel4xl/session2-gate.txt`; before and after each run: `pixel4xl/session2-thermal.txt`. The phone reported "not charging" for the whole session and its level fell from 97% to 89% on the cable. No run was disturbed. Two runs crashed (XNNPACK, below); the other 28 completed.

#### Threads and XNNPACK (`mixed`, 200 notes, one run per cell, ms per note)

| Configuration | default | `threads=1` | `threads=2` | `threads=4` | `ep=xnnpack` |
|---|---|---|---|---|---|
| `bge-fp32@512` | 139.9 | 248.9 | 124.5 | 138.6 | **crash** |
| `bge-fp32-opt@512` | 128.5 | 237.4 | 116.3 | 127.4 | 228.6 |
| `bge-fp32@256` | 102.1 | 179.0 | 91.3 | 101.8 | **crash** |

- **Four threads equals the default** (within 1%).
- **Two threads were 10% to 11% faster than the default in this single pass** (ratios 0.89, 0.91, 0.89). One run per cell is not enough for that size of difference; two paired blocks follow in the third session.
- **One thread is 75% to 85% slower.**
- **XNNPACK is unusable here.** With the plain `bge-fp32` graph the test process dies with `SIGSEGV` inside `libonnxruntime.so` about 4 s after it starts, at both token limits and again in a rerun made only to keep the crash log (`pixel4xl/xnnpack-crash/`). With the optimized graph it runs, 78% slower than the CPU provider (228.6 against 128.5 ms), and the process still holds 266,411 KiB after the embedder is closed (CPU provider runs: 63,392 to 115,062 KiB).

#### Edit latency (`latency=on`: 200 notes embedded one per call, ms)

| Configuration | p50 | p95 | Up to 64 tokens (106 notes): p50, p95 | Up to 128 (32): p50, p95 | Up to 256: p50, p95 | Over 256: p50, p95 |
|---|---|---|---|---|---|---|
| `bge-fp32@512` | 49.5 | 578.7 | 22.4, 49.8 | 79.7, 110.1 | 166.3, 215.2 (25 notes) | 362.2, 637.3 (37 notes) |
| `bge-fp32-opt@512` | 48.8 | 540.2 | 21.7, 48.9 | 79.2, 110.6 | 165.2, 220.6 (25 notes) | 356.9, 610.6 (37 notes) |
| `bge-fp32@256` | 56.0 | 254.8 | 25.0, 57.2 | 84.2, 120.8 | 223.9, 260.2 (62 notes) | none |

- **No configuration meets 200 ms at p95 over all notes on this phone.** Notes of up to 128 tokens do (p95 110 to 121 ms); notes of 129 to 256 tokens are just over (215 to 221 ms).
- **The 256-token limit cuts the tail** from about 580 to 255 ms because the 37 long notes are truncated. Its short-note figures are 6% to 15% higher than the baseline's in this one run each; the model and thread settings are the same, so treat that as run-to-run difference until repeated.

#### Finalist blocks 4 to 6 (`mixed`, 200 notes, ms per note)

| Block (order) | `bge-fp32@512` | `bge-fp32-opt@512` | `bge-fp32@256` | opt / baseline | 256 / baseline |
|---|---|---|---|---|---|
| 4 (ACB) | 138.2 | 126.9 | 102.0 | 0.92 | 0.74 |
| 5 (BAC) | 139.4 | 127.4 | 100.2 | 0.91 | 0.72 |
| 6 (CBA) | 144.6 | 127.8 | 102.5 | 0.88 | 0.71 |

Over all six blocks (screening blocks 1 to 3 and these): medians 139.6, 128.6 and 102.0 ms; the optimized graph's ratio is 0.88 to 0.93 (median 0.92), the 256-token limit's 0.71 to 0.74 (median 0.72). The optimized graph is faster in six of six blocks, by 7% to 12%; the gain reaches the 10% practical difference in one block only.

#### 5,000-note `IndexingMeasurementTest` (`mixed`, one run each)

| Configuration | Total | Notes per second (early, late) | ms per note | Peak PSS | Thermal |
|---|---|---|---|---|---|
| `bge-fp32@512` | 896.4 s (14.9 min) | 5.58 (5.97, 5.45) | 179.3 | 521,064 KiB (509 MiB) | status 1 from 4.5 min in; skin up to 42.7 °C |
| `bge-fp32-opt@512` | 829.2 s (13.8 min) | 6.03 (6.50, 5.76) | 165.8 | 428,152 KiB (418 MiB) | status 1 from 4.0 min in; skin up to 42.2 °C |
| `bge-fp32@256` | 607.1 s (10.1 min) | 8.24 (8.71, 8.08) | 121.4 | 306,019 KiB (299 MiB) | status 1 from 4.0 min in; skin up to 42.1 °C |

- **Every run is two to three times over the 5-minute goal** on this phone. The goal names a Pixel 8-class phone; this one is four chip generations older.
- **Every run was throttled.** Each started inside the cooling rule, crossed 40 °C after four minutes and stayed at thermal status 1 (never 2) to the end. Late throughput is 7% to 11% below early throughput.
- **Ratios hold under load:** the optimized graph takes 0.93 of the baseline's time, the 256-token limit 0.68.
- **The baseline exceeds the 500 MB limit** (509 MiB). The optimized graph peaks 91 MiB lower, the 256-token limit 210 MiB lower.
- Per note the store run costs more than the 200-note screening (179 against 140 ms for the baseline): the 5,000 notes average 130 tokens against 124, and the phone throttles.

### Third session (from 11:34): default threads against `threads=2`

Two paired blocks on `mixed` for all three configurations (`driver/p4xl-stage3.sh`, queue `driver/q-p4xl-3.txt`, 12 runs), same rule. In progress when this version was written.

## Not done

| Planned | State |
|---|---|
| Timing of `bge-int8@512`, `bge-int8-all@512`, `minilm-int8@256` | Skipped by rule on both phones: parity failed |
| Pixel 8: threads 1, 2, 4 and XNNPACK on the best two | One of ten runs made (`bge-fp32@512`, default threads) |
| Pixel 8: edit latency (`latency=on`) | Not run |
| Pixel 8: finalist blocks in a second cooled session | Not run; three balanced blocks on `mixed` exist |
| Pixel 8: 5,000-note `IndexingMeasurementTest`, baseline and finalists | Baseline started and lost when the phone was unplugged; none completed |
| Pixel 8: replacement for the excluded `m3` run | Not run |
| Pixel 4 XL: `ep=xnnpack` for `bge-fp32@512` and `bge-fp32@256` | Crashed (`SIGSEGV` in ONNX Runtime); no timing |
| Pixel 4 XL: `m3` beyond screening; 5,000-note repeats | Not planned; one 5,000-note run per configuration |
| Emulator harness check | Not run: the local API 35 emulator's disk is full |

The model files and the test APK are still on both phones under `/data/local/tmp/kvid-model-variants` (x86 fixtures) and `/data/local/tmp/kvid-arm-ref` (the arm64 fixtures for the three int8 variants). The remaining Pixel 8 queue is `driver/q-s2c.txt`.
