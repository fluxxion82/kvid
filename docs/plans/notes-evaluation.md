# Proposal: a notes-style evaluation set

_Written November 11 by the cloud session, for the owner and for review by Codex before anything is built. It answers the open item in [mobile-indexing.md](mobile-indexing.md): Phase A measured quality on public proxies (SciFact, NFCorpus, FiQA). Phase B showed that the configurations near the indexing goal on a phone are exactly the ones whose quality is unsettled: all three are INCONCLUSIVE on the public sets, full FiQA included (November 12, "Quality of the retained candidates" in the plan). On the Pixel 4 XL, 5,000 notes took 3.3 minutes with `minilm-fp32@128`, 5.2 with `minilm-fp32@256` and 6.2 with `bge-fp32@128`, against 14.7 for the default. No default changes until this set exists and the frozen rule has been applied to it._

_Revised November 12 after Codex's review ([codex-review-2026-11-12.md](../measurements/2026-11-12-answer5/codex-review-2026-11-12.md), findings 3 to 6, and its cost suggestions). Each changed part is marked "changed after Codex's review". Revised again November 13 after its second review ([codex-review-2-2026-11-12.md](../measurements/2026-11-12-answer5/codex-review-2-2026-11-12.md), findings 2, 3, 8 and 9), marked "changed after Codex's second review"._

## What it must decide

1. **Input limit.** Whether 128 or 256 tokens loses retrieval quality on notes, the case the public sets cannot answer.
   - The loss depends on how long real notes are, and on where in a note the thing a person searches for sits.
   - Phase A's proxies are long by note standards. 256 tokens truncates 71% of SciFact and 79% of NFCorpus documents. Phase B's synthetic `mixed` corpus, 5 to 600 words with an arbitrary log-uniform spread, has 31% of notes over 128 tokens.
   - Neither says anything about notes.
2. **Model.** Whether MiniLM's loss on the proxies (SciFact −0.03, inconclusive overall) holds on notes, where its speed would meet the indexing goal (`minilm-fp32@128`: 3.3 minutes for 5,000 notes on the Pixel 4 XL).
3. **Exact-match safety.** Whether hybrid search keeps names, identifiers, numbers and URLs findable under each candidate. Lexical search should carry these; the test checks that fusion does not bury them.

## Design

### Step 1: real note lengths, without collecting notes

- **The survey script.** A script, `kvid-embed-onnx/scripts/note_length_survey.py`, reads a local export of a person's notes and prints aggregate statistics only:
  - token counts under the shared bge/MiniLM vocabulary, as a histogram in 16-token bins;
  - the share of notes over 64, 128, 256 and 512 tokens;
  - the share of all tokens beyond those limits;
  - the number of notes by their title's presence;
  - nothing else: no text, titles, dates or file names.
- **Formats it reads:** first only the owner's export format; other importers (a folder of Markdown or text files from Obsidian, Bear or Apple Notes, Google Keep Takeout JSON) only when a volunteer needs one. _Changed after Codex's review, to cut cost._
- **Who runs it and what is shared.** The owner runs it first, then any volunteer who agrees. Only the printed aggregates are shared and committed, under `docs/measurements/`, with the contributor's consent.
- **What it gives.** The real length distribution: how much text 128 and 256 tokens actually cut. It drives the length mix of the synthetic set (step 2).
- **What it does not give** _(changed after Codex's review)_: where in a note the thing a person searches for sits.
  - Two people with the same length histogram can search titles, or facts near the ends of notes. Those imply different weights and different truncation losses.
  - Evidence positions come only from Part R, where each query's evidence is located on the contributor's machine and only a histogram leaves it.
  - Without Part R, step 4 reports sensitivity scenarios, not a measured population estimate.

### Step 2: the test set

The set has two parts, because neither alone is enough (Codex: "a wholly synthetic set alone leaves the decision provisional").

- **Part S: synthetic, public and reproducible.** Committed under CC0, since the text is authored for the purpose.
  - **Collections:** at least six independently written collections of 500 to 800 notes each (about 4,000 notes). Each is one invented person's notes: a student, a parent running a household, a software engineer, a researcher, a small-business owner, and someone with health and travel admin.
  - **Note types:** short fragments, lists, meeting notes, journal entries, recipes, project decisions, contacts and names, dates and numbers, identifiers (order and ticket numbers), code and URLs, near-duplicates, and competing versions of one note (an old and a new plan).
  - **Lengths:** follow step 1's distribution.
- **Part R: real, private and small. Required for a default change** _(changed after Codex's review; it was optional)_. The owner's own notes, and any volunteer's, evaluated on the contributor's machine with the same harness.
  - **Queries are elicited naturally:** the contributor writes them from what they actually look for, before looking at the note. Judgments come from the contributor.
  - **Evidence positions:** for each query the contributor marks the evidence span in its target note. The harness turns that span into the query's evidence stratum (below), which gives the evidence-position histogram that reweighting needs.
  - **Privacy:**
    - Notes, queries, spans and judgments never leave the machine.
    - Judging is local: no remote service or LLM sees any of it.
    - Only an explicit allowlist of aggregates is exported: the query count, the histogram by evidence stratum, the overall and per-stratum candidate-against-baseline deltas with their intervals, and kappa where two judges are used.
    - _Changed after Codex's second review:_ the allowlist also has the overlap audit's histogram, by query kind, of each query's overlap share in tenths. Per-query overlap values stay on the machine.
  - **Size:** a target of at least 150 queries per contributor.
  - **Without Part R,** a Part S result is provisional and does not change the default (step 4).

### Queries

- **Answerable test queries:** 800 in Part S, plus 200 for development from separate collections. **800 is a budget choice, not a size that settles every comparison** _(changed after Codex's review: the earlier spread figures, 0.07 to 0.15, were too low)_.

  The table below comes from Phase A's archived per-query records. Each cell gives the standard deviation of paired hybrid nDCG@10 differences against the baseline, and in brackets the queries needed for a 95% half-width of ±0.01 on one set, n = (1.96 s / 0.01)²:

  | Candidate | SciFact | NFCorpus | full FiQA |
  |---|---|---|---|
  | `bge-fp32@256` | 0.092 (325) | 0.051 (99) | 0.071 (191) |
  | `bge-fp32@128` | 0.155 (922) | 0.069 (182) | 0.138 (728) |
  | `minilm-fp32@256` | 0.161 (989) | 0.097 (362) | 0.157 (943) |
  | `minilm-fp32@128` | 0.186 (1,321) | 0.093 (335) | 0.179 (1,237) |

  - Notes may spread differently; these are the only estimates there are.
  - At 800 test queries, the 128-token and MiniLM candidates can end INCONCLUSIVE when their true loss is near the margin. That outcome is accepted; it is not a reason to change the rule.
  - The guardrail subsets are smaller. At the minimum quotas there are about 200 queries beyond token 128, 120 beyond 256 and 40 beyond 512, and the exact-match queries are a share of the 800. Their intervals will be wide; step 4 says how a wide guardrail interval is treated.
  - Reweighting (step 4) widens intervals further when it gives small strata more weight.
  - The primary size and the guardrail sizes are separate owner decisions (below).
- **Kinds of query:**
  - exact-match intents: a name, a number, an identifier, a URL or a quoted phrase;
  - semantic intents: a paraphrase, a question whose answer is in the note, or a vague memory ("that restaurant Sam recommended");
  - typos;
  - negation ("trips that weren't for work");
  - queries with several relevant notes;
  - queries whose answer is in the newer of two competing versions.
- **Where the answer sits** _(changed after Codex's review: the strata are fixed per query)_. Every query has one target note, chosen by its author (Part S) or by the contributor (Part R), with the character span of its evidence in the stored text (title, blank line, body). Notes judged relevant later do not change this.
  - **A query's stratum** is set by whether the target's whole evidence survives encoding of the stored text at each input limit, under the shared bge/MiniLM tokenizer. _Changed after Codex's second review:_ this said "the token offset where the evidence ends", which ignores the two special tokens. `BertTokenizer.encode(text, L)` keeps L − 2 content pieces plus `[CLS]` and `[SEP]`, so 126 at a limit of 128. The strata:
    - **within 128:** every content piece of the evidence is kept at a limit of 128;
    - **129 to 256:** kept whole at 256 but not at 128;
    - **257 to 512:** kept whole at 512 but not at 256;
    - **beyond 512:** not kept whole at 512.
  - An answer that a limit cuts counts as beyond that limit, because the model sees only part of it.
  - The strata are disjoint and fixed before any run.
  - **Minimum support per stratum in Part S's test split** _(changed after Codex's second review: the cumulative quotas allowed strata with no queries, such as 600, 0, 0, 200)_. Each stratum needs at least 10% of the answerable test queries within 128, 10% in 129 to 256, 10% in 257 to 512, and 5% beyond 512: 80, 80, 80 and 40 of 800.
    - These minimums imply the earlier quotas (at least 25% beyond token 128, 15% beyond 256, 5% beyond 512), and make the cost of each limit measurable even if real notes are short.
    - The set is not frozen until it meets them.
  - **Missing support is a hold** _(added after Codex's second review)_. A stratum can end up below its minimum, for instance when judging finds that targets do not answer their queries. If the decision gives that stratum weight (as a guardrail, through Part R's histogram or through a scenario), every verdict and estimate that uses it is hold. A stratum is never dropped, and the weights are never renormalized without it.
  - Step 4 reweights only with Part R's measured histogram; otherwise it reports scenarios.
- **No-answer queries** _(deferred after Codex's review, to cut cost)_: 100 plausible queries that no note answers, with a false-positive analysis of top-1 scores. They are not part of the decision, and are built only if the owner wants them.

### Writing the notes and queries without favouring a model

- **Freeze the specification first.** The collections, note types, length mix, query kinds and answer strata are written and frozen before any model is run on the set.
- **Authors.** Humans are preferred. Where an LLM writes notes:
  - each collection gets its own author, from at least two model families;
  - authors get only the specification, never model names, rankings or a preferred outcome;
  - no example is rewritten after any model's results are seen.
- **Queries are written from memory cues, not from the note text.** For each target the note's author writes a cue: what the person remembers, in other words. A different author, human by preference, writes the query from the cue alone. Writing queries while reading the note would copy its words and favour lexical search.
- **Overlap audit** _(added after Codex's review)_. Cue-based queries are still conditioned on a target the author chose. The analysis reports the share of each query's content words that occur in its target note, by query kind, for Part S and for Part R's naturally elicited queries. A clearly different distribution is reported beside the result. For Part R only the histogram leaves the machine (the allowlist above).
- **Judging** _(changed after Codex's review)_.
  - **The pool:**
    - the top 20 of lexical search;
    - for each of the five configurations (`bge-fp32` at 512, 256 and 128 tokens, `minilm-fp32` at 256 and 128), the top 20 of both its semantic and its hybrid ranking. That makes 11 rankings in all.
    - The author's target, and any note the author marked relevant, are always added, even if no system retrieves them.
  - **Blinding:** the pool is shuffled, and the system that found each note is hidden.
  - **Passes** _(changed after Codex's second review)_. The first pass used to judge only every ranking's top 10, called "everything nDCG@10 needs". That was wrong: relevant notes outside those results change the ideal ranking that nDCG@10 divides by.
    1. **The whole declared pool:** every note in the 11 top-20s, and every known target.
    2. **A blinded depth audit:** a random sample of ranks 21 to 100 of each ranking, judged the same way. It estimates how many relevant notes the pool misses per query.
    3. **Stopping rule.**
       - A note outside every evaluated top 20 changes nDCG@10 only through the ideal ranking.
       - The decision of step 4 is therefore recomputed with each query's ideal ranking completed by k missed relevant notes at grade 2 (the largest effect), for every k from none up to the audit's upper 95% bound.
       - If any verdict differs from the verdict on the judgments as they are, the pool is deepened (the top 50 of every ranking, then the audit again) and the comparison repeated.
       - Judging stops only when unresolved relevance cannot change the decision.
  - **Recall@100** is reported as pooled recall and labelled as such: a top-20 pool cannot establish complete recall.
  - **Grades:** each pair is graded by two human judges: 0 not relevant, 1 partly, 2 answers the query. Disagreements go to a third judge. Agreement (Cohen's kappa) is reported.
  - **No LLM sorts or grades the pool.** LLM pre-sorting was dropped after Codex's review, for cost and for Part R's privacy.
- **Splits.** Development and test are separated by collection before any run. Anything tunable, such as fusion settings, is touched only on development.
- **Scope.** English only. A decision from this set says nothing about notes in other languages.

### Step 3: tooling (on the code branch, after review)

_Changed after Codex's review._
- **Format.** The set uses `prepare_retrieval_eval.py`'s format (`corpus.jsonl`, `queries.jsonl`, `qrels.tsv`, `source.json`) plus:
  - `answers.jsonl`: per query, its target note, the evidence character span and other notes the author marked relevant;
  - query fields for kind, intent (exact or semantic), collection and split.
- **`RetrievalEvalTest`** records, per query, the token offsets of the target's evidence and whether it survives encoding whole at 128, 256 and 512, which gives the query's stratum. _Changed after Codex's second review._
- **`analyze_retrieval_eval.py`** adds:
  - results by stratum, query kind and intent;
  - candidate-against-baseline intervals per stratum (per set they exist since `6e41d47`, as Codex asked in its Phase A review);
  - the veto, hold and clear verdicts of step 4, with the support check per stratum;
  - the stopping-rule comparison of the judging passes;
  - the reweighted estimate or the three scenarios;
  - pooled recall@100 labelled as such;
  - the overlap audit.
- **A pool builder** writes each query's blinded judging pool (the 11 top-20s and the known targets) and the depth-audit sample of ranks 21 to 100.
- **`note_length_survey.py`** (step 1) needs only the tokenizer and reads files locally. Part R's harness exports only the aggregate allowlist above.

### Step 4: decision

_Rewritten after Codex's review: every guardrail now has a comparator, a criterion and an action._

All comparisons are candidate against `bge-fp32@512`, on hybrid nDCG@10 unless stated, paired by query. Intervals are 95%, from 10,000 query bootstraps (seed 20261108). The margin is 0.02.

1. **Primary: the frozen rule, unchanged.** It applies to Part S's test split, as a macro over collections with equal weights:
   - PASS if the lower bound is at least −0.02;
   - FAIL if the upper bound is below −0.02;
   - INCONCLUSIVE otherwise.

   Only PASS continues.
2. **The frozen guardrails, unchanged:**
   - per collection, hybrid ≥ lexical as a point requirement, with its interval reported (an interval that includes zero is not evidence of improvement);
   - semantic nDCG@10 and pooled recall@100 reported;
   - any collection losing more than 0.02 flagged for the owner.
3. **Added guardrails.** Each is judged in the same three ways:
   - **Veto** if the upper bound is below −0.02: a demonstrated loss beyond the margin. The candidate cannot be adopted.
   - **Hold** if the point loss exceeds 0.02 without a veto. The candidate is not adoptable until more queries resolve it.
   - **Clear** otherwise. Clear is not a claim of noninferiority; a wide interval is reported as wide.

   They apply to:
   - Part S's exact-match intent queries;
   - each Part S stratum beyond the candidate's input limit: 129 to 256, 257 to 512 and beyond 512 for a 128-token candidate, and the last two for a 256-token one. A stratum below its minimum support is hold (changed after Codex's second review);
   - Part R, overall, for each contributor.
4. **Reweighted estimate.**
   - **With Part R:** Part S's per-stratum deltas are reweighted to Part R's evidence-position histogram (contributors weighted equally), with a bootstrap interval, and judged as in 3.
   - **Without Part R:** three scenarios are reported instead:
     - all evidence within the first 128 tokens;
     - Part S's own mix;
     - evidence spread uniformly over each note, with lengths from step 1.

     The candidate must be clear under all three.
   - _Added after Codex's second review:_ a stratum that has weight but less than its minimum support makes the estimate hold.
5. **What it can conclude.**
   - **Adopt:** primary PASS, no veto or hold under 3 or 4, and Part R present for at least the owner. Only this can change the default, and only by the owner's decision.
   - **Provisional pass:** the same, but without Part R. It is reported, and the default does not change.
   - **Otherwise** the default stays, and the report names the missing or contrary evidence.
   - The absence of a statistical contradiction is not evidence of noninferiority. Part R is a guardrail with the criteria above, not a second primary test.

## Effort and order

1. **Owner:** run the survey on their own notes (half an hour, once the script exists), and Part R (about 150 queries with their evidence spans and judgments; several hours). Decide whether volunteers are asked.
2. **Cloud session:** write the survey script, the specification for Part S, and the harness and analysis changes. Codex reviews the specification before it is frozen.
3. **Authoring:** notes, cues and queries. This is the largest part: several thousand notes and about 1,100 queries, by humans or by LLM authors under the rules above.
4. **Judging:** about 1,000 queries. Each needs the whole pool judged, the union of 11 top-20s and the known targets, likely 60 to 120 notes (to be measured on the development split), plus the depth audit and any deepening the stopping rule asks for. Two judges grade each pair. This is now the largest part, and needs people the owner chooses. _Changed after Codex's second review; it said a first pass of 30 to 60 top-10 notes._
5. **Runs:** the five configurations on the JVM. Quality does not depend on the platform for fp32; int8 is out under ADR 0002. About an hour for 4,000 notes.

Owner decisions this proposal needs _(changed after Codex's review)_:
- whether Part R is done, and with whose notes. Without it no default can change; the best possible outcome is a provisional pass.
- who judges;
- whether LLM authors are acceptable for Part S's notes (queries and judgments stay human either way);
- the sizes, separately for the primary comparison and for the guardrail subsets, knowing that 800 test queries can leave the 128-token and MiniLM candidates INCONCLUSIVE;
- whether the deferred no-answer set is built;
- the pool depth _(added after Codex's second review)_: the top 20 of each ranking as above, or the top 10, which is cheaper. Either pool is judged completely, and the stopping rule protects the decision either way. With top 10, pooled recall@100 means less and the depth audit starts at rank 11.
