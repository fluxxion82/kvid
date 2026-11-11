# Phase B of the mobile indexing study: parity and timing on the Pixel 8 and the Pixel 4 XL (November 9 to 11, 2026)

Protocol: "Phase B commands" in [docs/plans/mobile-indexing.md](../../plans/mobile-indexing.md). Run by the local session. **The Pixel 8 is the owner's daily phone and was attached only at times**: twice for about 50 and 60 minutes on November 9 to 10, and again from 17:57 on November 10 to 01:27 on November 11. Sections 1 and 2 are the first attachment, section 3 the second, section 4 the Pixel 4 XL, which the owner offered for the night, and section 5 the Pixel 8's later attachments, which made the remaining planned runs. What the protocol asks for and was not done is listed at the end. Codex reviewed this archive on November 11; its findings are in the plan and the corrections are made here.

**Units and arithmetic.** PSS is recorded in KiB. The 500 MB limit is 488,281 KiB (476.8 MiB); where a peak is near it both MB and MiB are given. Per-run ratios and medians in the tables use the printed `per_note_ms`; the 95% intervals use `total_ms` and resample blocks (10,000 resamples, seed 20261108). Every PSS peak is a 100 ms sampled lower bound in the test process, sampled from after the model is opened; none is a whole-app, full-lifecycle figure.

| | |
|---|---|
| Code | `phase-5-mobile-indexing` at `586cbde3e12c50cde62aff882ee139b699e6546f`, clean tree |
| Test APK | `kvid-embed-onnx-androidTest.apk`, built with `-Pkvid.deviceTestAbi=arm64-v8a`, sha256 `2eb4691c…c6b959`; the same APK for every run, one fresh instrumentation process per run |
| Runtime | `onnxruntime-android` 1.30.0, CPU provider |
| Device | Pixel 8 (`shiba`), `google/shiba/shiba:17/CP3A.261005.005/16271449:user/release-keys`; the test reports `android-37/Pixel_8/arm64-v8a` |
| State | Screen on, launcher in the foreground, on external power at 80% and not charging (`charge_status=4`) for every timed run, battery saver off |
| Model files | Built from `build_variants.py` in a `linux/amd64` container (python 3.12, ONNX Runtime 1.30.0). All seven digests equal Phase A's `variants.json` |

Times are UTC; the Pixel 8 runs of sections 1 to 3 span 23:41 on November 9 to 02:03 on November 10, those of section 5 18:58 on November 10 to 01:27 on November 11.

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
| `pixel8-session2-supervise.txt` | When the supervisor script saw the Pixel 8 and how each chain ended |
| `pixel8-idle-wait.txt`, `pixel8-cooling-rule-2.txt` | Section 5: idle readings every 60 s from 22:52, and the second cooling rule derived from them |
| `pixel8-xnnpack-crash/` | Output of the two crashed XNNPACK runs on the Pixel 8 and the crash line of a rerun |
| `excluded-runs.txt` | The runs excluded because the phone was in use, with the evidence |
| `pixel4xl/` | Pixel 4 XL: device state, parity against both references, the three failure outputs, the idle baseline and cooling rule, `screening*.txt` (first session), `session2*.txt` (sweep, edit latency, finalist blocks, 5,000 notes), `session3*.txt` (default threads against two) and `xnnpack-crash/` (output of the two crashed runs and the crash log of a rerun) |
| `driver/` | The scripts that ran it (`queue.sh` reads a queue file such as `q-screen.txt` and calls `run.sh`; `run-parity.sh` is the earlier `run.sh` used for the Pixel 8's parity; `fg.sh` reports the app in front; `therm.sh` reads the temperatures; `p4xl-stage1.sh`, `p4xl-stage2.sh` and `p4xl-stage3.sh` are the Pixel 4 XL's three sessions, with queues `q-screen.txt`, `q-p4xl-2.txt` and `q-p4xl-3.txt`; `p8-chain2.sh` to `p8-chain5.sh` and `switch-rule.sh` are section 5, with queues `q-s2d.txt` and `q-s2e.txt`). They write `raw.log`, `gate.log` and `thermal.log`; the copies here are renamed to `.txt` because the repository ignores `*.log` |

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

A side observation, **not archived**: a native macOS arm64 build did not reproduce the pinned digest of an `-opt` variant (the offline graph optimizer writes a different file there), which is why the files were built in the x86 container. No digests of that native build were kept, and `reference-drift.txt` records only that `minilm-fp32-opt` was not built on the Mac, so treat this as unverified. The phone ran the container-built files.

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

### `m3`, 200 long notes (95,342 tokens at 512; 51,116 at 256)

Blocks 1 and 2 ran in session 1; block 3 ran 31 minutes later, after the phone had been away (section 3).

| Configuration | Per note, blocks 1 to 3 (ms) | Ratio to baseline in the same block | Peak PSS, blocks 1 to 3 (KiB) |
|---|---|---|---|
| `bge-fp32@512` | 376.2, 369.1, 458.0 (throttled) | | 543,114; 534,718; 529,425 |
| `bge-fp32-opt@512` | 372.1, excluded, 334.5 | 0.99, none, 0.73 (baseline throttled) | 429,130; excluded; 445,682 |
| `bge-fp32@256` | 160.6, 187.7, 190.0 | 0.43, 0.51, 0.41 (baseline throttled) | 505,880; 398,759; 497,164 |

- **Excluded:** `bge-fp32-opt@512` in block 2 (420.3 ms, 444,889 KiB). The phone was in use during the run; see `excluded-runs.txt`. The first version of this file counted it.
- **Throttled:** the baseline in block 3 ran last in its block and reported thermal status 1 from about 50 s in until the end (skin temperature up to 40.1 °C), although it started inside the cooling rule. Its 458.0 ms is kept and marked; the two block 3 ratios are therefore not like for like.

Reading, as screening evidence only (three runs per configuration and corpus):
- **The optimized graph is not shown to be faster.** Its ratios to the baseline straddle 1 on `mixed`. On `m3` no pair is free of throttling or interruption: in block 1 the optimized run reported thermal status 1 for its last 30 s (ratio 0.99), in block 2 it was disturbed, and in block 3 it took 334.5 ms against a throttled baseline. It does hold less memory: about 40,000 KiB less at peak on `mixed` and about 85,000 to 115,000 KiB less on `m3`.
- **256 tokens is faster in every block:** 0.73 to 0.79 of the baseline's time on `mixed`, where a fifth of the tokens are cut, and 0.43 and 0.51 on `m3` in the two clean blocks. On `mixed` it also peaks about 130,000 KiB lower.
- **The baseline's peak on `m3` is above 500 MB** in all three runs (529,425 to 543,114 KiB, a sampled lower bound). `bge-fp32@256` on `m3` reached 505,880, 398,759 and 497,164 KiB: two of three runs are above 500 MB as well (518 and 509 MB, which is 494 and 486 MiB).
- **Against the 5-minute goal** (60 ms per note): on `mixed`, 72.4 ms per note is 6.0 minutes per 5,000 notes and 52.8 ms is 4.4 minutes. These are projections from a 200-note microbenchmark, which the plan does not accept as the answer; the 5,000-note store runs are in section 5.
- **Heat:** the `mixed` runs end at a skin temperature of 36.2 to 37.5 °C; the `m3` runs at 37.9 to 38.9 °C, with up to 40.1 °C during a run; the phone's first throttling threshold is 39.0 °C. Two runs crossed it: `bge-fp32-opt@512`, `m3`, block 1 reported thermal status 1 for its last 30 s, and the baseline in block 3 for most of the run.

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
- **Two threads were 9% to 11% faster than the default in this single pass** (ratios 0.89, 0.91, 0.89). One run per cell is not enough for that size of difference; two paired blocks follow in the third session.
- **One thread is 75% to 85% slower.**
- **XNNPACK is unusable here.** With the plain `bge-fp32` graph the test process dies with `SIGSEGV` inside `libonnxruntime.so` about 4 s after it starts, at both token limits and again in a rerun made only to keep the crash log (`pixel4xl/xnnpack-crash/`). With the optimized graph it runs, 78% slower than the CPU provider (228.6 against 128.5 ms), and the process still holds 266,411 KiB after the embedder is closed (CPU provider runs: 63,392 to 115,062 KiB).

#### Edit latency (`latency=on`: 200 notes embedded one per call, ms)

| Configuration | p50 | p95 | Up to 64 tokens (106 notes): p50, p95 | 65 to 128 (32 notes): p50, p95 | 129 to 256: p50, p95 | Over 256: p50, p95 |
|---|---|---|---|---|---|---|
| `bge-fp32@512` | 49.5 | 578.7 | 22.4, 49.8 | 79.7, 110.1 | 166.3, 215.2 (25 notes) | 362.2, 637.3 (37 notes) |
| `bge-fp32-opt@512` | 48.8 | 540.2 | 21.7, 48.9 | 79.2, 110.6 | 165.2, 220.6 (25 notes) | 356.9, 610.6 (37 notes) |
| `bge-fp32@256` | 56.0 | 254.8 | 25.0, 57.2 | 84.2, 120.8 | 223.9, 260.2 (62 notes) | none |

- **No configuration meets 200 ms at p95 over all notes on this phone**, for embedding alone; the target also covers saving the vector, which this test does not time. Notes of up to 128 tokens are under it (p95 110 to 121 ms); notes of 129 to 256 tokens are just over (215 to 221 ms). One run of 200 calls per configuration.
- **The 256-token limit cuts the tail** from about 580 to 255 ms because the 37 long notes are truncated. Its short-note figures are 6% to 15% higher than the baseline's in this one run each; the model and thread settings are the same, so treat that as run-to-run difference until repeated.

#### Finalist blocks 4 to 6 (`mixed`, 200 notes, ms per note)

| Block (order) | `bge-fp32@512` | `bge-fp32-opt@512` | `bge-fp32@256` | opt / baseline | 256 / baseline |
|---|---|---|---|---|---|
| 4 (ACB) | 138.2 | 126.9 | 102.0 | 0.92 | 0.74 |
| 5 (BAC) | 139.4 | 127.4 | 100.2 | 0.91 | 0.72 |
| 6 (CBA) | 144.6 | 127.8 | 102.5 | 0.88 | 0.71 |

Over all six blocks (screening blocks 1 to 3 and these): medians 139.6, 128.6 and 102.0 ms; the optimized graph's ratio is 0.88 to 0.93 (median 0.92), the 256-token limit's 0.71 to 0.74 (median 0.72). The optimized graph is faster in six of six blocks, by 7% to 12%; the gain reaches the 10% practical difference in one block only. Block-resampled 95% interval of the mean ratio: 0.92 (0.90 to 0.93) for the optimized graph, a real gain that stays under the 10% threshold; 0.72 (0.72 to 0.73) for the 256-token limit.

#### 5,000-note `IndexingMeasurementTest` (`mixed`, one run each)

| Configuration | Total | Notes per second (early, late) | ms per note | Peak PSS | Thermal |
|---|---|---|---|---|---|
| `bge-fp32@512` | 896.4 s (14.9 min) | 5.58 (5.97, 5.45) | 179.3 | 521,064 KiB (509 MiB) | status 1 from 4.5 min in; skin up to 42.7 °C |
| `bge-fp32-opt@512` | 829.2 s (13.8 min) | 6.03 (6.50, 5.76) | 165.8 | 428,152 KiB (418 MiB) | status 1 from 4.0 min in; skin up to 42.2 °C |
| `bge-fp32@256` | 607.1 s (10.1 min) | 8.24 (8.71, 8.08) | 121.4 | 306,019 KiB (299 MiB) | status 1 from 4.0 min in; skin up to 42.1 °C |

- **Each of the three tested configurations is two to three times over the 5-minute goal** on this phone. The goal names a Pixel 8-class phone; this one is four chip generations older.
- **Every run was throttled.** Each started inside the cooling rule, crossed 40 °C after four minutes and stayed at thermal status 1 (never 2) to the end. Late throughput is 7% to 11% below early throughput.
- **Ratios hold under load:** the optimized graph takes 0.93 of the baseline's time, the 256-token limit 0.68.
- **The baseline exceeds the 500 MB limit** (521,064 KiB: 534 MB, 509 MiB). The optimized graph peaks 91 MiB lower (438 MB), the 256-token limit 210 MiB lower (313 MB).
- Per note the store run costs more than the 200-note screening (179 against 140 ms for the baseline): the 5,000 notes average 130 tokens against 124, and the phone throttles.

### Third session (11:34 to 12:37): default threads against `threads=2`

Two paired blocks on `mixed`, 200 notes, for all three configurations (`driver/p4xl-stage3.sh`, queue `driver/q-p4xl-3.txt`, 12 runs), same rule; within a pair the order alternates. Raw lines: `pixel4xl/session3.txt`; starts: `pixel4xl/session3-gate.txt`; before and after: `pixel4xl/session3-thermal.txt`. All 12 runs completed, started at status 0 and 30.0 to 30.5 °C, stayed at status 0 and were undisturbed. The phone still reported "not charging" (89% to 87%). The first run waited 15 minutes for the phone to cool after the 5,000-note runs.

| Configuration | Pair | default (ms per note) | `threads=2` (ms per note) | Ratio |
|---|---|---|---|---|
| `bge-fp32@512` | sweep | 139.9 | 124.5 | 0.89 |
| | block 1 | 139.4 | 123.4 | 0.89 |
| | block 2 | 141.3 | 122.7 | 0.87 |
| `bge-fp32-opt@512` | sweep | 128.5 | 116.3 | 0.91 |
| | block 1 | 127.0 | 119.5 | 0.94 |
| | block 2 | 129.1 | 120.7 | 0.93 |
| `bge-fp32@256` | sweep | 102.1 | 91.3 | 0.89 |
| | block 1 | 103.4 | 92.3 | 0.89 |
| | block 2 | 101.6 | 91.4 | 0.90 |

- **Two threads are faster than the default in nine of nine pairs on this phone** (three pairs per configuration, the sweep's included; with three pairs the interval is no more than their range). For the plain graph the gain is 10% to 13% at both token limits, at or above the 10% practical difference in all six pairs. For the optimized graph it is 6% to 9%, below it.
- **Peak memory barely changes** (two threads against the default: −1.5% to +3.2% per pair).
- **With two threads the optimized graph's lead shrinks to 2% to 3%** (119.5 and 120.7 against 123.4 and 122.7 ms): the two gains do not add up.
- This is the Snapdragon 855 (one prime, three large and four small cores). The Pixel 8's sweep (section 5) shows the opposite. Two threads were not tried with the 5,000-note run or with edit latency.

## 5. Pixel 8, November 10 to 11: indexing, edit latency, sweep, finalist blocks

Same APK, same model files. The files named `pixel8-session2*` continue from section 3: raw lines in `pixel8-session2.txt`, starts in `pixel8-session2-gate.txt`, before and after each run in `pixel8-session2-thermal.txt`. The phone was at 80% and not charging at every start, screen on, launcher in front.

| Time (UTC) | What happened |
|---|---|
| 17:57 to 18:57 | Attached at 39% and charging. The 30-minute wait for charging to stop was dropped because the owner could not promise a long attachment; the gate then waited the whole hour for the phone to cool and timed out as charging ended |
| 18:58 to 20:05 | Five runs under the first rule (skin at or below 35.5 °C): the 5,000-note baseline (**excluded**, another app was in front at its end), 5,000 notes at 256 tokens, edit latency three times |
| 20:05 to 21:50 | No runs: the phone was away and the local session restarted |
| 21:50 to 23:40 | Attached at 67% and charging, with another app in front until 22:27. At 80% and idle the skin temperature then rose to 37.4 °C (load average 2.4 to 2.7 from the phone's own apps, no kvid process) and fell slowly; the gate passed at 23:40 after a second timeout and restart |
| 23:40 to 23:55 | The 5,000-note baseline under the first rule. Valid |
| 23:45 to 23:55 | **Second cooling rule**, by the owner's decision ("use today's idle level"): thermal status 0 and skin at or below **36.4 °C**. Basis: the median of the 16 idle readings from 23:25 to 23:40 (35.85 °C) plus 0.5. Written to `pixel8-cooling-rule-2.txt` at 23:45:15 and in force from 23:55:37, when the baseline run had ended |
| 00:11 to 01:23 | 21 runs under the second rule: 5,000 notes with the optimized graph, the thread sweep, finalist blocks 4 to 6, the `m3` replacement and a repeat of the 5,000-note baseline. Starts were at 35.2 to 36.4 °C and status 0; waits 1 to 926 s |
| 01:25 | Rerun of the excluded one-thread run: excluded again (the owner picked the phone up). Not retried |
| 01:27 | XNNPACK rerun to keep the crash line. Not timed |

**Which rule each result was made under.** First rule (35.5 °C): the baseline and 256-token 5,000-note runs, the three edit-latency runs, and section 3's default-thread sweep run. Second rule (36.4 °C): everything else in this section. The start temperature of every run is in the gate file.

**The second rule is a mid-study amendment, not a replication of the first rule's conditions.** The owner authorized it, but its window and threshold were chosen after the cooling difficulty and some results were known, and after the phone had been seen to reach the first rule again. Its formula is also not the first rule's: that one was the top of the settled idle range (35.0 to 35.4 °C) plus 0.1, with no median archived as its basis. The rule file's remark "same formula as the first rule" is therefore wrong; the file is kept as it was written. Consequences: results from the two rules are kept apart below; small speed differences between runs made under different rules are confounded with the rule; the 5-minute misses are not in doubt (the baseline repeat under the warmer rule was the faster of the two).

### 5,000-note `IndexingMeasurementTest` (`mixed`)

| Configuration | Rule at start | Total | Notes per second (early, late) | ms per note | Peak PSS | Thermal |
|---|---|---|---|---|---|---|
| `bge-fp32@512` | first, 35.5 °C | 837.6 s (14.0 min) | 5.97 (8.33, 5.25) | 167.5 | 520,158 KiB (508 MiB) | status 1 in 47 of 54 samples, first after about 2 min; skin up to 41.9 °C |
| `bge-fp32@512`, repeat | second, 36.4 °C | 764.8 s (12.7 min) | 6.54 (8.22, 5.90) | 153.0 | 522,986 KiB (511 MiB) | status 1 in 46 of 50 samples, first after about 1 min; skin up to 40.0 °C |
| `bge-fp32-opt@512` | second, 36.4 °C | 737.3 s (12.3 min) | 6.78 (8.81, 6.19) | 147.5 | 436,146 KiB (426 MiB) | status 1 in 41 of 48 samples; skin up to 39.8 °C |
| `bge-fp32@256` | first, 35.5 °C | 521.0 s (8.7 min) | 9.60 (12.99, 8.52) | 104.2 | 330,370 KiB (323 MiB) | status 1 in 31 of 34 samples, first after about 1 min; skin up to 39.8 °C |

Thermal samples are 15 s apart. No run reached status 2. The excluded baseline run (18:58, first rule) took 770.9 s with a peak of 522,029 KiB.

- **None of the three tested configurations meets the 5-minute goal on the Pixel 8.** The goal needs 16.7 notes per second; the best run, at 256 tokens, averages 9.6 and takes 8.7 minutes. The baseline takes 12.7 to 14.0 minutes.
- **Sustained load throttles the phone within one to two minutes** and it stays at thermal status 1 to the end. Throughput falls 28% to 37% from the early to the late figure. The 200-note screening gave 74 ms per note for the baseline; the 5,000-note runs cost 153 to 168 ms. Throttling contributes to that gap but is not isolated as its only cause: the store run also writes vectors, calls the embedder in outer batches of 32, and has longer notes (130.3 against 124.2 tokens per note).
- **The two valid baseline runs differ by 9%** (837.6 and 764.8 s; the excluded one, 770.9 s). Differences of that size between configurations cannot be told apart from one run each. The optimized graph's 737.3 s is 0.88 and 0.96 of the two baseline runs; the 256-token limit's 521.0 s is 0.62 and 0.68.
- **The baseline exceeds the 500 MB limit** in all three of its runs (520,158 to 522,986 KiB: 533 to 536 MB, 508 to 511 MiB). The optimized graph peaks about 85 MiB lower (447 MB), the 256-token limit about 185 MiB lower (338 MB). Those two are below the limit in these sampled test-process runs on `mixed`; that is not yet a whole-app pass.

### Edit latency (`latency=on`: 200 notes embedded one per call, ms; first rule)

| Configuration | p50 | p95 | Up to 64 tokens (106 notes): p50, p95 | 65 to 128 (32 notes): p50, p95 | 129 to 256: p50, p95 | Over 256: p50, p95 | Peak PSS (KiB) |
|---|---|---|---|---|---|---|---|
| `bge-fp32@512` | 32.4 | 284.1 | 16.2, 34.3 | 50.9, 71.6 | 88.8, 127.7 (25 notes) | 217.4, 334.3 (37 notes) | 306,473 |
| `bge-fp32-opt@512` | 34.0 | 292.5 | 14.7, 45.6 | 48.6, 253.7 | 96.2, 216.0 (25 notes) | 235.8, 530.1 (37 notes) | 335,612 |
| `bge-fp32@256` | 34.3 | 148.4 | 16.0, 36.5 | 52.7, 127.8 | 135.3, 178.2 (62 notes) | none | 275,428 |

- **Embedding alone is under 200 ms at p95 with the 256-token limit** (148.4 ms, one run of 200 calls). **That does not yet validate the target**, which includes saving the vector; `latency=on` times only the embedding call (tokenizing included). At 512 tokens embedding alone already misses (284 and 293 ms) because of the 37 notes over 256 tokens; each bucket up to 256 tokens is under 200 ms with the plain graph (p95 34, 72 and 128 ms).
- **The optimized graph's run has outliers** in the middle buckets (p95 254 ms for 65 to 128 tokens against 72 ms for the plain graph) while its medians match. One run each; the cause is not known, and nothing was in front of the launcher.

### Threads and XNNPACK (`mixed`, 200 notes, one run per cell, ms per note)

| Configuration | default | `threads=1` | `threads=2` | `threads=4` | `ep=xnnpack` |
|---|---|---|---|---|---|
| `bge-fp32@512` | 74.1 (section 3) | excluded twice (238.6, 228.4) | 129.3 | 76.9 | **crash** |
| `bge-fp32@256` | 54.1 | 167.8 | 88.9 | 53.6 | **crash** |

- **Four threads equal the default** (within 4%).
- **Two threads were 64% to 74% slower than the default on this phone**, the opposite of the Pixel 4 XL, where they were 10% to 13% faster. These are single runs, and the 512-token default figure is from section 3 under the first rule (the block 4 to 6 defaults under the second rule, 74.8 to 76.8 ms, agree with it). The size of the difference is far beyond the run-to-run spread, but it is not a paired, repeated comparison. On this evidence no fixed thread count suits both phones.
- **One thread is three times slower** (the valid 256-token run; the two excluded 512-token runs agree).
- **XNNPACK crashes here too:** `Process crashed` for both runs, and `Fatal signal 11 (SIGSEGV), code 2 (SEGV_ACCERR)` in the rerun (`pixel8-xnnpack-crash/`). With the Pixel 4 XL that is six crashes in six attempts with the plain graph on two chips (two sweep runs and one diagnostic rerun per phone).

### Finalist blocks 4 to 6 (`mixed`, 200 notes, ms per note; second rule)

| Block (order) | `bge-fp32@512` | `bge-fp32-opt@512` | `bge-fp32@256` | opt / baseline | 256 / baseline |
|---|---|---|---|---|---|
| 4 (ACB) | 76.8 | 73.6 | 52.8 | 0.96 | 0.69 |
| 5 (BAC) | 74.8 | 72.8 | 53.5 | 0.97 | 0.72 |
| 6 (CBA) | 74.9 | 79.1 | 53.8 | 1.06 | 0.72 |

All nine runs stayed at status 0 and were undisturbed. Peaks: 436,177 to 456,814 KiB, 401,622 to 412,250 KiB and 295,719 to 323,588 KiB.

Over all six blocks (section 2's blocks 1 to 3 and these): medians 74.3, 73.2 and 53.2 ms.

- **No speed gain is demonstrated for the optimized graph on the Pixel 8.** Its ratio to the baseline is 0.96 to 1.10 over the six blocks; it is faster in three blocks and slower in three. Block-resampled 95% interval of the mean ratio: 1.01 (0.98 to 1.06), which includes no gain and excludes a 10% gain. It does use less memory.
- **The 256-token limit** takes 0.69 to 0.79 of the baseline's time in six of six blocks; interval of the mean ratio 0.73 (0.71 to 0.75), beyond the 10% threshold.
- Blocks 1 to 3 ran under the first rule and blocks 4 to 6 under the second, a day apart; each ratio is within one block, so the rule does not enter it.

### `m3` replacement

`scr-m3-b2r-bge-fp32-opt@512`, made for the run excluded in section 2: 361.0 ms per note, status 0 throughout (second rule, start 36.2 °C), peak 499,136 KiB, which is 511 MB and above the limit. **It is an unpaired observation.** The protocol asks for the whole affected block to be repeated; only this cell was, a day later and under the other rule (the block's baseline had started at 35.4 °C under the first), so it does not repair the block 2 pair.

## Not done

| The protocol asks for | State |
|---|---|
| Timing of `bge-int8@512`, `bge-int8-all@512`, `minilm-int8@256` | Skipped by rule on both phones: parity failed |
| `ep=xnnpack` on the best two | The plain graph crashed on both phones (`SIGSEGV` in ONNX Runtime), so no timing. The optimized graph was timed on the Pixel 4 XL only (228.6 ms per note) |
| Threads 1, 2, 4 on the best two | Pixel 8: run for `bge-fp32@512` and `bge-fp32@256`, as queued before the finalists were known, not for the optimized graph; `threads=1` at 512 tokens was run twice and excluded twice (phone in use). `mixed` only on both phones |
| Six paired blocks over two cooled sessions | Pixel 8: six blocks on `mixed`, three per session, but the sessions used different cooling rules; no same-rule replication. Finalist blocks on `m3` stop at screening's three on both phones |
| Repeat the whole affected block after an interruption | Not done: only the excluded `m3` cell was rerun (unpaired) |
| Edit latency including saving the vector; query and search p95 at 5,000 notes | Not run: `latency=on` times the embedding call only |
| Screen-off and background validation | Not run: every run had the screen on and the launcher in front |
| Whole-app, full-lifecycle PSS; a check of the sampler's overhead; batching and arena rechecked for the chosen graph | Not run |
| Fixed ambient conditions and brightness, energy cost, process ID per run | Not recorded |
| 5,000-note repeats | Pixel 8: two valid baseline runs, one each for the others. Pixel 4 XL: one each |
| Pixel 4 XL: `threads=2` at 5,000 notes and for edit latency | Not run; the gain is measured on 200-note runs only |
| Device timing of the uncertain candidates the frozen rule keeps (`bge-fp32@128`, `minilm-fp32@128`, `minilm-fp32@256`) | Not run: the owner's request listed six configurations. Completing the retained frontier, or amending its scope, is the owner's decision |
| Emulator harness check | Not run: the local API 35 emulator's disk is full |

The model files and the test APK are still on both phones under `/data/local/tmp/kvid-model-variants` (x86 fixtures) and `/data/local/tmp/kvid-arm-ref` (the arm64 fixtures for the three int8 variants). Nothing is waiting for either phone.
