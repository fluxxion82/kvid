# ADR 0001: Storage engine for the kvid document store

_Status: **Accepted for implementation** (October 4, 2026), with outstanding platform and production validation: JVM and the iOS simulator ran every check green on CI run 37176438762 with the identical SQLite 3.50.1 build, and the Android variant compiles against the same artifact. Binary size is carried into Milestone 2 as a measurement, not a blocker._

## Context

kvid is becoming a general-purpose, embedded, single-file searchable document store for Kotlin Multiplatform apps on JVM, Android and iOS (roadmap, "Direction"). The first release must save documents, reopen them safely after crashes, and search them offline, on all three platforms, with a file the user can copy or back up.

The previous design (an exact port of memvid v1) stored nothing durably: text lived only in an MP4 of QR codes and vectors in a CSV. The earlier draft roadmap proposed a custom single-file format with an embedded write-ahead log, modeled on memvid v2. The owner's Codex review asked for the engine choice to be an explicit decision with measured criteria rather than an assumption that a custom engine is required.

The persistence contract in `docs/PERSISTENCE_CONTRACT.md` lists what the engine must provide: atomic commit, crash recovery, a single portable file after close, snapshots while open, one writer with readers, bounded inputs, forward-only migrations, full-text search over current versions, and a reserved path for encryption.

## Options

### A. SQLite through the androidx.sqlite bundled driver

`androidx.sqlite:sqlite` (driver interfaces) plus `androidx.sqlite:sqlite-bundled` (SQLite compiled from source), version 2.7.1 (September 2026). Supported: Android API 23+, JVM, iOS, macOS, Linux, Windows. No system SQLite linkage is needed; the same SQLite build runs on every target.

- Pro: transactions, rollback journal or WAL, hot-journal recovery, `integrity_check`, `VACUUM INTO` snapshots, `busy_timeout` locking, `max_page_count` disk-full simulation, all already correct and tested by SQLite's own suite.
- Pro: FTS5 gives tokenised full-text search with `bm25()` ranking, prefix queries, phrase queries, `highlight()` and `snippet()`, with external-content tables so the text is stored once.
- Pro: one SQLite build on all platforms removes the per-platform behaviour differences that sank memvid v1. `addExtension()` (2.6.0+) leaves a door open for a vector extension later.
- Pro: kvid's effort goes into the document model, history, snapshot and search semantics rather than into a storage engine.
- Con: minSdk rises from 21 to 23, and the Intel iOS simulator target (`iosX64`) must be dropped: version 2.7.1 publishes `iosArm64` and `iosSimulatorArm64` only (first CI run of the spike, run 37176292080).
- Con: binary size. The bundled SQLite adds native code per ABI on Android and to the iOS framework. To be measured.
- Con: in WAL mode, `-wal` and `-shm` sidecars exist while open. Mitigated by default `DELETE` journal mode and by deferring public WAL support until handle coordination and checkpoint/switch failure are specified (contract, section 2).
- Con: vectors are not native. First implementation stores `float32` blobs and scans exactly, which is adequate for on-device corpus sizes; HNSW or an extension comes only if measured necessary (roadmap Milestone 5).
- Con: a dependency on Google's release cadence and on their Kotlin version compatibility.

### B. Append-only document log with rebuildable indexes (fallback)

A kvid-owned file: header, append-only records with checksums, indexes rebuilt on open or persisted as separate segments.

- Pro: smallest format; authoritative documents survive index loss; no third-party native code.
- Con: kvid owns durability (fsync ordering per platform, torn-write detection), locking, compaction and recovery, and must prove each with its own tests on each platform. The full-text index is also kvid's to write.
- Con: every one of the contract's guarantees in sections 3 to 6 is new code.

### C. Custom file with embedded WAL and persisted index segments (memvid v2's shape)

Everything in B plus an embedded write-ahead log and segment catalog.

- Pro: strict single-file at all times; direct control of layout.
- Con: the largest correctness and maintenance burden; memvid needed a year of releases and still ships WAL-checksum and index-cap fixes in 2026.

## Decision

**Option A, SQLite via the androidx.sqlite bundled driver.** kvid's identity is the document model and what it does with documents (history, search, portability, optional vectors, archive export), not a storage engine.

The verification below passed on JVM and iOS and compiles on Android. Remaining condition: if the bundled library adds more than roughly 3 MB per ABI to the sample app, revisit; Android runtime/native loading, genuine process-interruption recovery, snapshot publication, and production error translation also remain implementation gates. The spike establishes engine primitives, not the complete persistence contract.

## Consequences

- `kvid-core` will depend on `androidx.sqlite:sqlite` and `sqlite-bundled`; Android minSdk becomes 23 and the `iosX64` target is removed (Apple Silicon simulators only).
- The persistence contract is written against SQLite semantics; the fallback would require re-proving sections 3 to 6.
- A `kvid-storage-spike` module holds the verification tests until Milestone 2 replaces it with the real store. It is deliberately throwaway.
- Vectors start as exact search over blobs. The current `HnswVectorIndex` is not carried into the store.
- The video/QR pipeline is unaffected; it becomes an export of documents read from the store.

## Verification

Tests in `kvid-storage-spike/src/commonTest` run on JVM (Ubuntu CI job) and the iOS simulator (macOS CI job); the Android target compiles in CI and Android runtime tests are not configured in this module. Compilation does not establish native loading or device behavior.

| Check | Where | Result |
|---|---|---|
| Dependency resolves and compiles | JVM, Android, iosArm64, iosSimulatorArm64 | **yes** (run 37176438762). `iosX64` has no published variant and was dropped from the spike. |
| `sqlite_version()` and `PRAGMA compile_options` contain `ENABLE_FTS5` | JVM | **yes**: SQLite 3.50.1, FTS5 enabled, `DEFAULT_SYNCHRONOUS=2` (FULL), `DEFAULT_WAL_SYNCHRONOUS=1` (NORMAL) |
| | iOS simulator | **yes**: SQLite 3.50.1 with the same compile options as JVM (`ENABLE_FTS5`, `ENABLE_FTS4`, `ENABLE_RTREE`, `ENABLE_MATH_FUNCTIONS`, `THREADSAFE=2`, `SECURE_DELETE`, `TEMP_STORE=3`) |
| FTS5 table, `MATCH`, `bm25()` ordering, prefix, phrase and column queries, `snippet()` | JVM | **pass** |
| | iOS simulator | **pass** |
| External-content FTS kept in sync by triggers; `rebuild` reproduces the index from content | JVM | **pass** |
| | iOS simulator | **pass** |
| Uncommitted write lost on close, committed write present on reopen; read-your-writes inside a transaction | JVM | **pass** |
| | iOS simulator | **pass** |
| Exactly one file after close in `DELETE` mode; `-wal`/`-shm` removed after switching back to `DELETE` and closing | JVM | **pass** |
| | iOS simulator | **pass** |
| `VACUUM INTO` snapshot while open passes `integrity_check` and holds the committed rows only | JVM | **pass** |
| | iOS simulator | **pass** |
| Corruption surfaces as an error (`file is not a database`, code 26), for the invalid-header fixture; arbitrary page corruption is not covered | JVM | **pass** |
| | iOS simulator | **pass** |
| Disk full (`max_page_count`) surfaces as an error (code 13) and leaves state unchanged | JVM | **pass** |
| | iOS simulator | **pass** |
| `float32` vectors round-trip through a BLOB bit-exactly | JVM | **pass** |
| | iOS simulator | **pass** |
| Ingest and query timings, 5 000 documents of 20 to 80 words with FTS5 triggers | JVM (GitHub `ubuntu-latest`) | ingest 349 ms, 50 full-text queries 232 ms, second connection open and count (warm) 3 ms, file 2.5 MiB |
| | iOS simulator (GitHub `macos-latest`, arm64) | ingest 1 181 ms, 50 full-text queries 118 ms, second connection open and count (warm) 5 ms, file 2.5 MiB |
| Android host tests with the bundled driver | Android | not attempted in the spike (no host test builder); the Android variant compiles. To establish before Milestone 2 if host tests are wanted. |
| Binary size added to an Android APK and an iOS framework | manual | pending (needs the sample app) |

## When to revisit

- A target where FTS5 is absent or the bundled driver cannot load.
- Measured binary size or open time unacceptable for the sample app.
- A product requirement for a strict single file while open (no `-journal`), which SQLite cannot give in `DELETE` mode during a write.

## Review corrections and additional evidence

The expanded spike passes 16 tests on JVM and 16 on the Apple Silicon iOS simulator locally, and compiles Android main. Writable spike connections now explicitly use EXTRA and enable foreign keys. Additional cases exercise a real read-only connection, a snapshot while a writer still has staged changes, BUSY with two writers, an active WAL reader preventing journal-mode switching, savepoint rollback, committed ID non-reuse, explicit FULL/NOTADB/READONLY codes, FTS update/delete/rebuild consistency with rank=1 verification, and float raw bits including negative zero and NaN payloads. These are primitive checks, not a kvid implementation.

The timing test opens a second connection while the first remains open; it does not measure cold reopen. Historical CI timings above are synthetic observations, not release budgets. Wall-clock pass/fail thresholds were removed; correctness checks remain.

Orderly close is not a process kill. A replaced invalid header is not arbitrary page-corruption coverage. FULL injected through max_page_count is not a failed-sync test. The driver's message contains error codes, but its exception API does not expose a structured code; production translation remains a gate. Snapshot validation, atomic rename and platform sync remain application work. Binary footprint, peak memory, cold-open and mobile-device measurements remain pending.

See the revised [persistence contract](../PERSISTENCE_CONTRACT.md) for EXTRA versus FULL/NORMAL, committed-only snapshot publication, scoped transaction ownership, current-only search, cursor expiration and retained-history semantics.
