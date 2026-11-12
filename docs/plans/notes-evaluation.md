# Proposal: a notes-style evaluation set

_Written November 11 by the cloud session, for the owner and for review by Codex before anything is built. It answers the open item in [mobile-indexing.md](mobile-indexing.md): Phase A measured quality on public proxies (SciFact, NFCorpus, FiQA). Phase B showed that the configurations near the indexing goal on a phone are exactly the ones whose quality is unsettled: all three are INCONCLUSIVE on the public sets, full FiQA included (November 12, "Quality of the retained candidates" in the plan). On the Pixel 4 XL, 5,000 notes took 3.3 minutes with `minilm-fp32@128`, 5.2 with `minilm-fp32@256` and 6.2 with `bge-fp32@128`, against 14.7 for the default. No default changes until this set exists and the frozen rule has been applied to it._

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
- **Formats it reads:** a folder of Markdown or text files (Obsidian, Bear, Apple Notes exports), and Google Keep Takeout JSON.
- **Who runs it and what is shared.** The owner runs it first, then any volunteer who agrees. Only the printed aggregates are shared and committed, under `docs/measurements/`, with the contributor's consent.
- **What it gives.** The real length distribution: how much text 128 and 256 tokens actually cut. It also drives the length mix of the synthetic set (step 2) and the reweighting of its results (step 4).

### Step 2: the test set

The set has two parts, because neither alone is enough (Codex: "a wholly synthetic set alone leaves the decision provisional").

- **Part S: synthetic, public and reproducible.** Committed under CC0, since the text is authored for the purpose.
  - **Collections:** at least six independently written collections of 500 to 800 notes each (about 4,000 notes). Each is one invented person's notes: a student, a parent running a household, a software engineer, a researcher, a small-business owner, and someone with health and travel admin.
  - **Note types:** short fragments, lists, meeting notes, journal entries, recipes, project decisions, contacts and names, dates and numbers, identifiers (order and ticket numbers), code and URLs, near-duplicates, and competing versions of one note (an old and a new plan).
  - **Lengths:** follow step 1's distribution.
- **Part R: real, private and small.** The owner's own notes, and any volunteer's, evaluated on the contributor's machine with the same harness.
  - Queries and judgments come from the contributor.
  - Notes, queries and judgments never leave the machine; only the aggregate metrics and their intervals are committed.
  - A target of at least 150 queries per contributor.
  - Part R checks Part S: a direction that disagrees between them is reported, and the decision waits.

### Queries

- **Answerable test queries:** 800 in Part S, plus 200 for development from separate collections. This is sized from Phase A:
  - Paired per-query differences in hybrid nDCG@10 against the baseline have a standard deviation of 0.07 (bge at 256 tokens) to 0.15 (MiniLM at 128).
  - A 95% interval of ±0.01 on one set therefore needs about 200 queries for bge at 256 tokens and 600 to 900 for the 128-token and MiniLM candidates.
  - With fewer, those comparisons end INCONCLUSIVE whenever the true loss is near the margin.
- **Kinds of query:**
  - exact-match intents: a name, a number, an identifier, a URL or a quoted phrase;
  - semantic intents: a paraphrase, a question whose answer is in the note, or a vague memory ("that restaurant Sam recommended");
  - typos;
  - negation ("trips that weren't for work");
  - queries with several relevant notes;
  - queries whose answer is in the newer of two competing versions.
- **Where the answer sits.** Every judged-relevant pair records the character span of the evidence in the stored text, which is title, blank line, body. The harness turns that span into token offsets under each model's tokenizer.
  - **Stratified sampling:** at least 25% of answerable queries have their evidence starting after token 128, at least 15% after token 256, and at least 5% after token 512. This makes the cost of a limit measurable even if real notes are short.
  - Step 4 reweights the results to the real distribution.
- **No-answer queries:** 100, plausible but answered by no note. They get a separate false-positive analysis: the top-1 hybrid and semantic scores compared with those of answerable queries. nDCG cannot assess them.

### Writing the notes and queries without favouring a model

- **Freeze the specification first.** The collections, note types, length mix, query kinds and answer strata are written and frozen before any model is run on the set.
- **Authors.** Humans are preferred. Where an LLM writes notes:
  - each collection gets its own author, from at least two model families;
  - authors get only the specification, never model names, rankings or a preferred outcome;
  - no example is rewritten after any model's results are seen.
- **Queries are written from memory cues, not from the note text.** For each target the note's author writes a cue: what the person remembers, in other words. A different author, human by preference, writes the query from the cue alone. Writing queries while reading the note would copy its words and favour lexical search.
- **Judging.**
  - Candidates are pooled: the top 20 of lexical search and of every candidate configuration (`bge-fp32` at 512, 256 and 128 tokens, `minilm-fp32` at 256 and 128).
  - The pool is shuffled, and the system that found each note is hidden.
  - Each pair is graded by two human judges: 0 not relevant, 1 partly, 2 answers the query.
  - Disagreements go to a third judge. Agreement (Cohen's kappa) is reported.
  - An LLM may pre-sort the pool for a judge's convenience, but never decides a grade, and never judges notes it wrote.
- **Splits.** Development and test are separated by collection before any run. Anything tunable, such as fusion settings, is touched only on development.
- **Scope.** English only. A decision from this set says nothing about notes in other languages.

### Step 3: tooling (on the code branch, after review)

- **Format.** The set uses `prepare_retrieval_eval.py`'s format (`corpus.jsonl`, `queries.jsonl`, `qrels.tsv`, `source.json`) plus:
  - `answers.jsonl`: query, note and evidence character span;
  - query fields for kind, intent (exact or semantic), collection and split;
  - `no_answer.jsonl`.
- **`RetrievalEvalTest`** adds, per query, the token offset where the evidence starts and ends under the run's tokenizer. It scores no-answer queries separately, recording their top-1 scores.
- **`analyze_retrieval_eval.py`** adds:
  - results by evidence position (within 128, 129 to 256, 257 to 512, beyond 512 tokens), by query kind and by intent;
  - candidate-against-baseline intervals per stratum (per set they exist since `6e41d47`, as Codex asked in its Phase A review);
  - the reweighted estimate (step 4).
- **`note_length_survey.py`** (step 1). It needs only the tokenizer and reads files locally.

### Step 4: decision

- **The rule.** The frozen Phase A rule applies unchanged to Part S's test split: hybrid nDCG@10, the paired query bootstrap and the 0.02 margin. The macro over collections weighs each collection equally.
- **Reported beside it, not replacing it:**
  - the rule's result after reweighting the strata to the real length and evidence-position distribution from step 1;
  - per-stratum deltas with intervals, the guardrail for an input limit: the loss on queries whose evidence lies beyond the limit, and how common such queries are in real notes;
  - exact-match queries on their own, as a guardrail that hybrid search does not lose them;
  - Part R's result for each contributor, with its interval;
  - the no-answer analysis.
- **What it can conclude.** A candidate can replace the default only if it passes on Part S. Part R must not contradict it, and its exact-match guardrail must not lose more than 0.02. Otherwise the default stays, and the report says which evidence is missing.

## Effort and order

1. **Owner:** run the survey on their own notes (half an hour, once the script exists). Decide whether volunteers are asked.
2. **Cloud session:** write the survey script, the specification for Part S, and the harness and analysis changes. Codex reviews the specification before it is frozen.
3. **Authoring:** notes, cues and queries. This is the largest part: several thousand notes and about 1,100 queries, by humans or by LLM authors under the rules above.
4. **Judging:** about 1,100 queries × a pool of roughly 40 to 60 notes, by two judges. This is the second largest part, and needs people the owner chooses.
5. **Runs:** the five configurations on the JVM. Quality does not depend on the platform for fp32; int8 is out under ADR 0002. About an hour for 4,000 notes.

Owner decisions this proposal needs:
- whether Part R is done, and with whose notes;
- who judges;
- whether LLM authors are acceptable for Part S's notes (queries and judgments stay human either way);
- whether the sizes above, or smaller ones that accept more INCONCLUSIVE results, are the target.
