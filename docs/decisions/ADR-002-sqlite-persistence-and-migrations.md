# ADR-002: Crash-Safe SQLite/Room Persistence, Adjacency-Scoped Edges, and Cross-Platform Driver Strategy

- **Date:** 2026-11-12
- **Status:** Accepted
- **Owner:** SetFlow Engineering / Agent
- **Stage:** Stage 04 (`04_DATABASE_AND_BACKUP`)

---

## 1. Context & Problem Statement

Stage 04 (`STAGES/04_DATABASE_AND_BACKUP.txt` and `REFERENCE/04_DATA_MODEL_AND_PERSISTENCE.txt`) requires:
1. Relational SQLite persistence for `Project`, `Track`, `Occurrence`, `Edge`, `AutoPlanSnapshot`, `OverrideSnapshot`, `ResolvedPlanSnapshot`, `BeatGridCorrection`, `ProjectBackupRecord`, and `ExportJob`, plus orphan history for deterministic Undo after track reorder or removal.
2. Explicit foreign keys (`PRAGMA foreign_keys = ON`, `ON DELETE RESTRICT`), unique constraints (`UNIQUE(projectId, positionIndex)` on `occurrences`, `UNIQUE(projectId, outgoingOccurrenceId, incomingOccurrenceId)` on `edges`), and monotonic optimistic concurrency revisions (`editRevision`, `planRevision`).
3. Forward schema migrations (`v1 -> v2 -> v3`) with **no destructive migration fallback**, plus read-only recovery export when opening a corrupt or unsupported future schema (`D08`, `ACC-39`).
4. Full execution of golden persistence tests `D01`–`D12` and `100x` mid-save transaction interruption fault injection (`S01`) in offline Gradle test runs on `linux-aarch64`.

### Measured Environment Constraints on `linux-aarch64`
- The project compiles with Kotlin `2.4.21` and Android Gradle Plugin `9.4.1` in offline mode (`--offline`).
- The local Gradle cache contains `androidx.room:room-common-jvm:2.7.0`, `androidx.room:room-runtime-android:2.7.0`, `androidx.sqlite:sqlite-android:2.5.0`, and `org.xerial:sqlite-jdbc:3.41.2.2` (which includes native `org/sqlite/native/Linux/aarch64/libsqlitejdbc.so` and `Linux-Android/aarch64/libsqlitejdbc.so`).
- However, the cached KSP plugin (`2.3.9`) does not match Kotlin `2.4.21`, and Robolectric's shadow SQLite lacks native `linux-aarch64` binaries (documented in Stage 01).

---

## 2. Decision

1. **Use `@Entity`, `@PrimaryKey`, `@ForeignKey`, `@Index`, and `@ColumnInfo` annotations from `androidx.room:room-common:2.7.0`** on all database entity data classes in `app.setflow.db.SetFlowEntities.kt` to document the canonical Room schema contract.
2. **Execute identical SQLite DDL, foreign-key enforcement (`PRAGMA foreign_keys = ON`), WAL journaling (`PRAGMA journal_mode = WAL`), atomic `BEGIN IMMEDIATE` multi-table transactions, and `v1 -> v2 -> v3` migrations through a unified `SetFlowSqlDriver` interface** with two zero-mock native SQLite implementations:
   - **`AndroidSqliteDriver` (`android.database.sqlite.SQLiteDatabase`):** Used on Android devices in production (`SetFlowApplication`).
   - **`JdbcSqliteDriver` (`org.xerial:sqlite-jdbc:3.41.2.2`):** Used in JVM unit/integration tests (`Build.VERSION.SDK_INT == 0`), executing the exact same SQL DDL, foreign key constraints, unique indexes, migrations, and mid-transaction rollback fault injection against real on-disk SQLite `.db` files on `linux-aarch64`.
3. **Add `orphaned_edge_history` and `project_undo_log` tables in Schema `v3`** so that:
   - Reordering `A B C -> A C B` moves old edges (`A->B`, `B->C`) into `orphaned_edge_history` rather than copying overrides onto the new neighbor pairs (`A->C`, `C->B`).
   - Undoing a reorder or track removal (`D04`) deterministically restores the exact prior `edgeId`, `OverrideSnapshot`, and `ResolvedPlanSnapshot` in a single atomic transaction.

---

## 3. Consequences & Verification

- **Positive:**
  - 100% real SQLite execution (foreign keys, unique constraints, WAL/rollback journals, `v1 -> v2 -> v3` migrations, and 100x mid-transaction crash rollback) in both `./gradlew :app:testDebugUnitTest --offline` and Android runtime.
  - Zero network downloads or Kotlin/KSP version mismatches.
  - Zero destructive migration fallbacks; unsupported future schema versions (`> v3`) open in read-only recovery mode (`D08`, `ACC-39`).
- **Verification:**
  - Verified by `ProjectDatabaseStage04Test` covering `D01`–`D12`, `S01` (100x mid-save fault injection), `S16`/`S17` (malformed/oversized backup rejection), and `v1 -> v2 -> v3` schema migrations.
