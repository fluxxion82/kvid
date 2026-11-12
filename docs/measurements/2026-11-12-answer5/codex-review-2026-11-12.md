**Fix the measurement reporting before the long device campaign. The retained-candidate verdicts reproduce; the notes proposal needs revisions before it can support a default change.**

Code references below are at `6e41d47`; document references are in the planning worktree.

1. **[P2] Attribute memory reads only to a stable phase — [OnnxEmbedderTuningTest.kt:202](kvid-embed-onnx/src/commonTest/kotlin/com/kvid/embed/OnnxEmbedderTuningTest.kt:202)**

   `PhaseSampler` reads memory before loading `current`. If the workload changes phase during `memoryKb()`, the reading is assigned to the following phase. Atomic variables prevent a data race but do not fix this attribution error. Capture the phase on both sides of the read; treat a crossing read as a boundary/lifecycle sample. `PhaseSamplerTest` uses instantaneous readings and does not exercise this case.

2. **[P2] Include explicit endpoint readings in the lifecycle maximum — [OnnxEmbedderTuningTest.kt:212](kvid-embed-onnx/src/commonTest/kotlin/com/kvid/embed/OnnxEmbedderTuningTest.kt:212)**

   `before` only enables sampling; its value never enters the peaks. Neither do the explicit after-open, after-index/embed or after-close readings. Consequently, `mem_lifecycle_peak_kb` can be lower than another memory value already printed on the same line—including `mem_peak_kb`. Indexing also constructs its summary before cancelling and joining the sampler. Record endpoint readings in the same accumulator and stop/join before publishing its final snapshot.

3. **[P2] Obtain evidence-position weights before promising population reweighting — [notes-evaluation.md:94](docs/plans/notes-evaluation.md:94)**

   Step 1 measures note lengths, not where relevant evidence occurs for actual search intents. Identical length histograms can describe users who search titles or users who search facts near the ends of notes. Those imply different weights and different truncation losses. Collect a local, query-conditioned evidence-position histogram in Part R, or report sensitivity scenarios instead of a supposedly measured population estimate. Also freeze disjoint **query-level** strata, including how multiple relevant notes and evidence crossing a limit are handled.

4. **[P2] Make the adoption guardrails executable before freezing them — [notes-evaluation.md:99](docs/plans/notes-evaluation.md:99)**

   “Part R must not contradict,” the exact-match loss limit and the per-stratum guardrail lack operational definitions: comparator, point-versus-interval criterion and resulting action. Part R is also optional at line 110, although the parent plan says wholly synthetic evidence leaves the decision provisional. Explicitly retain the frozen per-set hybrid ≥ lexical point requirement, semantic/recall reporting and loss flags; define the added vetoes; and state that absent real validation cannot authorize a non-provisional default change. Lack of statistical contradiction is not evidence of noninferiority.

5. **[P2] Seed the judgment pool and name its retrieval modes — [notes-evaluation.md:69](docs/plans/notes-evaluation.md:69)**

   “Every candidate configuration” does not say whether it contributes dense, hybrid or both rankings. Both are evaluated, so both need coverage. Always include the author’s intended target and other known relevant notes, even when no system retrieves them; otherwise universally missed answers can disappear from the judgments. A top-20 pool also cannot establish recall@100 completeness. Add a blinded depth/residual audit and label pooled recall accordingly.

6. **[P2] Correct the sizing claim and account for the actual design — [notes-evaluation.md:45](docs/plans/notes-evaluation.md:45)**

   The archived spread exceeds the stated range. For MiniLM@128, paired hybrid differences have SD **0.1855 on SciFact** and **0.1794 on full FiQA**. The simple calculation \(n=(1.96s/0.01)^2\) gives approximately **1,322** and **1,237** queries respectively. Even bge@256’s SciFact SD, 0.0920, implies about **326**, rather than 200. Eight hundred total test queries also leave small per-collection and late-evidence groups; reweighting further changes precision. Present 800 as a budget choice with possible INCONCLUSIVE outcomes, and size the primary and critical guardrails separately.

7. **[P2] Qualify the claim that MiniLM’s SciFact loss is independent of the limit — [mobile-indexing.md:562](docs/plans/mobile-indexing.md:562)**

   There is evidence for a model-associated loss: at the same 256-token limit, MiniLM − bge hybrid nDCG@10 is **−0.0285**, with a diagnostic paired interval **[−0.0472, −0.0105]**. But MiniLM@128 − MiniLM@256 is **−0.0062 [−0.0215, +0.0093]**. That does not exclude a meaningful additional truncation loss. Say the model explains a substantial observed difference; the evidence does not establish “rather than the limit.”

8. **[P3] Preserve or report the query-phase store size — [IndexingMeasurementTest.kt:154](kvid-embed-onnx/src/commonTest/kotlin/com/kvid/embed/IndexingMeasurementTest.kt:154)**

   The documented combined command reaches queries with **5,103 live notes**: 205 edits including warm-ups produce 103 inserts and 102 updates. The line still prints `notes=5000`. Run queries before mutations, restore the corpus, or print the actual live count and describe this workload accurately.

9. **[P3] Locate and pin the analyzer in the reproduction command — [README.md:41](docs/measurements/2026-11-12-retained-quality/README.md:41)**

   Earlier commands assume execution inside the archive directory, but `analyze_retrieval_eval.py` does not exist there. Identify the analyzer at `6e41d47`, show its path and working-directory setup, and make the prepared-qrels prerequisite explicit. The selection itself is correct for the supplied archives.

The measurement boundaries otherwise answer several parts of A correctly:

- **`edit_total_` measures the intended operations.** `maxDocuments=1` makes the actual embedding call batch 1 despite `outer=32`. It includes the document write and `indexVectors`’ document read, tokenization, embedding, vector validation/encoding, persistence and commit. It describes the declared synthetic 50/50 insertion/update mixture; pooled p95 does not separately establish p95 for each operation.
- **The query tests exercise real search paths.** Twenty results is correct, ANY matches the quality protocol, and semantic/hybrid calls include their own query encoding and exact vector scan. Random words from the small, shared vocabulary are a synthetic workload with unusually common lexical terms. They are useful path measurements, but insufficient evidence for representative personal-note search latency.
- **The thermal and cache conditions need careful interpretation.** `pause=300` is outside the indexing total, correctly, but elapsed time does not prove cooling. The pause precedes edits only; queries then follow 205 edits. Within each query, hybrid always follows standalone encoding, lexical search and semantic search of the same query. Preserve thermal traces and describe these as continuous warm measurements; isolate or counterbalance modes if using them to establish foreground latency.
- **Five excluded warm-ups are reasonable for a warm target**, provided cold-open results remain separate. Nearest-rank p95 at 200 calls is rank 190, a standard descriptive estimator—not a confidence bound. Near 200 ms, retain samples and use repeated runs/uncertainty; the smaller length buckets have much less tail precision.
- Sampling still gives a lower bound. Indexing has no delayed-idle reading and stops before store closure. The stated whole-app, background and energy caveats are appropriate.

I recomputed the retained results from **11,439 unique selected per-query records**, using 10,000 paired bootstraps and the recorded streams:

| Candidate | Hybrid macro delta, 95% CI | Verdict |
|---|---:|---|
| bge-fp32@128 | −0.0141 [−0.0214, −0.0067] | INCONCLUSIVE |
| MiniLM-fp32@128 | −0.0157 [−0.0247, −0.0066] | INCONCLUSIVE |
| MiniLM-fp32@256 | −0.0122 [−0.0204, −0.0040] | INCONCLUSIVE |

All retained macro, per-set and hybrid-versus-lexical intervals match the report within floating-point precision. Phase A’s complete primary table reproduces at its published precision. All six compressed/uncompressed digests match, the recorded model hashes match `variants.json`, and new versus archived full-FiQA lexical records are identical. Full-FiQA records also contain an identical duplicate lexical block; deduplication handles it correctly.

The separate `seed + 1` generator leaves the macro rule and its random stream unchanged. Candidate-order changes explain MiniLM@256’s small interval movement. The semantic losses remain guardrail evidence; they do not create a new primary FAIL verdict. Likewise, an interval crossing zero does not violate the frozen **point** hybrid ≥ lexical requirement.

For bge@128, the FiQA length pattern reproduces: hybrid losses are **−0.0442** for longest-relevant-document lengths 257–512 and **−0.0576** above 512, versus small gains in the shorter groups. This supports an input-limit effect concentrated in those groups. It does **not** locate the missing evidence after token 128: document length is not answer position, and truncation also changes competing documents.

The five proposed configurations can decide the model/limit trade-off for the frozen notes workload once the design gaps are resolved. Independent authors, collection splits and model blinding help, but cue-based queries remain conditioned on an author-selected target. Audit cue/query overlap and include naturally elicited Part R queries. Part R’s privacy requirement is sound; implementation must keep judging local, exclude remote LLM pre-sorting and export only an explicit aggregate allowlist.

To reduce cost without weakening the central comparison:

- Start with the owner’s export format; defer additional importers.
- Drop LLM pre-sorting and defer no-answer score calibration.
- Reduce bespoke authoring of unrelated background notes while preserving realistic hard negatives.
- Judge in frozen passes: all evaluated top-10 results and known targets first, followed by a blinded depth audit; expand whenever remaining judgments could change the decision.

No files were modified. I did not run Gradle/device tests or repeat the qrels/`trec_eval` metric cross-check; the recomputation used the archived per-query scores.