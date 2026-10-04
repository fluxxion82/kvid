# ADR 0001: Storage engine for the kvid document store

_Status: **Proposed** (October 2026). Becomes Accepted when the verification section below is filled in from measurements on all three targets, or Superseded if the fallback is chosen._

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
- Con: minSdk rises from 21 to 23.
- Con: binary size. The bundled SQLite adds native code per ABI on Android and to the iOS framework. To be measured.
- Con: in WAL mode, `-wal` and `-shm` sidecars exist while open. Mitigated by default `DELETE` journal mode and by checkpoint-and-switch on `close()` (contract, section 2).
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

**Option A, SQLite via the androidx.sqlite bundled driver**, provisionally. kvid's identity is the document model and what it does with documents (history, search, portability, optional vectors, archive export), not a storage engine.

The decision is confirmed only when the verification below passes on JVM, Android and iOS. If FTS5 is missing on any target, if the bundled library adds more than roughly 3 MB per ABI, or if snapshot and close do not leave a single file, Option B is revisited.

## Consequences

- `kvid-core` will depend on `androidx.sqlite:sqlite` and `sqlite-bundled`; Android minSdk becomes 23.
- The persistence contract is written against SQLite semantics; the fallback would require re-proving sections 3 to 6.
- A `kvid-storage-spike` module holds the verification tests until Milestone 2 replaces it with the real store. It is deliberately throwaway.
- Vectors start as exact search over blobs. The current `HnswVectorIndex` is not carried into the store.
- The video/QR pipeline is unaffected; it becomes an export of documents read from the store.

## Verification

Tests in `kvid-storage-spike/src/commonTest` run on JVM (Ubuntu CI job) and the iOS simulator (macOS CI job); the Android target compiles in CI and the same tests run as Android host tests only if the bundled driver ships host natives, which is itself one of the facts to establish.

| Check | Where | Result |
|---|---|---|
| `sqlite_version()` and `PRAGMA compile_options` contain `ENABLE_FTS5` | all targets | pending |
| FTS5 table, `MATCH`, `bm25()` ordering, prefix and phrase queries | all targets | pending |
| Uncommitted write lost on close, committed write present on reopen | all targets | pending |
| Exactly one file after close in `DELETE` mode; `-wal`/`-shm` removed after checkpoint and close in WAL mode | all targets | pending |
| `VACUUM INTO` snapshot while open passes `integrity_check` and has the same rows | all targets | pending |
| `float32` vectors round-trip through a BLOB | all targets | pending |
| Ingest and query timings for 5 000 short documents | JVM, iOS simulator | pending |
| Binary size added to an Android APK and an iOS framework | manual | pending |

## When to revisit

- A target where FTS5 is absent or the bundled driver cannot load.
- Measured binary size or open time unacceptable for the sample app.
- A product requirement for a strict single file while open (no `-journal`), which SQLite cannot give in `DELETE` mode during a write.
