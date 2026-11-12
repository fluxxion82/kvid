**No P1 findings. Findings 1, 2 and 8 are resolved in the measurement paths. The checker still accepts invalid records, and the notes proposal needs further revisions before freezing.**

Code references below are at `31f1e0c`; document references are on `planning`.

1. **[P2] Verify token-bucket percentiles — [check_measure_lines.py:102](kvid-embed-onnx/scripts/check_measure_lines.py:102)**

   `edit_tokens` is ignored after its length check, and only overall percentiles are recomputed. I supplied two 20-token edits whose values were both `1.0`, alongside `edit_total_le64_p95_ms=999.0`; the checker exited **0, OK**. Validate token values, bucket counts and every printed bucket percentile. These fields are explicitly required by the run plan.

2. **[P2] Complete judgments needed for the ideal ranking — [notes-evaluation.md:101](docs/plans/notes-evaluation.md:101)**

   Top-10 results plus known targets are not “everything nDCG@10 needs”: relevant notes outside those results can change the ideal DCG denominator. The passes do not require judging the complete declared top-20 pool, and the random depth audit can miss such notes. In a constructed two-query example, completing those judgments changed the macro delta from **+0.3495 to −0.0405**. Judge the declared pool completely and require unresolved relevance to be unable to change the decision before stopping.

3. **[P2] Require observations in every stratum used for reweighting — [notes-evaluation.md:81](docs/plans/notes-evaluation.md:81)**

   The cumulative quotas permit counts **600, 0, 0, 200** across the four disjoint strata: all three quotas pass. Yet two required late-evidence guardrails have no observations, and Part R or the uniform-evidence scenario can assign them positive weight. Require minimum support in each needed stratum, and explicitly make missing support a hold rather than dropping or renormalizing it.

4. **[P3] Check metadata on every sample part — [check_measure_lines.py:68](kvid-embed-onnx/scripts/check_measure_lines.py:68)**

   Completeness uses only the first part’s denominator and `n`. A series containing `part=1/2` and `part=2/3`, with matching total value count, exited **0** despite missing the advertised third part. Require consistent metadata across parts and reject conflicting duplicate part numbers.

5. **[P3] Reject non-finite phase times — [check_measure_lines.py:131](kvid-embed-onnx/scripts/check_measure_lines.py:131)**

   `float("nan")` defeats the adjacent decrease checks. I passed `open:0.0,index:nan,edits:-2.0,close:3.0,end:4.0`; the checker exited **0**. Require finite, nonnegative times before checking order.

6. **[P3] Allow the alternating mixture for odd edit counts — [check_measure_lines.py:99](kvid-embed-onnx/scripts/check_measure_lines.py:99)**

   The harness accepts any positive edit count, but the checker requires exactly half put and half update. With five warm-ups, `edits=1` legitimately records one update and fails validation. Check the expected alternating counts, or explicitly restrict the harness to even counts. The planned `edits=200` campaign is unaffected.

7. **[P3] Make the open mark exactly zero — [IndexingMeasurementTest.kt:127](kvid-embed-onnx/src/commonTest/kotlin/com/kvid/embed/IndexingMeasurementTest.kt:127)**

   Both tests initialize the open mark with `now()` after creating the monotonic origin and obtaining wall time. A delay of at least 50 ms between those operations makes it print something other than `open:0.0`, failing the documented gate. Store the origin mark as `0.0`; the same issue occurs in tuning at line 114.

8. **[P3] Define evidence boundaries using the actual input budget — [notes-evaluation.md:78](docs/plans/notes-evaluation.md:78)**

   Specify how evidence offsets account for special tokens. `BertTokenizer.encode(..., 128)` retains **126 content pieces**, reserving `[CLS]` and `[SEP]`. A raw evidence end at content piece 127 therefore fits the written “within 128” stratum but is truncated. Define membership by whether the entire evidence survives encoding at that limit.

9. **[P3] Reconcile the overlap audit with the export allowlist — [notes-evaluation.md:48](docs/plans/notes-evaluation.md:48)**

   The allowlist excludes overlap statistics, while line 93 requires reporting Part R’s overlap distribution by query kind. Add explicitly permitted aggregate audit statistics, or state that the Part R audit remains local. Per-query overlap records should remain excluded.

The remaining answers are:

- **Phase attribution and atomics:** sound for these call sites. Comparing the packed state before and after a sample detects phase changes, including changing away and back. The counter wraps only after \(2^{28}\) changes; these runs make a handful. Explicit readings are synchronous in the same coroutine that changes phases. `reading()` would not independently protect against a separate concurrent phase writer, but neither measurement uses it that way. The CAS maximum update safely handles sampling and explicit readings on different threads.
- **Lifecycle maximum:** guaranteed for every numeric memory reading printed by a successfully completed run. Every reading raises the lifecycle accumulator, and publication follows cancellation and joining. A phase cannot print `none` when its explicit endpoint read succeeds. It can still do so if reads return `null`, such as an iOS `task_info` failure, and no valid sample covers that phase.
- **Queries before edits:** fixes the store-size discrepancy without changing which operations `edit_total_` times. It changes cache and thermal conditions: queries warm search pages and embedding allocations and consume energy before edits. Five edit warm-ups do not establish thermal equivalence. Report these as continuous warm measurements; the assertion that queries are short relative to the pause is not evidence of cooling.
- **Williams square:** correct. The 200 counted queries use each row 50 times; every mode occupies each position 50 times, and every ordered predecessor pair occurs 50 times **within queries**. Transitions between successive queries are not balanced by that property.
- **Raw samples:** the construction preserves every counted observation and its alignment with tokens and edit kind. Nearest-rank selection commutes with the monotone one-decimal rounding, so the corresponding printed percentiles remain reproducible. Use the total series directly; summing rounded write and save values can differ by 0.1 ms. A conservative 200-value timing line was **2,314 bytes**, comfortably below approximately 4 KB.
- **Thermal alignment:** the wall-clock anchor plus monotonic offsets is adequate for 15-second thermal sampling with stable clocks and a measured phone–host offset. It is not exact: phase times are rounded to 0.1 s, clock acquisition is not simultaneous, and later wall-clock adjustments are not represented. The revised run-plan comments otherwise correctly add sample capture, checker failures as harness faults, pooled nearest-rank percentiles, operation-specific results and thermal status at the reordered phase starts.
- **Previous document findings:** **4, 6, 7 and 9 are resolved**. The veto/hold/clear criteria are operational, the frozen primary and lexical point requirement remain stated, and Part R is required for adoption. Findings **3 and 5 are substantially addressed but remain incomplete** because of the support and judging issues above. The 11 rankings, known-target inclusion, blinding and pooled-recall label are correct.

I inspected the complete five-file diff, surrounding call sites and tests, the revised documents and draft run plan. I ran checker counterexamples through its `main()` function, confirmed both old smoke logs fail, recomputed Williams balance and line-size bounds, checked all six archive digests, and reran the analyzer’s 10,000-bootstrap analysis in memory over **11,439 unique selected records**. Its report tables reproduce, including all three INCONCLUSIVE verdicts. The sizing calculations and both revised MiniLM diagnostic intervals also reproduce.

The full qrels/`trec_eval` reproduction was not run: prepared datasets were absent. The in-memory analysis reused archived cross-check metadata only for the report header. I did not run Gradle or device tests. `git diff --check` passed; both worktrees remained unchanged.