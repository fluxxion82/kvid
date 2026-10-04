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
3. Tag filters require every listed tag; there is no any-of tag filter yet. The uri prefix uses `GLOB` with escaped wildcards and is not index-assisted.
4. Uris and metadata are stored as given, not normalized.
5. `find` pagination stays offset-based under an expiring cursor; snippets come from the body column only.

Defer stemming, advanced query syntax, phrase/prefix queries, and adaptive score cutoffs until demonstrated needs justify their complexity.

**Exit criterion:** persisted documents are searchable offline with reliable filtering and measured performance on target platforms.

## Milestone 4: mobile proof and first release

**Status: in progress on branch `phase-4-mobile-sample`** (five commits on `main` at `c299d31`; CI run 37228349307 green at `e3c017a`). Ready for review; packaging, snapshot UI and the open items below remain.

- [x] Notes sample (Compose Multiplatform 1.12.1, Material 3 1.9.0): `kvid-sample/shared` holds the UI, `NotesModel` and `StoreSession` for desktop, Android and iOS; `kvid-sample/androidApp` and the XcodeGen-generated `kvid-sample/iosApp` host it. Create, edit, delete, list, search while typing (whole words), tag chips, version history, close and reopen.
- [x] Background/foreground transitions and recovery after process interruption, checked on running apps in CI. Android API 35 emulator: cold launch about 1.1 s creates and seeds the store; leaving the foreground closes it cleanly; a fresh process reopens it; killing the process with the store open, relaunching and leaving the foreground leaves a recovered, cleanly closed file with `quick_check` ok. iOS simulator: launch creates and seeds the store; moving another app to the front closes it cleanly. The checks read the store file from the device (`kvid-sample/check_store.py`).
- [x] Serialized concurrent calls: `StoreSession` leases defer a background close until running work finishes, and a new use cancels a pending close (sample tests on the desktop JVM and iOS simulator).
- [x] Documented resource lifecycle on the `Kvid` class: one writable handle per path per process, ownership at application scope on Android and iOS, transaction ownership, process death, and `close` semantics. `close()` can no longer be cancelled once called (a cancelled caller used to leave the handle open and its path reserved), and `use { }` closes after success, failure or cancellation; regression tests cover both.
- [x] Basic document history is in the sample (versions oldest to newest, deletions kept).
- [ ] Validate backup/export from a consistent committed snapshot in the sample (the store API and its tests exist; the sample has no export action yet).
- [ ] Publish only targets that meet the acceptance criteria; document packaging for Kotlin and Swift consumers (Maven Central plus an XCFramework).
- [x] Migration statement for the experimental APIs and stored artifacts in the README: the QR/video classes are experimental and planned for `kvid-video`; their `.bin` indexes and MP4/QR artifacts are not compatible with `.kvid` stores and do not migrate automatically; data moves by re-adding text or importing JSON Lines. The README now leads with the store and the sample.

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

- [ ] The iOS app links Compose's ICU data object built for iOS 18.5 while the sample targets iOS 16.0 (linker warning). Run the sample on the minimum iOS version, or raise the sample's deployment target.
- [ ] Search while typing matches whole words only; a prefix option for the last plain-query term is a demonstrated need from the sample. Decide whether to add it to `QuerySyntax.PLAIN`.
- [ ] Compose 1.12 requires the sample to compile against Android API 37; `kvid-core` stays on 36.
- [ ] Android runtime evidence is API 35 only; API 23 and physical devices remain (Milestone 2).
- [ ] Peak memory is still unmeasured.

**First-release boundary:** durable documents, metadata, BM25, basic filters, portable files, and a working mobile sample. No embedding download is required.

## Milestone 5: optional semantic and hybrid search

- [ ] Support one real embedding implementation first; verify tokenizer, pooling, normalization, and output parity across supported platforms.
- [ ] Record the full embedding configuration in the file: model/revision or hash, tokenizer revision, dimensions, pooling, normalization, and distance metric. Refuse to mix models.
- [ ] Treat embeddings as derived data; define missing-model behavior and explicit re-embedding/index migration.
- [ ] Keep exact vector search as the correctness baseline and initial implementation.
- [ ] Introduce HNSW only after measurements establish a benefit. Evaluate recall@k versus latency and memory across parameters and multiple seeded datasets. The current implementation is a reference, not a release candidate: it recomputes the farthest result inside its inner loop and persists as CSV text.
- [ ] Benchmark graph construction and query behavior; avoid fragile wall-clock assertions in ordinary unit tests.
- [ ] Add hybrid search with document-level deduplication and measured reciprocal-rank-fusion settings.
- [ ] Add token-aware chunking when the real tokenizer is available.

ONNX Runtime with a shared model (for example `bge-small-en-v1.5`, 384 dimensions) is the candidate for portable vectors across platforms, not a required default. Platform-native (Apple `NLContextualEmbedding`) and remote models may be separate providers with distinct identities. Do not impose a model download on lexical-only users.

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
