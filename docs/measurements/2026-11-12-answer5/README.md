# Answer-5 measurements: smoke checks, Codex's review, run plan (November 12, 2026; no timed run yet)

For "Measurements added for the targets in answer 5" in [docs/plans/mobile-indexing.md](../../plans/mobile-indexing.md). Local session. Code: `phase-5-mobile-indexing` at `6e41d47` (first smoke check) and `31f1e0c` (second), clean tree each time. **Nothing here is a measurement result.** The first smoke check (`6e41d47`) found two reporting faults and Codex found the cause of one of them. The cloud session fixed them at `31f1e0c`; the second smoke check, below, passes on that commit. The timed runs still wait for a Pixel 8 idle baseline, the frozen plan and the owner's go-ahead.

| File | Content |
|---|---|
| `smoke-pixel4xl-index.txt` | Raw `[kvid-index-measure]` line of the smoke run (300 notes, 20 edits, 20 queries, 5 s pause) |
| `smoke-pixel4xl-tune.txt` | Raw `[kvid-embedding-tune]` line of one tuning run |
| `smoke2-pixel4xl-index.txt`, `smoke2-pixel4xl-tune.txt` | Second smoke check, APK at `31f1e0c`: the `[kvid-index-measure]` line with its nine `[kvid-index-samples]` lines, and the `[kvid-embedding-tune]` line, as `logcat` printed them |
| `smoke2-checker.txt` | Output of `check_measure_lines.py` (at `31f1e0c`) on the second smoke files, and on the first ones for comparison |
| `codex-review-2026-11-12.md`, `codex-review-prompt.txt` | Codex's review of the measurement code, the retained candidates' quality and the notes-evaluation proposal, and the prompt it was given. Local paths are shortened to repository paths; nothing else is changed |
| `plan-draft.txt` | The run plan for the timed runs, a draft, revised for `31f1e0c`: the Pixel 8's first cooling value and the owner's go-ahead are not filled in |

## First smoke check (`6e41d47`)

- **APK:** `kvid-embed-onnx-androidTest.apk`, built at `6e41d47` with `-Pkvid.deviceTestAbi=arm64-v8a`, sha256 `56731d10…02b9bf`. The model files already on the phones are reused.
- **Device:** Pixel 4 XL, November 12, 02:04 to 02:06 UTC. The Pixel 8 was tried first and unplugged about a minute into the run; no line was received from it.
- **Not timed:** no cooling rule, the tuning run started straight after the index run. The times in these lines say nothing.

| Check from the brief | Result |
|---|---|
| `edit_write_`, `edit_save_`, `edit_total_` p50 and p95, overall and by token length | Present |
| `query_embed_`, `query_lexical_`, `query_similar_`, `query_hybrid_` p50 and p95 | Present |
| `phase_start_s` | Present: `index:1.0,pause:53.7,edits:58.7,queries:62.4,close:64.8,end:64.9` |
| `mem_before_kb`, `mem_open_peak_kb`, `mem_index_peak_kb`, `mem_pause_peak_kb`, `mem_edits_peak_kb`, `mem_queries_peak_kb`, `mem_lifecycle_peak_kb` | Present, PSS |
| `mem_close_peak_kb` on the index line | **Missing.** The close phase lasted 0.1 s (`close:64.8,end:64.9`), no 100 ms sample fell in it, and `summary()` leaves out a phase without a sample |
| `mem_lifecycle_peak_kb >= mem_peak_kb` on the index line | Holds (425,214 against 400,740) |
| Tuning line: `mem_open_peak_kb`, `mem_embed_peak_kb`, `mem_close_peak_kb` | Present (222,459; 421,099; 427,516) |
| Tuning line: lifecycle peak against `mem_peak_kb` | **`mem_lifecycle_peak_kb` (427,516) is below `mem_peak_kb` (427,532).** `mem_peak_kb` includes the explicit reading after embedding; the lifecycle peak is the largest sampled value only. This is Codex's second finding, seen on a device |

Further observations from the lines, for the cloud session:

- On the tuning line the largest sample is attributed to the close phase (427,516 KiB) although the embedding phase's own peak is 421,099 KiB and the reading taken after embedding is 427,532 KiB. Memory is at its highest when embedding ends, so a sample taken around the phase change lands in "close". Codex's first finding describes how.
- The tuning line still says `mem_peak=sampled-100ms`; the index line says `sampled-100ms-from-before-open`.
- `phase_start_s` counts from just before the open, and the line carries no wall-clock time. To line the phases up with the driver's thermal readings the driver will keep the logcat time of the result line and count back from `end`. A wall-clock time at the open, printed in the line, would be simpler and exact.

## Codex's review (summary; full text in `codex-review-2026-11-12.md`)

`gpt-6.1-sol`, high effort, read-only, on `planning` at `185f5fc` and the code diff `586cbde..6e41d47`. **No P1.** The local session read the sampler code for findings 1 and 2 and confirms both; the others are not independently checked.

| # | Finding | Where |
|---|---|---|
| 1 | P2: a memory read that spans a phase change is attributed to the following phase | `OnnxEmbedderTuningTest.kt:202` |
| 2 | P2: the explicit readings (before, after open, after index or embed, after close) do not enter the lifecycle peak, so it can be below `mem_peak_kb` | `OnnxEmbedderTuningTest.kt:212` |
| 3 | P2: reweighting needs where the evidence sits in a note for real search intents, not note lengths | `notes-evaluation.md:94` |
| 4 | P2: the adoption guardrails are not operational; Part R is optional although a wholly synthetic result stays provisional | `notes-evaluation.md:99` |
| 5 | P2: the judgment pool does not name its retrieval modes or always include the intended target; a top-20 pool cannot establish recall@100 | `notes-evaluation.md:69` |
| 6 | P2: the sizing understates the spread; ±0.01 for MiniLM at 128 tokens needs about 1,200 to 1,300 queries, so 800 is a budget choice | `notes-evaluation.md:45` |
| 7 | P2: "the model rather than the limit" for MiniLM's SciFact loss is too strong; an added truncation loss is not excluded | `mobile-indexing.md:562` |
| 8 | P3: queries run against 5,103 live notes after the edits while the line prints `notes=5000` | `IndexingMeasurementTest.kt:154` |
| 9 | P3: the reproduction command does not locate the analyzer | `2026-11-12-retained-quality/README.md:41` |

Found sound: `edit_total_` measures the write, tokenizing, embedding and saving of one note at batch 1 (a pooled p95 over a half-and-half mix of new notes and updates); the query measurements use the real search paths but a synthetic vocabulary; the warm-ups and the nearest-rank p95 at 200 calls; the separate random stream for the per-set intervals. Recomputed from 11,439 per-query records: the three retained candidates are INCONCLUSIVE with the published intervals, and all digests match.

## Second smoke check (`31f1e0c`)

- **APK:** `kvid-embed-onnx-androidTest.apk`, built at `31f1e0c` (clean tree) with `-Pkvid.deviceTestAbi=arm64-v8a`, sha256 `e6963588ac128ca883739cdcd32b150986d6a474d782255a16e16d3b3b6748d5`. The model files already on the phone are reused.
- **Device:** Pixel 4 XL, November 12, 20:12 to 20:14 UTC. The Pixel 8 was not attached.
- **Runs:** `KVID_INDEX_MEASURE=corpus=mixed+notes=300+model=bge-fp32+edits=20+queries=20+pause=5`, then `KVID_EMBEDDING_TUNE=corpus=mixed+notes=200+model=bge-fp32` (the configuration of the first smoke check). Both instrumentation runs ended `OK (1 test)`.
- **Not timed:** no cooling rule, the tuning run started straight after the index run. The times in these lines say nothing.
- **Checker:** `python3 -I kvid-embed-onnx/scripts/check_measure_lines.py` prints `OK` for the index line and for the tuning line (`smoke2-checker.txt`). The same script fails both lines of the first smoke check, for the faults found then.

| Fault of the first smoke check | Second smoke check |
|---|---|
| `mem_close_peak_kb` missing on the index line | Present: 104,106 |
| Tuning line: lifecycle peak (427,516) below `mem_peak_kb` (427,532) | `mem_lifecycle_peak_kb` = `mem_peak_kb` = `mem_embed_peak_kb` = 430,376 |
| Different `mem_peak` labels on the two lines | Both `sampled-100ms+readings-from-before-open` |
| No wall-clock time | `open_epoch_ms` on both lines and on every samples line. Index: 1794514343958 (20:12:23.958 UTC), 3 s after the driver started the run |
| Queries after the edits, on more notes than printed | `phase_start_s=open:0.0,index:1.0,pause:51.4,queries:56.4,edits:59.0,close:62.4,end:62.5`, `query_store_notes=300`, `query_order=williams4` |

Observations for the cloud session; none of them fails a documented invariant:

- **Sampling rate.** `mem_samples` is 491 over 62.5 s on the index line and 292 over 36.3 s on the tuning line: about 8 per second, not 10. The 100 ms is the delay between reads; with the read itself the period comes to about 125 ms on this phone. "Roughly ten per second" in the plan section should read "about eight on the Pixel 4 XL".
- **`mem_boundary_samples=0` on both lines.** No read spanned a phase change in either run, so the device has not exercised that path.
- **The close phase's peak is the reading after the close.** `mem_close_peak_kb` equals `mem_after_close_kb` on both lines (104,106 and 92,064 KiB). Close lasted 0.1 s and under 0.05 s, no sample fell in it, and the only reading credited to it is the one taken once the memory was released. The field therefore does not show the memory held while the store was closing; that is covered by the preceding phase's peak and by the lifecycle peak. This matches the documented meaning but is easy to misread.
- **Clocks.** The phone's clock was 0.47 s behind the host's when measured after the runs (`adb shell date +%s%3N` between two host readings 68 ms apart). The run plan logs thermal readings with the phone's clock, so the offset does not enter the alignment.
- **Line length.** The longest line is 2,121 characters (the result line). A samples line with 200 values of the widest series is estimated at about 1,500 characters; none was produced here.

## State

- **The second smoke check passes.** Nothing was timed.
- **Not done: the Pixel 8 idle baseline.** The phone was not attached on November 12 when the smoke check ran. The cooling value is measured at the start of the first session, as the plan says.
- **The run plan is still a draft** (`plan-draft.txt`), revised for `31f1e0c` and the cloud session's comments. It becomes `plan.txt` once the Pixel 8's first threshold is measured and Codex's review of `31f1e0c` is in, and the timed runs start only after the owner's go-ahead.
