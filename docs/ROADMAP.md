# kvid roadmap

_Revised October 4, 2026 (CI green at `a997698`). This merges the owner's Codex-reviewed revision of the roadmap with the state of the `phase-0-baseline` branch. Unchecked items are planned work, not implementation claims. Where something was verified by running it, the text says so; everything else comes from source inspection._

## Direction

kvid becomes a general-purpose, embedded, portable searchable document store for Kotlin Multiplatform applications on JVM, Android, and iOS. Documents, metadata, and eventually optional vector indexes travel together in a portable file. Notes, journals, offline documentation, transcripts, and message history are the initial use cases. AI integrations come last and remain optional.

The first release has one concrete goal: **save documents, reopen safely, and search offline on all three platforms.**

QR codes in video become an optional archive/export feature. Repairing every existing video implementation is not a prerequisite for the new store. memvid informs the design (it solved the same problems and then abandoned the QR approach), but neither its API nor its internal format determines kvid's architecture.

## Current baseline (branch `phase-0-baseline`)

What is on the branch, and how each item was checked:

| Item | State | How verified |
|---|---|---|
| Gradle wrapper jar committed (was excluded by `*.jar`) | done | fresh `./gradlew` run in a clean container |
| Kotlin 2.5.0-Beta1, Gradle 9.8.0, JDK 17 toolchain | done | JVM compile and tests executed |
| AGP 9.4.0 via `com.android.kotlin.multiplatform.library`; Android target configured with `android {}` inside `kotlin {}`; device tests in `src/androidDeviceTest` | done | CI Ubuntu job (`./gradlew build`: Android compile, host tests, lint, JVM tests, examples) green on three consecutive runs |
| iOS targets unchanged (iosX64, iosArm64, iosSimulatorArm64, static framework) | done | CI macOS job: Kotlin/Native 2.5.0-Beta1 compiles the iOS source sets and tests; 88 simulator tests run. Five `IosQRCodeGeneratorTest` rendering cases fail with `Failed to create CGImage from QR code` on CI and the local Xcode 26.2 simulator. CI explicitly excludes them with `KVID_SKIP_IOS_QR_RENDERING_TESTS=true`; they run by default locally (see Appendix A) |
| kotlinx-coroutines 1.11.0, kotlinx-serialization 1.11.0 with the compiler plugin applied, androidx.test 1.7.0 / 1.3.0 | done | JVM compile |
| Removed: Kotlin dev repo, `mavenLocal()`, four unrelated repos, `kotlinx-benchmark`, `maven-publish`, `appcompat`, forced Kotlin resolution strategy, no-op build-cache block | done | JVM configure |
| `kvid-examples` compiles (three duplicate `main` functions removed) | done | compile executed |
| ffmpeg-dependent JVM tests fail instead of skip when `CI` is set | done | ran with ffmpeg present |
| Tautological tests removed or replaced with behavioral ones (see Milestone 0) | done | JVM suite executed: 106 tests, 0 failures, 0 skipped |
| `CancellationException` is rethrown ahead of every `catch (e: Exception)` in main source sets | done | JVM compile; Android and iOS source sets compile only in CI |
| `MemoryStore.exportIndex` uses kotlinx.serialization instead of hand-built JSON | done | round-trip test with quotes, backslashes, tabs and newlines |
| `HnswVectorIndex.search` sorts candidates by distance before taking top-k (results were returned in visit order) | done | new recall@10 test against the exact index |
| GitHub Actions: Ubuntu job (ffmpeg, `./gradlew build`, examples compile) and macOS job (iOS simulator tests) | done | runs on pushes to `main` and `phase-*` branches, on pull requests, and manually |
| README prerequisites and status describe the real per-platform state | done | review |

Still true, from source inspection, and unchanged by Phase 0:

- `MemoryStore` retains chunks and metadata in memory; saving a vector index alone does not persist a searchable document store.
- `SimpleEmbedding` is a positional character-code demonstration, not a semantic model.
- The iOS encoder writes a custom container rather than MP4; iOS compression is raw DEFLATE while JVM and Android use gzip; the iOS QR decoder cannot build an image from raw pixels (an `@Ignore`d round-trip test now documents this in `iosTest`).
- The Android encoder does not drain output buffers during encoding, and there is no Android QR generator.

The full per-platform defect list is in Appendix A as an investigation checklist. The Android and iOS items are from reading the code, not from device execution.

## Milestone 0: establish a reproducible baseline

- [x] Make a fresh checkout build using a committed Gradle wrapper, including its JAR.
- [x] Choose the Kotlin/Gradle/AGP combination (2.5.0-Beta1 / 9.8.0 / 9.4.0). JVM verified; Android and iOS verification is the first CI run on this branch. The Kotlin Gradle plugin compatibility table is not yet published for 2.5.0-Beta1 (2.4.20 lists AGP 9.3.1 and Gradle 9.7.0 as the newest fully supported). If CI rejects the combination, pin `kotlin = "2.4.20"` in `gradle/libs.versions.toml` until 2.5.0 ships.
- [x] Apply the serialization compiler plugin where generated serializers are needed.
- [x] Remove unused repositories, plugins, and dependencies after confirming usage.
- [x] Add CI for JVM tests, Android host tests, examples compilation, and iOS simulator tests.
- [x] Android emulator job (`android-store`: API 35, x86_64, KVM) runs the common store suite on a device and fails when the instrumentation run executes zero tests, because the device-test task prints no counts and passes on an empty run. First green run 37216476571 at `8b47f77`; counted run 37217532833 at `f678eb1` (44 tests). Host tests alone do not establish mobile support.
- [x] Make missing prerequisites visible in test reports; required CI coverage fails when its prerequisites are absent.
- [x] Correct README capabilities and prerequisites to match verified behavior.
- [x] Replace tautological tests with behavioral tests where coverage is needed; remove assertions that establish no behavior. Removed: data-class construction and enum-count "integration" tests, `100 < 10000`, byte-in-0..255, `assertNotNull` on non-null `Result`, a print-only benchmark that ran as a unit test, the whole iOS video decoder test file (16 tests that never called the decoder). Added: HNSW recall against exact search, HNSW top-k equals exact top-k on a small set, exact sentence-boundary chunking, corrupted compressed payload fails, vectors and search results survive save/load, export JSON round trip, an ignored iOS QR round trip that documents the known decoder failure.
- [x] Preserve coroutine cancellation through error handling. `Result` versus typed exceptions remains a separate API decision (open decision 5).
- [x] Every test task prints failed and skipped events with full exception messages and causes, so CI logs are diagnosable without the report artifacts.
- [x] First fully green CI run: commit `a997698`, run 37171137198 (https://github.com/fluxxion82/kvid/actions/runs/37171137198). Ubuntu green on all four runs; macOS green with the five Core Image rendering tests skipped and one decoder round-trip test ignored (82 passed, 6 skipped).
- [x] Reproduce the Core Image failure locally: all five rendering tests fail on Xcode 26.2 with both default and software-renderer contexts. Remove unconditional `@Ignore` annotations; CI opts out using `KVID_SKIP_IOS_QR_RENDERING_TESTS=true`, excludes only these five tests, and prints the reason. A default local run exposes the defect.
- [ ] Repair or replace the iOS renderer and remove the CI opt-out once the rendering tests pass. This remains a known platform defect, not a verified CI-only limitation.
- [x] Fix `MemoryEncoder.buildVideo` lifecycle cleanup: cancellation and returned initialization/frame/finalization failures release resources and reset state; cleanup errors do not replace the original failure. Common regression tests exercise failure and successful retry.

**Exit criterion:** reproducible build instructions and CI results for the actual branch, with platform limitations stated accurately.

## Milestone 1: choose storage architecture and define its contract

**Status: architecture selected; validation partially complete.** [ADR 0001](adr/0001-storage-engine.md) accepts SQLite via androidx.sqlite 2.7.1 for implementation. The [persistence contract](PERSISTENCE_CONTRACT.md) is a revised proposal, not an implemented guarantee. The corrected spike passes 16 tests each on JVM and the iOS simulator and compiles Android main.

- [x] SQLite 3.50.1 and FTS5 observed on JVM and iOS; Android dependency compiles.
- [x] Default DELETE-mode connections explicitly use synchronous EXTRA and foreign keys. WAL/NORMAL is insufficient for the proposed power-loss boundary; public WAL support is deferred.
- [x] Primitive coverage: committed reopen, orderly-close rollback, savepoints, writer contention, real read-only handles, active-reader WAL cleanup failure, and committed-only snapshot with a staged writer.
- [x] Invalid-header NOTADB and injected FULL codes verified. FTS insert/update/delete/rebuild consistency and float raw-bit encoding verified.
- [x] Synthetic 5,000-document timings and file size observed. Historical CI run 37176438762: JVM ingest 349 ms, 50 queries 232 ms, second-open/count 3 ms; iOS ingest 1,181 ms, queries 118 ms, second-open/count 5 ms; file 2.5 MiB. These are warm second-connection timings, not cold reopen or release budgets.
- [x] Proposed contract specifies scoped transactions without manual commit, cancellation cleanup, tombstone visibility, same-commit ordering, retention floor, current-only FTS, and cursors expiring on writes.
- [ ] Android runtime/native loading and the common spike suite on a device or emulator.
- [ ] Actual process-kill recovery, failed sync, and broader corruption fixtures in Milestone 2. Orderly close is not a kill simulation.
- [ ] Production exception-code translation: the pinned AndroidX exception has no structured result-code property.
- [ ] Atomic snapshot publication and platform file/directory sync in Milestone 2; VACUUM INTO alone does not supply these.
- [ ] Cold-open, peak-memory and representative mobile workloads; APK/framework size with the sample app. Revisit footprint above roughly 3 MB per ABI.

The spike targets minSdk 23 and omits iosX64 because the pinned artifact has no Intel simulator variant; applying those changes to core is Milestone 2 work. Proposed exceptions, bounds, migration compatibility, compression and encryption reservations still require implementation tests.

**Exit criterion:** the architecture decision and proposed semantics are reviewable. Platform validation is complete only after Android runtime evidence; production durability and publication remain explicit subsequent milestone gates.

## Milestone 2: durable document store

**Status: implemented, review corrections applied, full CI green at `f678eb1`; release validation incomplete** merged into `main` at `15877cd` on October 4, 2026 (CI run 37217532833: JVM and Android host tests, the API 35 emulator job with 44 store tests, and the iOS simulator all green; the Android host store suite is skipped by design because the bundled natives cannot load there). `kvid-core` now carries the store in package `com.kvid.store`; the storage spike is deleted. The planning documents stay on this branch; the code branch has none.

What is implemented, against the persistence contract:

- [x] `kvid-core` minSdk 23 and `iosX64` removed (ADR 0001).
- [x] `Kvid.create`, `open`, `openReadOnly`, `suspend close()`: `application_id`, `kvid_meta` format/schema versions, version checks that refuse older schemas until a real migration is provided, `quick_check` after an unclean close, newer major or minor refused for every open; every writable connection sets and verifies `foreign_keys=ON`, `journal_mode=DELETE`, `synchronous=EXTRA` (contract 3, 4).
- [x] Scoped `transaction { }` with a `Transaction` receiver: no manual commit; savepoint nesting; reentry through the store, use after the block, and use from another coroutine are rejected; cancellation is checked before commit; rollback and cleanup run in a non-cancellable context on the connection dispatcher with cleanup failures suppressed (contract 3).
- [x] Documents and versions: UUIDv7 or caller ids, `AUTOINCREMENT` version ids, `(seq, versionId)` visibility with tombstones, `get(asOfSeq)`, `history`, update creates a superseding version, delete creates a tombstone, several updates in one transaction (contract 6).
- [x] Current-content projection (`current`) kept in the same transaction as the version write, external-content FTS5 maintained by triggers, `rebuildIndex()` from authoritative versions; `find` searches current live versions only, filters by tag and event-time range, returns higher-is-better scores and snippets (contract 7).
- [x] `list` ordered by `(eventTime desc, versionId desc)` with keyset cursors; `find` with offset cursors; every cursor carries commit sequence, history floor and an exact bounded query fingerprint and expires with `CursorExpired` after any committed write or compaction (contract 7).
- [x] Bounds checked before writes (body, title, metadata, uri, tags) and stored body/metadata/title/URI/tag lengths validated before materialising large text; hard library caps above configurable limits (contract 8).
- [x] `KvidException` subclasses with stable codes; driver errors translated by a version-pinned message parser with unknown codes mapped to `Io` (contract 5, see deviations).
- [x] `snapshot()`: `VACUUM INTO` a temporary file next to the destination through a fresh read-only connection, validation of the copy, durability through a write transaction under `synchronous=EXTRA`, atomic publication, parent-directory fsync via a small `expect`/`actual` (JVM `FileChannel.force`, Android `Os.fsync`, iOS `fsync`); existing destinations refused; temp files removed on failure (contract 2).
- [x] `verify()`: `integrity_check`, `foreign_key_check`, current-version and projection invariants, sequence bound, predecessor references, FTS `integrity-check` with `rank=1` on writable handles (contract 4).
- [x] `vacuum(KEEP_ALL | KEEP_LATEST)`: retention applied transactionally with an explicit history floor (`asOf` below it raises `HistoryUnavailable`, deletion markers retained), then `VACUUM` (contract 6).
- [x] JSON Lines export of every retained version; import of live current versions in one transaction, preserving ids, event times, titles, metadata, uris and tags.
- [x] Tests (common, run on JVM and the iOS simulator; skipped on the Android host runtime where the bundled natives cannot load): reopen round trip, copying a closed file, foreign and newer files, unclean-close marker, commit/rollback/cancellation/savepoints/reentry/escape, versions and as-of visibility, same-transaction ordering, caller ids and unique uri, list ordering and cursor expiry, current-only search and find cursors, projection drift and rebuild, page damage, one writable handle per path plus read-only handles, snapshots, bounds, retention floor, JSON Lines, error translation, UUIDv7, float32 blob codec.
- [x] JVM-only: a child JVM is SIGKILLed while holding an open write transaction; the parent reopens, sees exactly the committed documents, `verify()` passes, and the hot journal is gone.
- [x] Android common store suite: local Pixel 9 arm64 emulator (Android 15/API 35) at `8b47f77` with zero skips, and the CI API 35 x86_64 emulator job at `f678eb1` (run 37217532833: 44 run, 0 failed, 0 skipped).
- [ ] Minimum-supported API 23 runtime validation and representative physical-device coverage.
- [ ] A committed cross-platform fixture file read on all three targets (today each platform round-trips its own closed file).
- [ ] Disk-full and failed-sync injection through the store API (the spike exercised `max_page_count` on a raw connection; the store has no test hook yet).
- [ ] Process-kill recovery on iOS and Android (the JVM test cannot run in the simulator).
- [ ] Cold-open, peak-memory and binary-size measurements with the sample app (Milestone 4).
- [ ] Kotlin warns that `expect object` is Beta; replace `FileSync`/`PlatformInfo` objects with top-level `expect` functions and values.

Deviations from, or decisions within, the contract for review:

1. `update` creates a version with exactly the supplied fields; nothing is inherited from the previous version. Simple and explicit; an app that edits a body must resend title, tags, uri.
2. Writable handles are coordinated per canonical path by refusing a second writable open in the same process with `Locked`, rather than sharing one connection owner between handles.
3. Read-only `verify()` cannot run the FTS write command; `VerifyReport.unchecked` explicitly lists this missing coverage. `ok` applies only to performed checks. Snapshot validation uses writable full invariant verification.
4. Error translation still parses the driver's message (pinned to 2.7.1 and tested); no supported adapter exists in androidx.sqlite. Unknown codes map to `Io`.
5. `find` pagination is offset-based under a cursor that expires on any write; keyset paging on `(score, versionId)` was not worth it while ranking is still Milestone 3's subject.
6. `importJsonLines` imports live current versions only; it does not reconstruct history.
7. The connection dispatcher is `ioDispatcher.limitedParallelism(1)` per store (an `expect val` because `Dispatchers.IO` is not visible from common code).

**Exit criterion:** committed documents and metadata survive close/reopen on every target; interrupted writes preserve the last committed state; detected corruption surfaces explicitly, and verify checks the full database and application invariants. Close/reopen and the common suite are verified on JVM/iOS; see the review evidence below for Android. Portable fixtures, store-level fault injection and mobile process interruption remain open validation items.

### Phase 2 review corrections

The review reproduced partial writes after a caught unique-URI failure, leaked transactions after cancellation during BEGIN, hidden outer-store reentry during nested transactions, projection-content drift missed by verify, title-length bounds missed during reads, and query cursor hash collisions. Corrected code isolates individual writes with savepoints, covers transaction/resource acquisition cancellation, retains ancestor transaction markers, groups multi-statement reads/exports in a read snapshot, compares projected content with authoritative versions, reports unchecked verification coverage, validates snapshot invariants, bounds stored title/URI/tag fields and compares exact query identities. Cleanup failures make a session rollback-only; ambiguous failed commits invalidate the connection and release its reservation. All vacuum/rebuild operations expire cursors. File creation reserves its path atomically; writable open cannot recreate a missing file, and unsupported format checks precede mutating connection configuration.

Publication never replaces an existing destination on JVM/iOS (atomic hard-link creation followed by removal of the temporary link). Android app SELinux rejects hard links: an exclusive per-destination directory reservation coordinates kvid publishers across processes, followed by existence check and atomic rename. Android requires an app-owned destination directory without non-kvid writers; this is an explicit limitation, not a general filesystem no-replace primitive. A crash can leave `.NAME.kvid-publish-lock`; inspect destination and temporary output before manually removing that reservation. Unsupported publication/filesystem behavior must fail rather than silently weaken the guarantee. Failed directory sync after publication raises an uncertain-durability error and retains the published destination for inspection.

**Reviewed code tip:** `8b47f77` on `phase-2-document-store`. Final local focused results: JVM 45 tests (including subprocess-kill recovery), iOS simulator 44, and Android Pixel 9 arm64/API 35 44; zero failures or skips in these store suites. Full CI at this corrected tip passed (run 37216476571).

**Second review (Claude, October 4):** no objections to `8b47f77`; the corrections match the contract, and the snapshot temporary file is created beside its destination so hard-link publication stays on one filesystem. One evidence gap: the emulator job's log printed no test counts, the device-test task passes when the instrumentation run finds no tests, and the report artifact lives on blob storage that the cloud session cannot fetch. `f678eb1` adds a CI step that counts the JUnit results after the emulator run and fails on zero. CI run 37217532833 at `f678eb1` is green: Android emulator 44 run, 0 failed, 0 skipped; JVM/Android host and iOS simulator green. Reviewed tip `f678eb1` merged into `main` at `15877cd`; the merged phase branch was deleted.

The Android device compilation now explicitly includes commonTest via `sourceSetTreeName = "test"`; the original configuration did not. A local Pixel 9 arm64 emulator running Android 15/API 35 passed the reviewed suite; final test totals and code tip are recorded below. Host tests returning without execution are not Android store runtime evidence.

A trigger-induced SQLite automatic rollback regression verifies that caught failures cannot continue writing outside the intended transaction. The failed-commit regression uses a rollback trigger, not a failed-fsync injection: the latter remains a required gate. Schema 0 is refused rather than silently bumped to 1, since no historical migration has been implemented.

### Milestone 3 order

1. [x] Establish the query API first: plain text by default, tokenize/quote literal terms with explicit AND/OR semantics; reserve raw FTS5 syntax for an opt-in advanced mode. Decide empty/punctuation-only and Unicode behavior, bound input and cursor lengths, and translate all invalid-query cases consistently. Done on `phase-3-search`; see Milestone 3.
2. [x] Validate tag/date/URI filters, their combinations, tie order and cursor expiry; add representative BM25 ranking cases and an independent small reference calculation. Done with a synthetic corpus; a real notes corpus remains.
3. [ ] Measure realistic corpora and mobile resources before setting release budgets. Synthetic-corpus timings and file size are recorded on all three CI platforms; peak memory and real corpora remain.

The Phase 2 corrected branch's CI passed at `f678eb1` and it was merged into `main` at `15877cd`; the integration gate for Phase 3 is satisfied. Keep portable fixtures and disk-full/failed-sync injection visible as Phase 2 acceptance gates rather than treating green happy-path tests as complete durability validation.

## Milestone 3: useful offline full-text search

**Status: query API, filters, Unicode handling and ranking checks merged into `main` at `c299d31` on October 4, 2026** (reviewed tip `e2d0c34`; CI run 37224621383 green: JVM and Android host tests, API 35 emulator with 59 device tests and zero failures or skips, iOS simulator). Chunked search, peak memory and a real-world corpus remain. Semantics are in the persistence contract, section 7.

- [x] Lexical search with BM25 ranking, deterministic tie-breaking `(score desc, versionId desc)`, and documented normalization and tokenization. Tokenizer decision: FTS5 `unicode61` defaults (case folding, diacritic removal, no stemming). Titles, bodies and tags are stored in NFC; queries and tag filters are normalized the same way.
- [x] Plain-text queries by default: whitespace-separated pieces quoted for FTS5, so operators, quotes and column filters are literal; pieces without letters or digits ignored; an empty plain query matches nothing; `MatchMode.ALL` (default) or `ANY`; 128-term bound. Raw FTS5 is opt-in through `QuerySyntax.FTS5`, and rejected expressions raise the new `InvalidQuery` (`KV_INVALID_QUERY`).
- [x] Tag, date and uri-prefix filters, shared by `list` and `find`, combine with AND: every listed tag is required; the uri prefix is literal and case-sensitive. Cursors bind to syntax, match mode, compiled query, page size and every filter.
- [x] Only current live versions are searchable (update, delete, rebuild and JVM process-kill recovery covered).
- [ ] Define chunk-to-document result aggregation before exposing chunked search. Not started; no chunked search is exposed.
- [x] Ranking checked against an independent BM25 calculation (the `fts5_aux.c` formula) for a six-document corpus, including an exact tie. [ ] A representative real notes corpus is still missing.
- [x] Rebuilding the index reproduces ids, order and scores; search after JVM process-kill recovery matches the recovered documents.
- [ ] Measurements: synthetic timings and file size are recorded below on all three CI platforms. Peak memory, cold open after reboot, and real corpora are not measured.

**Codex review corrections (`e2d0c34`, pending final CI).** Five regression tests reproduced and now cover literal uri prefixes containing NUL, tag-filter length validation, more than 1,000 combined tags, pagination with a maximum-length escaped uri prefix, and NUL in plain queries. Prefix comparison uses UTF-8 bytes; one counted tag subquery avoids SQLite expression-depth limits; the combined serialized query/filter budget is 256 KiB and the cursor cap accommodates every accepted identity. Planning stays on `planning`; no planning files enter the code branch. Local corrected-tip verification: JVM 60 tests, iOS simulator 59 tests, and API 35/arm64 Pixel 9 emulator 59 device tests; zero failures and zero skips on all three. An independent review of the corrections found no further issues. GitHub CI run 37224621383 passed at `e2d0c34` (Android emulator: 59 device tests, 0 failed, 0 skipped). Claude re-reviewed the corrections with no objections and merged the tip into `main` at `c299d31`; its tree is identical to `e2d0c34`. The remote `phase-3-search` branch must be deleted outside the cloud session, which cannot delete remote branches.

**Projection change (schema 2, commit `2d762a8`).** The first measurement put 5,000 notes with about 9.7 MB of text in a 33.5 MB file. A local reproduction attributed 42% of it to the `current` projection, which stored a second copy of every live title and body for FTS5's external content. The projection now holds only ids, event time and uri, and FTS5 reads text through a view over the immutable `versions` rows. The file dropped to 20.1 MB on every platform. Schema 1 existed only on unreleased builds and is refused as an older schema. Review this change on its own; it can be dropped without affecting the query work.

The change exposed a fragile corruption test. The bundled SQLite runs with auto-vacuum, so page 2 is a pointer map, and the test's fixed damage range hit live entries only while the file had more than about 40 pages. The test now damages the root page of `versions`. The Android device-test step also now lists failing tests by name, since its report artifact is not readable from the cloud session.

**Measurements.** Synthetic notes corpus: 5,000 notes of 40 to 300 words from a 3,000-word Zipf-like vocabulary, ingested in 10 transactions, then 100 autocommit puts. Times are milliseconds, median/p95 where several runs were timed, from single CI runs on shared runners. JVM timings varied by up to 60% between runs on the same schema, and one iOS runner reported 3 processors, so these are observations, not budgets.

| Platform | Schema | Ingest | Put | Find ALL | Find ANY | ANY + tag | List 50 | Reopen + find | File |
|---|---|---|---|---|---|---|---|---|---|
| JVM (Ubuntu) | 1 | 2,325 | 1.8/2.3 | 1.8/8.3 | 3.9/11.3 | 3.0/8.6 | 1.6/1.8 | 5.0 | 33.5 MB |
| JVM (Ubuntu) | 2 | 2,271 | 1.7/2.4 | 2.0/4.7 | 3.3/6.8 | 2.0/4.2 | 1.6/2.9 | 4.0 | 20.1 MB |
| Android API 35 x86_64 emulator | 1 | 6,162 | 4.0/5.0 | 3.6/10.3 | 6.8/13.7 | 5.2/12.0 | 3.5/4.2 | 7.5 | 33.5 MB |
| Android API 35 x86_64 emulator | 2 | 4,068 | 2.1/7.3 | 1.9/3.9 | 2.8/6.6 | 1.8/3.9 | 1.5/1.8 | 4.5 | 20.1 MB |
| iOS simulator (macOS arm64) | 1 | 4,134 | 1.5/2.1 | 9.5/18.5 | 15.1/19.7 | 15.1/20.2 | 18.8/21.5 | 13.5 | 33.5 MB |
| iOS simulator (macOS arm64) | 2 | 5,770 | 1.9/3.1 | 12.0/24.9 | 14.9/20.8 | 17.7/35.8 | 22.8/75.6 | 14.8 | 20.1 MB |

Open items from the measurements:

- [x] iOS `list` and `find` were roughly ten times slower than JVM and Android. Cause: CI ran the measurement in an unoptimized debug Kotlin/Native binary. Batching page loads (Phase 4) did not change it; an optimized test binary did (list median 15.0 ms debug, 0.4 ms optimized; see Milestone 4). Measure iOS with `iosSimulatorArm64ReleaseTest`.
- [ ] Page utilization: versions of 1 to 3 KB on 4 KiB pages leave much of each page empty. Evaluate page size or body compression with a real corpus.

**Decisions within the contract, for review:**

1. Plain text is the default and never raises `InvalidQuery`; raw FTS5 is opt-in and passes phrases, prefixes, `NEAR` and column filters through without kvid-level guarantees.
2. Supplementary-plane characters are classified approximately when deciding whether a plain piece has tokens; a piece made only of characters SQLite does not tokenize makes an `ALL` query match nothing.
3. Tag filters require every listed tag; there is no any-of tag filter yet. The uri prefix compares UTF-8 bytes literally (including NUL) and is not index-assisted.
4. Uris and metadata are stored as given, not normalized.
5. `find` pagination stays offset-based under an expiring cursor; snippets come from the body column only.

Defer stemming, advanced query syntax, phrase queries, and adaptive score cutoffs until demonstrated needs justify their complexity. Prefix matching of the last plain-query term was added in Phase 4 for the sample's search while typing (opt-in `prefixLastTerm`).

**Exit criterion:** persisted documents are searchable offline with reliable filtering and measured performance on target platforms.

## Milestone 4: mobile proof and first release

**Status: merged into `main` at `bc55ded`** (reviewed phase tip `3cdd69a`; [CI run 37250580229](https://github.com/fluxxion82/kvid/actions/runs/37250580229) passed at that exact tip, including mobile smoke tests and packaging). The merge tree equals the tested phase tree. A Maven Central release and the open items below remain separate work.

- [x] Notes sample (Compose Multiplatform 1.12.1, Material 3 1.9.0): `kvid-sample/shared` holds the UI, `NotesModel` and `StoreSession` for desktop, Android and iOS; `kvid-sample/androidApp` and the XcodeGen-generated `kvid-sample/iosApp` host it. Create, edit, delete, list, search while typing (last-term prefix), tag chips, version history, close and reopen.
- [x] Background/foreground transitions and recovery after process interruption, checked on running apps in CI. Android API 35 emulator: cold launch about 1.1 s creates and seeds the store; leaving the foreground closes it cleanly; a fresh process reopens it; killing the process with the store open, relaunching and leaving the foreground leaves a recovered, cleanly closed file with `quick_check` ok. iOS simulator: launch creates and seeds the store; moving another app to the front closes it cleanly. The checks read the store file from the device (`kvid-sample/check_store.py`).
- [x] Serialized concurrent calls: `StoreSession` leases defer a background close until running work finishes, and a new use cancels a pending close (sample tests on the desktop JVM and iOS simulator).
- [x] Documented resource lifecycle on the `Kvid` class: one writable handle per path per process, ownership at application scope on Android and iOS, transaction ownership, process death, and `close` semantics. `close()` can no longer be cancelled once called (a cancelled caller used to leave the handle open and its path reserved), and `use { }` closes after success, failure or cancellation; regression tests cover both.
- [x] Basic document history is in the sample (versions oldest to newest, deletions kept).
- [x] Backup from a consistent committed snapshot in the sample: a Back up action writes a verified snapshot into a `backups` directory next to the store; a test opens it read-only, verifies it, and checks that the live store keeps accepting writes while the backup stays fixed.
- [x] Packaging for Kotlin and Swift consumers, documented in the README and checked by the macOS CI job: `maven-publish` with POM metadata and javadoc jars stages the root module and its JVM, Android, iOS arm64 and iOS simulator arm64 publications; the release `KvidCore.xcframework` has device and simulator slices. All three platforms meet the CI acceptance checks.
- [ ] Publish to Maven Central under `com.sterlingalbury` (owner decision, October 5). Remaining owner steps: verify the namespace through Central's DNS TXT record for `sterlingalbury.com`, create Portal credentials, and configure signing keys as release secrets with offline backups. The build already stages under the new group.
- [x] Migration statement for the experimental APIs and stored artifacts in the README: the QR/video classes are experimental and planned for `kvid-video`; their `.bin` indexes and MP4/QR artifacts are not compatible with `.kvid` stores and do not migrate automatically; data moves by re-adding text or importing JSON Lines. The README now leads with the store and the sample.

**Codex review corrections (`028f348`; incorporated in green CI at `3cdd69a`).** A deterministic regression reproduced an obsolete search response replacing the list after the user typed a new query. Refreshes are now serialized and publish results or failures only while their query and selected tags are still current; separate regressions reproduce both obsolete responses. The staged publication's external consumer also failed to compile public `JsonObject` metadata because serialization was an implementation dependency; it is now an API dependency. CI compiles an isolated consumer against the root Maven coordinates. Packaging documentation now explicitly says artifacts are unsigned and a Central release still requires signing configuration.

Local review verification: 63 JVM store tests, 62 iOS simulator store tests, 10 desktop sample tests and 10 iOS sample tests; zero failures and zero skips. The external consumer compiles after the dependency correction, and the Android debug sample APK builds. Merge and release are separate decisions; neither is performed by this review.

Original recommendations (subsequently accepted below): verify `sterlingalbury.com` through Central's DNS TXT challenge and use `com.sterlingalbury`; add an opt-in final-term prefix mode for plain queries and enable it in the sample; use iOS 18.5 as the sample minimum until an older runtime is validated, without changing the library minimum. Commit `028f348` itself did not change the namespace, query API or iOS target. Signing keys and Portal credentials belong in release secrets, with offline backups; no credentials are required for local staging checks.

**Owner decisions applied (Claude, October 5).** All three recommendations were accepted and implemented on the branch: `ea12eab` hardens the Android smoke test, whose store copy came back empty after backgrounding in CI run 37245413199 (the app had created and seeded the store; the copy now retries until it has the SQLite header); `487413a` adds opt-in `FindOptions.prefixLastTerm` for plain queries with tests and enables it in the sample; `3cdd69a` publishes under `com.sterlingalbury` (Kotlin packages stay `com.kvid.*`) and raises the iOS sample minimum to 18.5. Claude reviewed `028f348` with no objections: refresh serialization with obsolete-response filtering, the `api` serialization dependency, and the external consumer check are all correct. CI run 37250580229 is green at `3cdd69a`: JVM and Android host tests; Android emulator with 63 device tests and the full app smoke sequence (no copy retries needed); iOS simulator tests, the optimized measurement, sample tests, the iOS 18.5 app build without the ICU linker warning and its smoke run; publications staged under `com/sterlingalbury` with the external consumer compiling against them, and the XCFramework. The prefix option is a new public API, so the branch goes back to Codex for review before merge.

**Codex second review.** Reviewed `028f348..3cdd69a` and the updated persistence contract; no actionable findings or code corrections. Opt-in prefixes preserve exact-word defaults, quote user text, handle final multi-token phrases and deduplication, and bind pagination to the compiled expression. Namespace references and packaging checks agree; the iOS minimum change is confined to the sample. Fresh local verification: 64 JVM store tests, 63 iOS store tests, and 10 sample tests on each of desktop JVM and iOS; zero failures and skips. CI additionally ran 63 Android device tests and both app smoke sequences. The merged `phase-3-search` GitHub branch was deleted after verifying its tip is an ancestor of `main`. No additional Claude review is needed because this round makes no code changes. Phase 4 was subsequently merged by owner authorization at `bc55ded`.

Library changes in this phase:

- `list()` and `find()` load a page with one version query and one tag query instead of two queries per row; tags keep the per-version hard cap through a window function; `history()` loads tags together.
- `tagCounts()` returns tags of live current versions with document counts, most used first; also on `Transaction`.

Measurements at `e3c017a` (same synthetic 5,000-note corpus as Milestone 3, median/p95 ms, single CI runs):

| Platform | Ingest | Put | Find ALL | Find ANY | ANY + tag | List 50 | Reopen + find | File |
|---|---|---|---|---|---|---|---|---|
| Android API 35 emulator | 3,140 | 2.3/3.1 | 1.7/4.8 | 2.8/6.0 | 2.0/4.8 | 0.8/3.1 | 4.5 | 20.1 MB |
| iOS simulator, debug binary | 4,345 | 3.0/5.0 | 11.0/21.4 | 13.9/21.8 | 14.3/18.1 | 15.0/24.1 | 11.6 | 20.1 MB |
| iOS simulator, optimized binary | 1,336 | 1.6/2.9 | 0.8/2.9 | 1.9/5.0 | 1.1/2.5 | 0.4/0.8 | 5.4 | 20.1 MB |

Open items:

- [x] The iOS app linked Compose's ICU data object built for iOS 18.5 while the sample targeted iOS 16.0. Owner decision: the sample's minimum is iOS 18.5 until an older runtime is validated; `kvid-core` keeps its own target.
- [x] Search while typing matched whole words only. Owner decision: opt-in `FindOptions.prefixLastTerm` for plain queries, enabled in the sample; exact words stay the library default.
- [x] Compose 1.12 requires the sample to compile against Android API 37; configured and checked in CI. `kvid-core` stays on 36.
- [ ] Android runtime evidence is API 35 only; API 23 and physical devices remain (Milestone 2).
- [ ] Peak memory is still unmeasured.

**First-release boundary:** durable documents, metadata, BM25, basic filters, portable files, and a working mobile sample. No embedding download is required.

## Milestone 5: optional semantic and hybrid search

**Handoff after Phase 4.** Start code work from `main` at `bc55ded` or later, and read planning from this branch separately. Phase 4 review found no remaining code blockers; the merged `phase-4-mobile-sample` branch is removed as part of cleanup. Review the semantic-search scope below before implementation. Namespace verification, Portal credentials/signing, API 23 and physical-device evidence, peak memory, and remaining durability fault-injection work are still open; merging Phase 4 does not mark them complete. The merge commit triggers its own main CI run; the identical phase tree already has passing CI.

**Phase 5 status (October 5).** The store-side vector layer is implemented on branch `phase-5-semantic-search` (one commit on `main` at `bc55ded`; CI run 37256251770 green at `2c32e74`: JVM and Android host tests, Android emulator with 72 device tests and the sample smoke run, iOS simulator tests, the optimized measurement, sample build and smoke run, packaging). It works with any `Embedder` and ships no model. The runtime for the first real model is decided in [ADR 0002](adr/0002-embedding-runtime.md), accepted by the owner on October 23: ONNX Runtime with `BAAI/bge-small-en-v1.5` in an optional `kvid-embed-onnx` module. The tokenizer is the first implementation step.

**Tokenizer (October 23).** `kvid-embed-onnx` now holds a pure-Kotlin BERT WordPiece tokenizer (`BertTokenizer`, no runtime dependency yet). Its character classes and normalization (clean-up, CJK padding, lowercasing and accent stripping, whitespace and punctuation) come from tables generated with Hugging Face `tokenizers` 0.23.3 for every Unicode scalar value (`scripts/generate_bert_tables.py`), not from platform Unicode data, so the three platforms cannot diverge with their Unicode versions. Special tokens are matched in raw text as the reference does; words over 100 characters become `[UNK]`; `encode` adds `[CLS]`/`[SEP]` and truncates to 512. CI builds a fixture with the reference tokenizer of `BAAI/bge-small-en-v1.5` at pinned revision `5c38ec7c405ec4b44b94cc5a9bb96e735b38267a` with SHA-256 checks of `tokenizer.json` and `vocab.txt` (`scripts/make_tokenizer_fixture.py`: curated edge cases, 3,000 seeded random texts mixing scripts, emoji, controls and punctuation, and one input over the length limit). The parity test is required in CI and compares every token id: **3,048 cases, 0 mismatches on the JVM, the Android API 35 emulator and the iOS simulator** (CI run 40016745410 at `df02d68`). On Android the fixture is pushed to the device and passed as instrumentation arguments, the mechanism the model files will use. Locally, an altered fixture case and a missing required fixture each made the test fail rather than skip. The same branch fixes the iOS sample's background close, which ran without a background task and could be suspended before finishing (CI run 40012171530 caught it on a slow simulator).

**ONNX Runtime embedder (October 24).** Implemented on `phase-5-onnx-embedder` (from `df02d68`; commits `8df1723`, `74f5120`, `8ebdbad`) by a local agent following the [implementation plan](plans/onnx-embedder-implementation.md), which Codex `gpt-6.1-sol` and Claude Opus reviewed before implementation; Codex also reviewed the code (five minor findings, all fixed before the push). **CI run 40077315508 is green at `8ebdbad` on the first push** (JVM + Android host, Android store, the new Android embedding job, iOS).

- `OnnxEmbedder` (JVM and Android) runs `bge-small-en-v1.5` with ONNX Runtime 1.30.0. Tokenization, padding, pooling and normalization are common code tested on every platform with a fake backend; only the runtime call is JVM/Android code. `EmbeddingSpec` records `sha256:` digests of `model.onnx` and `vocab.txt`. The session's options outlive it, `close` waits for a running inference, a cancelled `open` leaves nothing open, and every inference checks the output shape `[batch, seq, 384]`. `BertEmbeddingModel`'s constructor is internal for now, so output name and pooling cannot vary under one spec.
- Owner decisions (October 24): `kvid-embed-onnx` alone requires Android API 24, the minimum of `onnxruntime-android` 1.30.0 (`kvid-core` and the sample stay at 23); the AAR's `TelemetryInitializer` provider, which started a telemetry HTTP client at app launch, is removed by the module's manifest and `open` disables ONNX Runtime telemetry. A device test checks that the provider is absent. The AAR's `INTERNET` and `ACCESS_NETWORK_STATE` permissions are left to the app (README).
- Model files: `onnx/model.onnx` exists at the pinned revision (133,093,490 bytes, SHA-256 `828e1496…cf35`); no `optimum` export was needed. `kvid-embed-onnx/scripts/model-pins.json` is now the single pin for the revision and all three digests, read by CI and the local check; CI caches the model and verifies digests after every restore.
- Reference fixture: 194 cases (94 curated, 100 seeded random, 2 over 512 tokens), Python ONNX Runtime 1.30.0 on the CPU provider, one text at a time, CLS pooling, float32 normalization. One-off check against `sentence-transformers` 6.2.0 (torch 2.14.1) at the same revision: **minimum cosine 1.000000000 (1 − cos = 2.1e-12)**, so the ONNX graph is the published model.
- Parity, every case at cosine ≥ 0.9999 required, batched (8) versus single ≥ 0.99999 required; token ids equal on all cases:

| Platform | Min cosine | Max abs diff | Batched vs single |
|---|---|---|---|
| JVM, CI Ubuntu x86_64 | 1.00000000 | 5.96e-8 | 1.00000000 |
| JVM, local macOS arm64 | 1.00000000 | 1.49e-7 | 1.00000000 |
| Android API 35 emulator, CI x86_64 | 1.00000000 | 3.58e-7 | 1.00000000 |
| Android API 35 emulator, local arm64 | 1.00000000 | 1.49e-7 | 1.00000000 |
| Pixel 8, Android API 37 | 1.00000000 | 1.49e-7 | 1.00000000 |

- Measurements (`OnnxEmbedderMeasurementTest`, separate instrumentation run on Android; the Milestone 3 synthetic notes, 40 to 300 made-up words each, so tokens per note are high and likely pessimistic for real notes, though tokens per note were not recorded; times in ms, open = digest + session + warm-up; PSS in MB):

| Platform | Open (digest / session / warm-up) | Per note, batch 1 (median) | Per note, batch 16 (median) | 500 notes | 5,100 notes, extrapolated | PSS before / after open / after 500 notes |
|---|---|---|---|---|---|---|
| JVM, CI Ubuntu | 1,666 (750 / 865 / 33) | 700 | 442 | 116 s | 19.7 min | not measured |
| JVM, local macOS arm64 | 1,477 (660 / 809 / 5) | 81 | 78 | 38 s | 6.5 min | not measured |
| API 35 emulator, CI x86_64 | 584 (354 / 219 / 6) | 423 | 480 | 239 s | 40.5 min | 71 / 242 / 1,130 |
| API 35 emulator, local arm64 | 279 (111 / 152 / 12) | 195 | 192 | 96 s | 16.2 min | 78 / 249 / 1,156 |
| Pixel 8, API 37 | 1,066 (585 / 457 / 15) | 293 | 375 | 216 s | 36.7 min | 71 / 227 / 1,058 |

- Sizes: model 133.1 MB fp32; `onnxruntime-android` 1.30.0 native libraries per ABI: arm64-v8a 33.1 MB, armeabi-v7a 23.4 MB, x86 39.4 MB, x86_64 39.4 MB (uncompressed `.so`); JVM jar 55.6 MB (natives for every desktop platform). The device-test APK with all four ABIs is 143 MB, so tests package one ABI (`-Pkvid.deviceTestAbi`).
- **Batching and memory (October 24, follow-up on `phase-5-onnx-embedder`, commit `9821fac`, CI run 40120045653 green).** The first measurements left two findings: PSS of about 1.1 GB after embedding 500 notes in batches of 16 (against about 240 MB after open), and batches of 16 no faster per note than one note at a time on Android. `OnnxEmbedderTuningTest` (`KVID_EMBEDDING_TUNE=corpus=…+batch=…+sort=…+arena=…+pattern=…`, one configuration per instrumentation run, PSS sampled every 100 ms) measured 200 notes per configuration on two corpora: the Milestone 3 notes (`m3`, 95,342 tokens, nearly every note at the 512-token limit) and `mixed` (24,836 tokens; 5 to 600 English words, log-uniform, so lengths differ as in real notes). Settings: `sort` groups texts of similar token length into one inference (results keep input order), `arena` is ONNX Runtime's CPU memory arena, `pattern` its memory-pattern planning. Local API 35 arm64 emulator, PSS in MB (after open ≈ 270 on `mixed`, ≈ 350 on `m3`):

| Corpus | Batch | Sort | Arena | Padded tokens | Per note (ms) | PSS peak | PSS after the call |
|---|---|---|---|---|---|---|---|
| mixed | 16 | off | on | 91,256 | 219.6 | 1,215 | 1,211 |
| mixed | 16 | on | on | 28,640 | 51.3 | 870 | 870 |
| mixed | 8 | on | on | 26,784 | 46.8 | 558 | 558 |
| mixed | 4 | on | on | 25,664 | 43.9 | 443 | 443 |
| mixed | 1 | – | on | 24,836 | 62.2 | 287 | 267 |
| mixed | 16 | on | off | 28,640 | 58.8 | 490 | 245 |
| mixed | 8 | on | off | 26,784 | 52.6 | 408 | 232 |
| mixed | 8 | on | off, pattern off | 26,784 | 53.1 | 360 | 231 |
| mixed | 4 | on | off | 25,664 | 48.8 | 323 | 221 |
| m3 | 16 | off | on | 102,400 | 206.4 | 1,160 | 1,149 |
| m3 | 8 | on | on | 96,528 | 181.9 | 716 | 704 |
| m3 | 8 | on | off | 96,528 | 187.5 | 465 | 215 |
| m3 | 4 | on | off | 95,788 | 184.5 | 350 | 219 |
| m3 | 1 | – | off | 95,342 | 188.5 | 349 | 216 |

  Pixel 8 (API 37), the same 14 configurations, run in two sessions with a pause between them (after open ≈ 170 to 290 MB):

| Corpus | Batch | Sort | Arena | Per note (ms) | PSS peak | PSS after the call |
|---|---|---|---|---|---|---|
| mixed | 16 | off | on | 292.9 | 1,213 | 1,171 |
| mixed | 16 | on | on | 127.8 | 864 | 864 |
| mixed | 8 | on | on | 105.3 | 556 | 547 |
| mixed | 4 | on | on | 92.8 | 459 | 421 |
| mixed | 1 | – | on | 97.2 | 316 | 295 |
| mixed | 16 | on | off | 200.6 | 611 | 208 |
| mixed | 8 | on | off | 180.6 | 413 | 177 |
| mixed | 8 | on | off, pattern off | 132.5 | 396 | 213 |
| mixed | 4 | on | off | 181.3 | 317 | 216 |
| m3 | 16 | off | on | 469.9 | 1,154 | 1,110 |
| m3 | 8 | on | on | 454.6 | 714 | 687 |
| m3 | 8 | on | off | 614.8 | 467 | 210 |
| m3 | 4 | on | off | 592.8 | 376 | 215 |
| m3 | 1 | – | off | 680.3 | 375 | 214 |

  JVM on the local Mac, `mixed`: batch 16 unsorted 179.4 ms, sorted 51.8; batch 8 sorted 44.5; batch 4 sorted 46.7; batch 1 83.7.

  Reading: the slowness of batch 16 was padding, not batching. Unsorted batches of 16 padded `mixed` to 3.7× its token count; sorting removes almost all of it and makes batch 16 four times faster per note on the emulator and 2.3× on the Pixel 8. Sorted batches of 4 are the fastest setting on both Android targets and within noise of the fastest on the JVM; on `m3`, where every note is at the limit, the emulator rows measured 182 to 189 ms per note across batch sizes 1 to 8, but those rows mix arena on (batch 8) and off (batches 8, 4, 1) and there is no matched arena-on batch-4 row, so they do not establish that batch size does not matter there. The memory after a call is the arena keeping the peak allocation of the largest batch: with the arena off it returns to about the post-open level (≈ 220 MB) and the peak falls further (323 MB for sorted batches of 4 against 1,215 MB before). Its time cost differs by target: about 10% per note on the emulator, but 35 to 95% in the Pixel 8 table (`mixed` batch 4: 181 against 93 ms; `m3` batch 8: 615 against 455). The phone numbers are confounded by heat: the arena-off runs came after the heaviest ones, the same `m3` batch-16 configuration measured 375 ms per note in the morning's measurement run and 470 here, and the one arena-off run that followed a pause (`mixed` batch 8, pattern off) came in at 133 ms, close to the arena-on figure. The interleaved A/B on a cooled Pixel 8 (battery 30.2 °C at the start, 32.6 °C at the end; `mixed`, sorted batches of 4, 200 notes per run, arena on and off alternating) settles it:

| Pair | Arena on: per note (ms) | peak / after (MB) | Arena off: per note (ms) | peak / after (MB) | Off ÷ on |
|---|---|---|---|---|---|
| 1 | 68.8 | 461 / 461 | 133.0 | 310 / 179 | 1.93 |
| 2 | 99.2 | 456 / 456 | 162.1 | 323 / 221 | 1.63 |
| 3 | 95.3 | 466 / 466 | 165.5 | 322 / 221 | 1.74 |
| 4 | 99.3 | 462 / 424 | 172.8 | 321 / 220 | 1.74 |

  The arena-off cost on this phone is real: 1.6 to 1.9× the time per note in every pair, against about 1.1× on the emulator, for 140 MB less peak memory and about 240 MB less resident memory after a call. (Pair 1 is faster on both sides, the cold phone; the ratio holds.) **Decided by the owner (October 24): `cpuMemoryArena = true` is the default again** (commit `d3e600f`, CI run 40163662098 green; CI's x86_64 emulator reports 452 MB after embedding and 490 ms per note, parity unchanged), since the memory the arena holds is released when the embedder is closed, which an app does after bulk indexing anyway, and a 70% slower index on phones is the larger cost; `cpuMemoryArena = false` is documented in `OnnxOptions` and the README for memory-constrained processes that keep the embedder open. With the arena on, the emulator's measurement run reports 445 MB after embedding 500 `m3` notes (229 MB with it off, 1,156 MB with the original unsorted batches of 16) and 199 ms per note; the local check before the push ran every part and passed. A third option worth one experiment is ONNX Runtime's arena shrinkage run option (`memory.enable_memory_arena_shrinkage`), which returns unused arena chunks after each run and might give most of both. Turning memory patterns off saved 48 MB of peak on the emulator and 17 MB on the Pixel 8 in one run each, at no clear time cost; not adopted on single samples. **New defaults in `OnnxOptions`: `maxBatchSize = 4`, `sortByLength = true`, `memoryPattern = true`**, and the arena as decided below; each is still a parameter. Parity is unchanged with sorting on (the fixture test embeds in sorted batches of 8 and one at a time; 194 cases at minimum cosine 1.00000000 on the JVM and the emulator). _Historical, arena off (commit `9821fac`, superseded by `d3e600f` below):_ `OnnxEmbedderMeasurementTest` on the emulator reported PSS 229 MB after embedding 500 `m3` notes (1,156 MB with the original unsorted batches of 16) and 227 ms per note (195 before; these notes all reach the limit, so this is the arena's cost plus run-to-run noise, which is about ±10% on the emulator). CI's x86_64 emulator, same configuration: 213 MB after embedding (1,130 before), 568 ms per note (423 before; that runner's timings vary widely between runs). Parity in CI: minimum cosine 1.00000000, maximum absolute difference 5.96e-8 on the JVM and 3.58e-7 on the emulator, unchanged. Local check before the push: python, fixtures, jvm, ios and android (72 core and 42 embed device tests plus the measurement run, API 35 emulator) all ran and passed; the sample parts were not requested. Still open: indexing time. With today's defaults (sorted batches of 4, arena on) a `mixed` note costs 43.9 ms on the emulator and 92.8 ms on the Pixel 8 (single runs; 69 to 99 ms in the cooled A/B), so 5,100 notes take about 4 and 8 minutes of embedding alone; with the arena off, about 49 and 163 ms. There is no matched arena-on batch-4 `m3` row on either device: the closest are arena-on batch 8 at 182 ms (emulator) and 455 ms (warm Pixel 8), about 15 and 39 minutes. These figures come from one 200-note `embed` call sorted as a whole; `Kvid.indexVectors` calls `embed` with 32 documents at a time, so sorting only works within each 32-note window, and the store's own reads and writes are not included (Codex review of [mobile-indexing.md](plans/mobile-indexing.md)). The mobile-indexing study measures full `indexVectors` runs. Mobile indexing still needs background work and progress, or the MiniLM / int8 fallbacks of ADR 0002; mobile indexing still needs background work and progress, or the MiniLM / int8 fallbacks of ADR 0002.
- Also found: `connectedAndroidDeviceTest` reports success when the test APK fails to install and no test runs (seen locally with a full emulator disk); CI and the local check now gate on fresh JUnit results with required test classes (`scripts/verify_device_tests.py`). The `/data/local/tmp` fallback (copying through a shell `cat`) was never needed: both devices read the fixture directly.
- Local pre-push check, `scripts/check-local.sh` (new): parts ran, skipped or failed are listed and a partial run exits 2. Run before the push against the local API 35 emulator (`--with-sample`): python, fixtures, jvm, ios, android (72 core + 40 embed device tests, then the measurement run) and sample-android **ran**; **sample-ios skipped** (`xcodegen` is not installed locally; CI builds and runs the iOS sample). A run against the Pixel 8 was **skipped** because the phone had disconnected from adb; the Pixel 8 numbers above come from the implementing agent's direct device runs of the same code before two test-only review fixes (all seven test classes passed). Locally, an altered reference vector and a missing required fixture each failed the run.
- **Review (October 25, cloud session), `8df1723..d3e600f`: no blocking findings.**
  - **Checked against the plan.** The pipeline is common code tested with a fake backend, and ONNX Runtime calls sit in one source directory shared by the JVM and Android, with no iOS stub. The spec records the file digests. Every inference checks the output shape. The lifecycle is safe: `close` waits for running inferences, a cancelled `open` leaks nothing, and the session options outlive the session. CI gates on JUnit results plus the printed parity lines, and the local check reports parts it skipped.
  - **Verified independently.** Now that Google Maven and Hugging Face are reachable, I ran `scripts/check-local.sh --only python,fixtures,jvm` on Linux x86_64. It passed: the full `./gradlew build`, tokenizer 3,048 cases with 0 mismatches, and embedding 194 cases at minimum cosine 1.00000000 with maximum absolute difference 5.96e-8. JVM timings in this container are noisy: the batch-1 median was 406 ms in one run and 80 ms in the next.
  - **Added: `OnnxEmbedderStoreTest`** (commit `0d85781`, required in CI and in the local check). No test drove the real model through the store. The new test indexes six notes and requires `findSimilar` to rank the right note first for six queries that share no word with it; the test checks that lexically. `findHybrid` must agree when a query has no lexical hits. After a reopen, a second embedder over the same files has an equal spec, embeds nothing again and gets the same results. The smallest margin over the runner-up is 0.06 cosine. It passed on the JVM locally and on CI's API 35 emulator. CI run 40167171523 is green on every job; the Android embedding job ran 43 device tests with 0 failures, and parity was unchanged.
  - **Minor, left as is.** `OrtEncoderBackend.live` and `insideRunForTests` are test hooks in main code; they are internal and small. The measurement run in the `android-embed` job is verified without `--since`, but its required `[kvid-embedding-measure]` logcat line still fails a run that did not measure.
  - **Cloud-session environment (not committed).** Gradle here needs an init script in `~/.gradle/init.d` that sends Maven Central requests to Google's mirror, because `repo.maven.apache.org` intermittently answers 404 through the proxy. It also needs an Android SDK installed in the session's scratchpad. iOS and the Android emulator still cannot run here (no macOS, no KVM).
  - **Open for the owner.** Mobile indexing time is the main product risk: 5,100 notes take about 8 to 50 minutes on a Pixel 8. The choices are background indexing with progress in the sample, a measured int8 model, or the MiniLM fallback of ADR 0002. Also open: whether to merge this branch, which contains `phase-5-semantic-search`, before the iOS work.
- **Local sign-off for merge (October 25, local agent), `0d85781`: approved.**
  - **Reviewed `0d85781`.** `OnnxEmbedderStoreTest` asserts what it claims. Each query is first checked lexically (`MatchMode.ANY`) to share no word with its target note, so only the vectors connect them. The spec must be equal after a reopen with a second embedder over the same files, and `indexVectors` must embed nothing again. Hybrid results are required to agree only where a query has no lexical hits, which is the correct condition under reciprocal rank fusion. The temporary store goes in `java.io.tmpdir`, which is the app's cache directory on Android; it worked on the emulator. The required-class lists are consistent in all four gates: CI's JVM and `android-embed` jobs, and the jvm and android parts of `check-local.sh`.
  - **Local check.** `scripts/check-local.sh` with the android part, against the local API 35 emulator (`Pixel_9`, arm64): python, fixtures, jvm, ios and android all **ran and passed** (`RESULT: PASSED`, exit 0); the sample parts were not requested. JVM: 42 kvid-embed-onnx tests, all passed. Emulator: 72 kvid-core and 43 kvid-embed-onnx device tests, then the measurement run, with 0 failures or skips. `OnnxEmbedderStoreTest` passed on both. Parity was unchanged: 194 cases at minimum cosine 1.00000000 and maximum absolute difference 1.49e-7. On the emulator the smallest semantic margin was 0.064 (query "money we spent last quarter": Quarterly budget 0.567, then Flight to Lisbon 0.503), matching the 0.06 measured on the JVM.
  - **CI.** Run 40167171523 is green at `0d85781` on every job. The branch contains `phase-5-semantic-search`, has `main` as an ancestor and tracks no `docs/`, so it can be merged with a merge commit.
  - **No findings.** I agree with the cloud session's two minor notes and leave them as is.
  - **Merged** into `main` as `a6acaef` (a merge commit of `0d85781`; its tree is identical to the reviewed branch), with `main` CI run 40238653366 green on every job, including the docs policy. `phase-5-semantic-search` and `phase-5-onnx-embedder` were deleted after both were confirmed to be contained in `main`. The iOS work continues on `phase-5-ios-embedder` from `a6acaef`.
- **iOS embedder (October 25 to 26, local agent), `phase-5-ios-embedder` at `c8be6a3`: implemented, awaiting the cloud session's review.** [Implementation plan](plans/ios-embedder-implementation.md), reviewed by Codex before implementation (14 findings, all folded in, R1 to R12) and settled by a link spike (R13).
  - **What exists.**
    - `OnnxEmbedder` is an `expect class` in common code. The JVM/Android implementation is unchanged, and the iOS one calls the ONNX Runtime 1.30.0 C API through cinterop.
    - The runtime is Microsoft's `onnxruntime-c` pod archive. It is downloaded at a pinned URL and SHA-256 into `~/.cache/kvid/runtime`, verified file by file on every build, never committed, and embedded statically in the module's klib.
    - The embedder tests moved to `commonTest` and run on the JVM, Android and the iOS simulator from one copy.
    - Apps link one static `KvidEmbed` XCFramework, with ONNX Runtime inside and the kvid-core and kvid-embed-onnx APIs exported, instead of KvidCore. They need no CocoaPods and no separate runtime framework. KvidCore stays free of ONNX Runtime.
  - **Telemetry.** The iOS runtime contains Microsoft's 1DS telemetry client (endpoint `mobile.events.data.microsoft.com`).
    - Once per process, before kvid's first call into ONNX Runtime, kvid sets `ORT_DISABLE_TELEMETRY=1` if the app has not set it, and calls `DisableTelemetryEvents`. If the app has set another value, kvid keeps it, and suppression is then the app's choice; this is documented in the README.
    - Four test runs, each a fresh simulator process, check the variable unset, truthy, falsy and empty.
    - **Network check (October 26, owner-approved control): passed.** This was the plan's R2 probe. A disposable executable, built from `c8be6a3` with one added binary and a control switch and never committed, opened the embedder and embedded 32 synthetic sentences. It then stayed alive for 112 s.
      - **Setup.** Each condition ran as a fresh process on an iPhone 16 simulator (iOS 18.1) with its own empty `HOME`. The probe cleared every CI and test opt-out variable that ORT 1.30 checks and printed its environment first.
      - **Capture.** Each condition was captured for 2 minutes, starting before launch, with three tools:
        - `log stream` for the probe process and for `com.apple.network`/`mDNSResponder`;
        - `nettop`, sampled every second;
        - `lsof` on the probe's PID, polled 99 times.
      - **1. Production policy** (variable unset, so kvid set it to `1` and also called `DisableTelemetryEvents`):
        - No socket in `lsof` or `nettop`.
        - No network or 1DS log events.
        - Nothing written under `HOME`; ORT never created its uploader.
      - **2. API call only, variable `0`** (kvid's "app's explicit choice" path):
        - One HTTPS connection to `https://mobile.events.data.microsoft.com/OneCollector/1.0` about 9 s after launch.
        - Two POSTs, both answered with HTTP 200: 2.6 KB sent, 9.1 KB received.
        - A 1DS event cache was written at `Library/Application Support/Microsoft/DeveloperTools/.onnxruntime/onnxruntime.db`.
        - This matches ORT's source: `DisableTelemetryEvents` leaves the uploader running, and the once-per-process `ProcessInfo` event (OS, CPU model, device class, memory) is still sent.
      - **3. Control** (neither the variable nor the call): the same endpoint at about 9 s, two POSTs answered with HTTP 200, 3.4 KB sent, and the same cache file.
      - **Verdict.** Under R2's rule this is a **pass**: condition 1 made no connection, and the control's attempt was visible. Condition 2 shows that **the C API call alone does not prevent an upload**. With kvid's current policy, an app that sets `ORT_DISABLE_TELEMETRY` to `0`, `false` or an empty value therefore sends ORT's `ProcessInfo` event. **Owner decision:** keep that as the app's choice and say so plainly in the README, or overwrite the value or refuse to open (R2's flagged alternative).
      - **Evidence and cleanup.** The capture files are in the local agent's scratchpad and are not committed. The probe worktree was deleted afterwards.
  - **Results** (local Mac simulator, iOS 27.1; CI's runner, iOS 26.5):
    - **Parity:** 194 cases at minimum cosine 1.00000000, maximum absolute difference 1.49e-7, and batched against unbatched 1.00000000, on both. Optimized and debug test binaries both took about 80 ms per note locally (ORT's native kernels dominate; about 350 to 430 ms on CI's runner).
    - **Portability:** a store indexed on the JVM reopens on the iOS simulator with an equal spec. Nothing is embedded again, 6/6 top hits match, and the maximum score difference is 0.
    - **Swift consumer:** links `KvidEmbed` with only `-F`/`-framework`, on the simulator and on the device slice (`minos` 15.1). The link map shows `_OrtGetApiBase` coming from KvidEmbed. On the simulator, its vector for "hello world" equals Python ONNX Runtime's (maximum absolute difference 0).
    - **Leak check:** `leaks` cannot attach to simulator processes on Xcode 27.1, so this is a loop of 200 open/embed/close cycles plus 120 failed opens and 40 failed inferences. The per-kind native object counters return to baseline every time, and malloc in use grew 25 KiB. The result is reported as "no growth detected". A negative control leaking 51 MiB is detected; `phys_footprint` alone missed it, so malloc in use is the gate.
  - **Local checks before the push.** `scripts/check-local.sh` at these commits ran python, fixtures, jvm, ios and ios-framework, all passed. The android part failed on the local `Pixel_9` emulator only because its disk was 94% full (kvid-core's store measurement hit a disk I/O error). It then passed on a Pixel 4 XL (API 33), as the owner chose: 72 kvid-core and 51 kvid-embed-onnx device tests, parity 1.00000000, 618 ms per note on the Milestone 3 notes and 442 MB PSS after embedding, with the arena on.
  - **CI.** Run 40378076539 is green on every job. Its first attempt failed in kvid-core's experimental `HnswVectorIndexTest.testScalability`, which hit its 5-minute `runTest` timeout on the iOS simulator. That test took 218 s in the previous run on this branch, so it has little margin on slow runners; it is the Appendix A item, and kvid-core is unchanged here. The rerun of the failed job passed. **Open for the owner:** raise that timeout, shrink the test, or quarantine it with the rest of the experimental pipeline.
  - **Still open.** The owner's decision on apps that set a false or empty `ORT_DISABLE_TELEMETRY`, measurements on the paired iPhone 13 (signing needs the owner's development team), the cloud session's review of the plan and the code, and the mobile-indexing study, which now has iOS simulator numbers.

- [ ] Support one real embedding implementation first; verify tokenizer, pooling, normalization, and output parity across supported platforms. Decided in ADR 0002 (accepted October 23). Tokenizer parity is done on all three platforms; embedding parity (pooling, normalization, output) is done on the JVM and Android (October 24, CI run 40077315508) and on the iOS simulator (October 26, `phase-5-ios-embedder`, CI run 40378076539), not yet merged or measured on an iPhone.
- [x] Record the full embedding configuration in the file: model/revision or hash, tokenizer revision, dimensions, pooling, normalization, and distance metric. Refuse to mix models. `EmbeddingSpec` in `kvid_meta.embedding_config`; `EmbeddingMismatch` before the model runs or anything is written.
- [x] Treat embeddings as derived data; define missing-model behavior and explicit re-embedding/index migration. Vectors are removed when a write supersedes their version; without vectors, semantic search is empty and hybrid search is lexical, and no model runs; `resetVectors()` switches models; schema 2 stores migrate to schema 3 on a writable open (the first real migration).
- [x] Keep exact vector search as the correctness baseline and initial implementation. `findSimilar` scans every live embedded version with the shared filters, cursors and tie order; tests compare it with a brute-force reference.
- [ ] Introduce HNSW only after measurements establish a benefit. Evaluate recall@k versus latency and memory across parameters and multiple seeded datasets. The current implementation is a reference, not a release candidate: it recomputes the farthest result inside its inner loop and persists as CSV text.
- [ ] Benchmark graph construction and query behavior; avoid fragile wall-clock assertions in ordinary unit tests.
- [ ] Add hybrid search with document-level deduplication and measured reciprocal-rank-fusion settings. `findHybrid` is implemented (k = 60, 100 candidates per ranking, weights; each document once; lexical snippets kept) and tested against an independent fusion calculation; the settings still need measuring on an evaluation corpus with a real model.
- [ ] Add token-aware chunking when the real tokenizer is available. The tokenizer is available; chunking and its chunk-to-document aggregation are not started.

ONNX Runtime with a shared model (for example `bge-small-en-v1.5`, 384 dimensions) is the candidate for portable vectors across platforms, not a required default. Platform-native (Apple `NLContextualEmbedding`) and remote models may be separate providers with distinct identities. Do not impose a model download on lexical-only users.

Store-side vector measurements at `2c32e74` (the Milestone 3 corpus, 5,100 documents, pseudo-random 384-dimension vectors standing in for `bge-small`; store costs only, no model inference; medians in ms):

| Platform | Store all vectors | Exact similar | Hybrid | File with vectors |
|---|---|---|---|---|
| JVM (Ubuntu) | 367 | 16.0 | 21.8 | 30.6 MB (20.1 MB without) |
| Android API 35 emulator | 601 | 38.2 | 48.9 | 30.6 MB |
| iOS simulator, optimized binary | 332 | 33.8 | 40.9 | 30.6 MB |
| iOS simulator, debug binary | 5,154 | 1,232 | 1,258 | 30.6 MB |

At this size exact search is well within interactive latency on every target, so HNSW is not justified yet. Vectors add about 2 KB per document (1.5 KB of float32 plus page overhead).

Next steps (ADR 0002 accepted; the tokenizer and the JVM/Android embedder are done and reviewed, October 25). Merging `phase-5-onnx-embedder` waits for the local agent's sign-off on `0d85781` (owner, October 25). Then two tracks run in parallel. The [mobile indexing study](plans/mobile-indexing.md) is reviewed by Codex first, then the cloud session measures quality and JVM speed and the local agent measures devices. [iOS through cinterop](plans/ios-embedder.md) is planned and implemented by the local agent; then iOS through cinterop; then an evaluation corpus to measure semantic and hybrid retrieval and tune fusion; then semantic search in the sample.

**Exit criterion:** semantic retrieval improves a representative evaluation corpus and has explicit model compatibility and resource costs.

## Later capabilities

### Encryption

Implement encryption after the format has reserved its required structure and before promoting kvid for sensitive-data use. Select a maintained cross-platform cryptographic implementation; specify key provisioning, password derivation if supported, authenticated coverage of documents and indexes, nonce lifecycle, and key rotation. Checksums detect accidental corruption; they do not provide authentication. If SQLite is chosen, evaluate whole-file encryption of the exported snapshot versus an encrypted-database build.

### Video archive/export

Move the existing QR/MP4 pipeline into optional `kvid-video`. Repair portable compression, Android codec buffering/plane handling, iOS pixel conversion/resource lifetimes, and genuine MP4 encoding as part of this work (Appendix A).

Require exact byte recovery, payload checksums, explicit missing-frame errors, and cross-platform fixtures. Include chunk sequencing and reconstruction; decoding QR strings alone is not a complete document import. Derive frame size from QR version (the current 256 px frames hold under two pixels per module at version 30). Test codec/quality configurations before describing export as a backup. Video export is codec-dependent.

### Backup and synchronization

Document consistent snapshot export and interrupted-compaction recovery early. Treat multi-device sync as a separate design with stable identifiers, conflict resolution, merge behavior, and deletion propagation. An append-only file is not by itself a synchronization protocol.

### Developer tools and additional media

Expand a JVM CLI when the API stabilizes: `create`, `put`, `get`, `find`, `stats`, `verify`, `export`, and `import`; add history/compaction commands with those features. Add Markdown import/export, blobs, image embeddings (MobileCLIP-class models are designed for phones), and natural-language date parsing based on actual demand.

### AI integrations, last

Optional context assembly with citations and a token budget can build on search. MCP, Koog, LangChain4j, and structured memory cards follow demonstrated integration needs. The core store must remain useful without an LLM or network connection.

## Module boundaries

Begin with logical boundaries and split published artifacts when dependencies or consumers require it:

| Module | Responsibility |
|---|---|
| `kvid-core` | Documents, persistence contract, storage, lifecycle, recovery |
| `kvid-search` | Standalone lexical/vector indexes and ranking (only if the custom-store path is chosen; with SQLite most of this is FTS5) |
| `kvid-text` | Shared text normalization, tokenization, chunking; extract when useful |
| `kvid-sample` | Android/iOS proof of the library |
| Optional providers | Embedding runtimes and remote APIs |
| Later tools | Video export, CLI, and AI adapters |

Keep store-specific version visibility and transaction rules out of standalone search components. Avoid creating all proposed modules before they have implementations or a clear dependency-isolation benefit.

## Decisions still open

1. Final acceptance of the proposed scoped transaction/error API and retention defaults.
2. Android runtime evidence and platform minimum changes in core.
3. Snapshot publication and sync adapters; supported error-code translation.
4. Representative workloads, cold-open/peak-memory budgets and native footprint.
5. Encrypted export design and later vector providers. Tokenizer configuration for 0.1 is decided: `unicode61` defaults with NFC-stored text and no stemming (Milestone 3).

SQLite and the closed/snapshot portable-artifact definition are selected. The `.kvid` extension names a SQLite application database. Historical full-text search and public WAL support are deferred; they are not guarantees of 0.1.

## Appendix A: investigation checklist from the code review

Findings from reading the code. Items marked "confirmed" were reproduced by a test on this branch; the rest are unconfirmed on devices.

Core (commonMain):

- [x] confirmed and fixed: `HnswVectorIndex.search` returned candidates in visit order, not by distance.
- [ ] `HnswVectorIndex` recomputes the farthest element of the result set with `maxByOrNull` inside the inner loop and re-sorts the candidate list on every insert (`SemanticEmbedding.kt`); 500 inserts need a five-minute timeout on the iOS simulator.
- [ ] `MemoryEncoder` hard-codes QR version 30 into 256 px frames; no test proves this decodes after H.264 compression.
- [ ] `JvmVideoDecoder` extracts every frame to disk even when a subset is requested.

Android (`AndroidVideoEncoder.kt`):

- [ ] `addFrame` never drains output buffers; once the codec's input pool fills, frames are dropped while returning success, and `finalize` loops waiting for an end-of-stream it never queued.
- [ ] RGB is converted to planar I420 while the codec is configured for semi-planar NV12.
- [ ] The decoder ignores stride, slice height and the real output colour format; it also re-queues end-of-stream every iteration and accumulates every frame in memory.
- [ ] The QR decoder never calls `TextCompression.decompress`; there is no Android QR generator at all.
- [ ] Re-`initialize` leaks the previous `MediaCodec`; `encodingTimeMs` is measured from construction, not initialization.

iOS:

- [x] confirmed by CI (three runs): `IosQRCodeGenerator.generateQRCode` throws `Failed to create CGImage from QR code` for every input on GitHub's macOS simulator runner, with both the default `CIContext` and `kCIContextUseSoftwareRenderer`. Also reproduced locally on Xcode 26.2 with both contexts. The five rendering tests run by default; CI explicitly opts out using `KVID_SKIP_IOS_QR_RENDERING_TESTS=true` and logs the exclusion reason. Recommended Phase 1 fix regardless: a pure-Kotlin QR encoder in `commonMain` (a port of Nayuki's qrcodegen, or the `qrose` KMP library) so generation is byte-identical on every platform and depends on neither Core Image nor ZXing; decoding stays platform-specific (ZXing, Vision).
- [x] documented by an `@Ignore`d round-trip test: `IosQRCodeDecoder` passes raw pixels to `CIImage.imageWithData`, which expects an encoded image.
- [ ] compiler warning, not yet confirmed at runtime: six `String as NSString` casts in `IosHnswVectorIndex.kt` are flagged "This cast can never succeed". Kotlin/Native often bridges these at runtime, so iOS index persistence needs an actual save/load test on the simulator before this is called a bug.
- [ ] `IosTextCompression` uses Apple's raw-DEFLATE `zlib` algorithm under a `GZ:` prefix; JVM/Android gzip payloads cannot be read on iOS and vice versa.
- [ ] `IosVideoEncoder` writes a custom raw-RGB container, not MP4; `IosVideoDecoder` cannot read it, emits BGRA labelled RGB_888, skips frame-number increments on `continue`, and hard-codes H.264 in `getVideoInfo`.
- [ ] CoreFoundation objects (`CGImage`, colour spaces, every `copyNextSampleBuffer`) are never released.
- [ ] `IosQRCodeGenerator` ignores the requested version and reports it as honoured.

Build and examples:

- [ ] `PersistenceExample` looks for an index filename the writer never produces; `PerformanceBenchmark.benchmarkEndToEnd` reuses one encoder across sizes without clearing it; the advanced benchmarks run parameter sweeps that are impractical with the current HNSW.

## Appendix B: memvid background

kvid began as a port of memvid v1 (Python; text chunks, gzip, QR codes, MP4 frames, FAISS index). memvid deprecated that design in January 2026 and rewrote the project in Rust as v2: a single-file `.mv2` store with an embedded write-ahead log, append-only checksummed frames, a Tantivy BM25 index, an HNSW index, a time index, and a TOC footer; hybrid search fused with reciprocal rank fusion; as-of queries; optional encryption. The stated reasons for abandoning v1 were slow QR decoding, codec behaviour that varied by platform, no crash recovery, vector-only search, and a Python implementation that could not be embedded. These facts come from the memvid repository at commit e6bd9f7 (2026-07-14), its `MV2_SPEC.md`, and package-registry metadata; they are background for the architecture decision, not requirements. Byte compatibility with `.mv2` is not a goal: its index segments are Rust-library layouts and its spec and code disagree in several places. An importer that reads `.mv2` frames and rebuilds kvid indexes can be considered later if users need it.

## Reference notes

- [SQLite single-file portability](https://www.sqlite.org/onefile.html), [atomic commit](https://www.sqlite.org/atomiccommit.html), and [WAL behavior](https://www.sqlite.org/wal.html) inform the storage comparison; runtime journals differ from a closed portable artifact.
- [kotlinx-io buffered I/O](https://kotlinlang.org/api/kotlinx-io/kotlinx-io-core/kotlinx.io/-sink/) informs common I/O usage if the custom-store path is chosen; platform durability requirements must be established separately.
- [Android Gradle Library Plugin for KMP](https://developer.android.com/kotlin/multiplatform/plugin) documents the `android {}` block used in `kvid-core/build.gradle.kts`.

## Phase 2 review implementation references

- [Device-test source set configuration](https://developer.android.com/kotlin/multiplatform/plugin): `sourceSetTreeName = "test"` includes commonTest without breaking the default native hierarchy.
- [Android emulator runner](https://github.com/ReactiveCircus/android-emulator-runner): the added CI job uses API 35/x86_64 and enables KVM. Local evidence above is API 35/arm64; the CI job's own result is run 37217532833 at `f678eb1` (44 tests, counted by the job's verification step).
