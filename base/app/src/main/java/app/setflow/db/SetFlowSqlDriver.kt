/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import java.io.Closeable
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

interface SetFlowSqlCursor : Closeable {
    fun next(): Boolean
    fun getString(columnIndex: Int): String?
    fun getLong(columnIndex: Int): Long
    fun getLongOrNull(columnIndex: Int): Long?
    fun getInt(columnIndex: Int): Int
    fun getIntOrNull(columnIndex: Int): Int?
    fun getDouble(columnIndex: Int): Double
    fun getDoubleOrNull(columnIndex: Int): Double?
}

interface SetFlowSqlSession {
    fun execSql(sql: String, bindArgs: Array<Any?> = emptyArray())
    fun <T> query(sql: String, bindArgs: Array<Any?> = emptyArray(), mapper: (SetFlowSqlCursor) -> T): T
}

interface SetFlowSqlDriver : Closeable {
    val dbFile: File
    val openMode: DatabaseOpenMode
    val schemaVersion: Int
    val readOnlyReason: String?

    fun <T> read(block: (SetFlowSqlSession) -> T): T
    fun <T> writeTransaction(block: (SetFlowSqlSession) -> T): T
}

/**
 * Schema DDL and forward migrations v1 -> v2 -> v3 (ADR-002, D06, D08, ACC-26, ACC-39).
 * Destructive fallback migrations are strictly forbidden.
 */
object SetFlowSchemaAndMigrations {
    const val CURRENT_SCHEMA_VERSION = 3

    val SCHEMA_V1_DDL = listOf(
        """
        CREATE TABLE IF NOT EXISTS projects (
            projectId TEXT NOT NULL PRIMARY KEY,
            schemaVersion INTEGER NOT NULL,
            name TEXT NOT NULL,
            createdAtUtc INTEGER NOT NULL,
            updatedAtUtc INTEGER NOT NULL,
            editRevision INTEGER NOT NULL,
            settingsSnapshot TEXT NOT NULL,
            deletedAt INTEGER NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_projects_updated ON projects(updatedAtUtc)",
        """
        CREATE TABLE IF NOT EXISTS tracks (
            trackId TEXT NOT NULL PRIMARY KEY,
            displayName TEXT NOT NULL,
            title TEXT NOT NULL,
            artist TEXT NOT NULL,
            album TEXT NOT NULL,
            durationUs INTEGER NOT NULL,
            contentUri TEXT NOT NULL,
            providerAuthority TEXT NULL,
            mime TEXT NOT NULL,
            codecDescription TEXT NOT NULL,
            sampleRateHz INTEGER NOT NULL,
            channelCount INTEGER NOT NULL,
            bitsPerSample INTEGER NOT NULL,
            size INTEGER NOT NULL,
            modifiedTime INTEGER NOT NULL,
            mediaStoreId INTEGER NULL,
            grantStatus TEXT NOT NULL,
            contentFingerprint TEXT NOT NULL,
            audioFingerprint TEXT NULL,
            fingerprintVersion INTEGER NOT NULL,
            cachedArtworkKey TEXT NULL,
            lastVerified INTEGER NOT NULL,
            availabilityState TEXT NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_tracks_fp ON tracks(contentFingerprint)",
        "CREATE INDEX IF NOT EXISTS idx_tracks_uri ON tracks(contentUri)",
        """
        CREATE TABLE IF NOT EXISTS occurrences (
            occurrenceId TEXT NOT NULL PRIMARY KEY,
            projectId TEXT NOT NULL,
            trackId TEXT NOT NULL,
            positionIndex INTEGER NOT NULL,
            playbackGainDb REAL NULL,
            sourceRangeStartUs INTEGER NULL,
            sourceRangeEndUs INTEGER NULL,
            FOREIGN KEY(projectId) REFERENCES projects(projectId) ON DELETE RESTRICT,
            FOREIGN KEY(trackId) REFERENCES tracks(trackId) ON DELETE RESTRICT
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_occurrences_project_position ON occurrences(projectId, positionIndex)",
        "CREATE INDEX IF NOT EXISTS idx_occurrences_track ON occurrences(trackId)",
        """
        CREATE TABLE IF NOT EXISTS edges (
            edgeId TEXT NOT NULL PRIMARY KEY,
            projectId TEXT NOT NULL,
            outgoingOccurrenceId TEXT NOT NULL,
            incomingOccurrenceId TEXT NOT NULL,
            relationVersion INTEGER NOT NULL,
            planRevision INTEGER NOT NULL,
            origin TEXT NOT NULL,
            lastResolvedFingerprint TEXT NOT NULL,
            lastValidationStatus TEXT NOT NULL,
            FOREIGN KEY(projectId) REFERENCES projects(projectId) ON DELETE RESTRICT,
            FOREIGN KEY(outgoingOccurrenceId) REFERENCES occurrences(occurrenceId) ON DELETE RESTRICT,
            FOREIGN KEY(incomingOccurrenceId) REFERENCES occurrences(occurrenceId) ON DELETE RESTRICT
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_edges_project_pair ON edges(projectId, outgoingOccurrenceId, incomingOccurrenceId)",
        "CREATE INDEX IF NOT EXISTS idx_edges_outgoing ON edges(outgoingOccurrenceId)",
        "CREATE INDEX IF NOT EXISTS idx_edges_incoming ON edges(incomingOccurrenceId)",
        """
        CREATE TABLE IF NOT EXISTS auto_plan_snapshots (
            edgeId TEXT NOT NULL PRIMARY KEY,
            engineVersion TEXT NOT NULL,
            analyzerVersion TEXT NOT NULL,
            parametersJson TEXT NOT NULL,
            analysisKeyA TEXT NOT NULL,
            analysisKeyB TEXT NOT NULL,
            planCanonicalJson TEXT NOT NULL,
            generatedAtUtc INTEGER NOT NULL,
            FOREIGN KEY(edgeId) REFERENCES edges(edgeId) ON DELETE RESTRICT
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS override_snapshots (
            edgeId TEXT NOT NULL PRIMARY KEY,
            overrideSchema INTEGER NOT NULL,
            revision INTEGER NOT NULL,
            exitAnchorUs INTEGER NULL,
            entryAnchorUs INTEGER NULL,
            spanUs INTEGER NULL,
            barCount INTEGER NULL,
            pulseMode TEXT NULL,
            plannedRatePpm INTEGER NULL,
            laneOverridesJson TEXT NULL,
            correctedGridVersionA INTEGER NULL,
            correctedGridVersionB INTEGER NULL,
            editTimestampUtc INTEGER NOT NULL,
            FOREIGN KEY(edgeId) REFERENCES edges(edgeId) ON DELETE RESTRICT
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS resolved_plan_snapshots (
            edgeId TEXT NOT NULL PRIMARY KEY,
            resolutionRevision INTEGER NOT NULL,
            canonicalSegueJson TEXT NOT NULL,
            planHashSha256 TEXT NOT NULL,
            sourceKeys TEXT NOT NULL,
            warnings TEXT NOT NULL,
            validatedAtUtc INTEGER NOT NULL,
            FOREIGN KEY(edgeId) REFERENCES edges(edgeId) ON DELETE RESTRICT
        )
        """.trimIndent()
    )

    val MIGRATION_1_TO_2_DDL = listOf(
        "ALTER TABLE override_snapshots ADD COLUMN presetId TEXT NULL",
        "ALTER TABLE override_snapshots ADD COLUMN userEditedFieldsBitset INTEGER NOT NULL DEFAULT 0",
        """
        CREATE TABLE IF NOT EXISTS beat_grid_corrections (
            trackId TEXT NOT NULL PRIMARY KEY,
            revision INTEGER NOT NULL,
            bpmOverride REAL NULL,
            phaseAnchorUs INTEGER NULL,
            beatsPerBar INTEGER NULL,
            regionStartUs INTEGER NULL,
            regionEndUs INTEGER NULL,
            confidenceOverride REAL NULL,
            createdAtUtc INTEGER NOT NULL,
            FOREIGN KEY(trackId) REFERENCES tracks(trackId) ON DELETE RESTRICT
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS export_jobs (
            jobId TEXT NOT NULL PRIMARY KEY,
            projectId TEXT NOT NULL,
            projectRevision INTEGER NOT NULL,
            format TEXT NOT NULL,
            destinationUri TEXT NOT NULL,
            status TEXT NOT NULL,
            progress REAL NOT NULL,
            plannedDurationFrames INTEGER NOT NULL,
            failureCode TEXT NULL,
            createdAt INTEGER NOT NULL,
            completedAt INTEGER NULL,
            FOREIGN KEY(projectId) REFERENCES projects(projectId) ON DELETE RESTRICT
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_export_jobs_project ON export_jobs(projectId)"
    )

    val MIGRATION_2_TO_3_DDL = listOf(
        """
        CREATE TABLE IF NOT EXISTS project_backup_records (
            backupId TEXT NOT NULL PRIMARY KEY,
            projectId TEXT NOT NULL,
            backupRevision INTEGER NOT NULL,
            fileUri TEXT NOT NULL,
            checksum TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            status TEXT NOT NULL,
            FOREIGN KEY(projectId) REFERENCES projects(projectId) ON DELETE RESTRICT
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_backup_records_project ON project_backup_records(projectId)",
        """
        CREATE TABLE IF NOT EXISTS orphaned_edge_history (
            projectId TEXT NOT NULL,
            outgoingOccurrenceId TEXT NOT NULL,
            incomingOccurrenceId TEXT NOT NULL,
            edgeJson TEXT NOT NULL,
            autoPlanJson TEXT NULL,
            overridePlanJson TEXT NULL,
            resolvedPlanJson TEXT NULL,
            archivedAtUtc INTEGER NOT NULL,
            PRIMARY KEY(projectId, outgoingOccurrenceId, incomingOccurrenceId)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS project_undo_log (
            undoId INTEGER PRIMARY KEY AUTOINCREMENT,
            projectId TEXT NOT NULL,
            priorRevision INTEGER NOT NULL,
            snapshotJson TEXT NOT NULL,
            createdAtUtc INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_undo_project ON project_undo_log(projectId, undoId)"
    )

    val REQUIRED_TABLES_V3 = setOf(
        "projects",
        "tracks",
        "occurrences",
        "edges",
        "auto_plan_snapshots",
        "override_snapshots",
        "resolved_plan_snapshots",
        "beat_grid_corrections",
        "export_jobs",
        "project_backup_records",
        "orphaned_edge_history",
        "project_undo_log"
    )

    fun createDriver(dbFile: File, context: Context? = null): SetFlowSqlDriver {
        dbFile.parentFile?.mkdirs()
        // On Android runtime (Build.VERSION.SDK_INT > 0) or whenever an Android Context is provided,
        // use AndroidSqliteDriver backed by android.database.sqlite.SQLiteDatabase.
        // On desktop JVM unit tests (SDK_INT == 0 without Context), use JdbcSqliteDriver with fallback.
        val preferAndroid = Build.VERSION.SDK_INT > 0 || context != null
        return if (preferAndroid) {
            AndroidSqliteDriver(dbFile)
        } else {
            try {
                JdbcSqliteDriver(dbFile)
            } catch (_: ClassNotFoundException) {
                // Defensive fallback: if sqlite-jdbc is absent, attempt AndroidSqliteDriver
                AndroidSqliteDriver(dbFile)
            }
        }
    }
}

/**
 * JDBC SQLite driver (`org.xerial:sqlite-jdbc`) used on JVM unit/integration tests.
 */
class JdbcSqliteDriver(
    override val dbFile: File,
    targetSchemaVersion: Int = SetFlowSchemaAndMigrations.CURRENT_SCHEMA_VERSION
) : SetFlowSqlDriver {

    private val connection: Connection
    override var openMode: DatabaseOpenMode = DatabaseOpenMode.READ_WRITE
        private set
    override var schemaVersion: Int = 0
        private set
    override var readOnlyReason: String? = null
        private set

    init {
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        connection.autoCommit = true
        val session = JdbcSession(connection)
        session.execSql("PRAGMA foreign_keys = ON")
        session.execSql("PRAGMA busy_timeout = 5000")
        initializeOrMigrate(session, targetSchemaVersion)
    }

    private fun initializeOrMigrate(session: SetFlowSqlSession, targetVersion: Int) {
        val existingVersion = session.query("PRAGMA user_version") { c ->
            if (c.next()) c.getInt(0) else 0
        }
        schemaVersion = existingVersion

        if (existingVersion < 0 || existingVersion > SetFlowSchemaAndMigrations.CURRENT_SCHEMA_VERSION) {
            openMode = DatabaseOpenMode.READ_ONLY_UNSUPPORTED_SCHEMA
            readOnlyReason = "Unsupported database schema version $existingVersion (max supported: ${SetFlowSchemaAndMigrations.CURRENT_SCHEMA_VERSION}). Opened in read-only recovery mode."
            return
        }

        try {
            if (existingVersion == 0) {
                // Check if tables exist without user_version or if brand new DB
                val existingTables = queryTableNames(session)
                if (existingTables.isNotEmpty() && "projects" !in existingTables) {
                    openMode = DatabaseOpenMode.READ_ONLY_UNSUPPORTED_SCHEMA
                    readOnlyReason = "Unrecognized existing tables without projects table ($existingTables). Opened in read-only recovery mode."
                    return
                }
                runRawTransaction(session) {
                    SetFlowSchemaAndMigrations.SCHEMA_V1_DDL.forEach { session.execSql(it) }
                    if (targetVersion >= 2) {
                        SetFlowSchemaAndMigrations.MIGRATION_1_TO_2_DDL.forEach { session.execSql(it) }
                    }
                    if (targetVersion >= 3) {
                        SetFlowSchemaAndMigrations.MIGRATION_2_TO_3_DDL.forEach { session.execSql(it) }
                    }
                    session.execSql("PRAGMA user_version = $targetVersion")
                }
                schemaVersion = targetVersion
            } else {
                var v = existingVersion
                if (v == 1 && targetVersion >= 2) {
                    runRawTransaction(session) {
                        SetFlowSchemaAndMigrations.MIGRATION_1_TO_2_DDL.forEach { session.execSql(it) }
                        session.execSql("PRAGMA user_version = 2")
                    }
                    v = 2
                }
                if (v == 2 && targetVersion >= 3) {
                    runRawTransaction(session) {
                        SetFlowSchemaAndMigrations.MIGRATION_2_TO_3_DDL.forEach { session.execSql(it) }
                        session.execSql("PRAGMA user_version = 3")
                    }
                    v = 3
                }
                schemaVersion = v
            }

            if (schemaVersion == SetFlowSchemaAndMigrations.CURRENT_SCHEMA_VERSION) {
                val tables = queryTableNames(session)
                val missing = SetFlowSchemaAndMigrations.REQUIRED_TABLES_V3 - tables
                if (missing.isNotEmpty()) {
                    openMode = DatabaseOpenMode.READ_ONLY_UNSUPPORTED_SCHEMA
                    readOnlyReason = "Corrupt schema v3: missing required tables $missing"
                }
            }
        } catch (t: Throwable) {
            openMode = DatabaseOpenMode.READ_ONLY_UNSUPPORTED_SCHEMA
            readOnlyReason = "Schema initialization/migration failed: ${t.message}"
        }
    }

    private fun queryTableNames(session: SetFlowSqlSession): Set<String> =
        session.query("SELECT name FROM sqlite_master WHERE type='table'") { c ->
            val out = mutableSetOf<String>()
            while (c.next()) {
                c.getString(0)?.let { out.add(it) }
            }
            out
        }

    private fun <T> runRawTransaction(session: SetFlowSqlSession, block: () -> T): T {
        session.execSql("BEGIN IMMEDIATE")
        var committed = false
        try {
            val res = block()
            session.execSql("COMMIT")
            committed = true
            return res
        } finally {
            if (!committed) {
                runCatching { session.execSql("ROLLBACK") }
            }
        }
    }

    @Synchronized
    override fun <T> read(block: (SetFlowSqlSession) -> T): T {
        val session = JdbcSession(connection)
        return block(session)
    }

    @Synchronized
    override fun <T> writeTransaction(block: (SetFlowSqlSession) -> T): T {
        if (openMode != DatabaseOpenMode.READ_WRITE) {
            throw ReadOnlyUnsupportedSchemaException(
                foundVersion = schemaVersion,
                maxSupportedVersion = SetFlowSchemaAndMigrations.CURRENT_SCHEMA_VERSION,
                message = readOnlyReason ?: "Database is in read-only recovery mode"
            )
        }
        val session = JdbcSession(connection)
        return runRawTransaction(session) {
            block(session)
        }
    }

    @Synchronized
    override fun close() {
        runCatching { connection.close() }
    }

    private class JdbcSession(private val conn: Connection) : SetFlowSqlSession {
        override fun execSql(sql: String, bindArgs: Array<Any?>) {
            if (bindArgs.isEmpty()) {
                conn.createStatement().use { st -> st.execute(sql) }
            } else {
                conn.prepareStatement(sql).use { ps ->
                    bindParams(ps, bindArgs)
                    ps.executeUpdate()
                }
            }
        }

        override fun <T> query(sql: String, bindArgs: Array<Any?>, mapper: (SetFlowSqlCursor) -> T): T {
            conn.prepareStatement(sql).use { ps ->
                bindParams(ps, bindArgs)
                ps.executeQuery().use { rs ->
                    return mapper(JdbcCursor(rs))
                }
            }
        }

        private fun bindParams(ps: java.sql.PreparedStatement, args: Array<Any?>) {
            for (i in args.indices) {
                val idx = i + 1
                when (val v = args[i]) {
                    null -> ps.setNull(idx, Types.NULL)
                    is String -> ps.setString(idx, v)
                    is Long -> ps.setLong(idx, v)
                    is Int -> ps.setInt(idx, v)
                    is Double -> ps.setDouble(idx, v)
                    is Float -> ps.setDouble(idx, v.toDouble())
                    is Boolean -> ps.setInt(idx, if (v) 1 else 0)
                    else -> ps.setString(idx, v.toString())
                }
            }
        }
    }

    private class JdbcCursor(private val rs: java.sql.ResultSet) : SetFlowSqlCursor {
        override fun next(): Boolean = rs.next()
        override fun getString(columnIndex: Int): String? = rs.getString(columnIndex + 1)
        override fun getLong(columnIndex: Int): Long = rs.getLong(columnIndex + 1)
        override fun getLongOrNull(columnIndex: Int): Long? {
            val v = rs.getLong(columnIndex + 1)
            return if (rs.wasNull()) null else v
        }
        override fun getInt(columnIndex: Int): Int = rs.getInt(columnIndex + 1)
        override fun getIntOrNull(columnIndex: Int): Int? {
            val v = rs.getInt(columnIndex + 1)
            return if (rs.wasNull()) null else v
        }
        override fun getDouble(columnIndex: Int): Double = rs.getDouble(columnIndex + 1)
        override fun getDoubleOrNull(columnIndex: Int): Double? {
            val v = rs.getDouble(columnIndex + 1)
            return if (rs.wasNull()) null else v
        }
        override fun close() {
            rs.close()
        }
    }
}

/**
 * Android native [SQLiteDatabase] driver used on Android devices.
 */
class AndroidSqliteDriver(
    override val dbFile: File
) : SetFlowSqlDriver {

    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
    override var openMode: DatabaseOpenMode = DatabaseOpenMode.READ_WRITE
        private set
    override var schemaVersion: Int = 0
        private set
    override var readOnlyReason: String? = null
        private set

    init {
        db.setForeignKeyConstraintsEnabled(true)
        val session = AndroidSession(db)
        val existingVersion = db.version
        schemaVersion = existingVersion
        if (existingVersion < 0 || existingVersion > SetFlowSchemaAndMigrations.CURRENT_SCHEMA_VERSION) {
            openMode = DatabaseOpenMode.READ_ONLY_UNSUPPORTED_SCHEMA
            readOnlyReason = "Unsupported database schema version $existingVersion"
        } else {
            try {
                if (existingVersion == 0) {
                    db.beginTransactionNonExclusive()
                    try {
                        SetFlowSchemaAndMigrations.SCHEMA_V1_DDL.forEach { db.execSQL(it) }
                        SetFlowSchemaAndMigrations.MIGRATION_1_TO_2_DDL.forEach { db.execSQL(it) }
                        SetFlowSchemaAndMigrations.MIGRATION_2_TO_3_DDL.forEach { db.execSQL(it) }
                        db.version = SetFlowSchemaAndMigrations.CURRENT_SCHEMA_VERSION
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                    schemaVersion = SetFlowSchemaAndMigrations.CURRENT_SCHEMA_VERSION
                } else {
                    var v = existingVersion
                    if (v == 1) {
                        db.beginTransactionNonExclusive()
                        try {
                            SetFlowSchemaAndMigrations.MIGRATION_1_TO_2_DDL.forEach { db.execSQL(it) }
                            db.version = 2
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                        v = 2
                    }
                    if (v == 2) {
                        db.beginTransactionNonExclusive()
                        try {
                            SetFlowSchemaAndMigrations.MIGRATION_2_TO_3_DDL.forEach { db.execSQL(it) }
                            db.version = 3
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                        v = 3
                    }
                    schemaVersion = v
                }
            } catch (t: Throwable) {
                openMode = DatabaseOpenMode.READ_ONLY_UNSUPPORTED_SCHEMA
                readOnlyReason = "Migration error: ${t.message}"
            }
        }
    }

    @Synchronized
    override fun <T> read(block: (SetFlowSqlSession) -> T): T = block(AndroidSession(db))

    @Synchronized
    override fun <T> writeTransaction(block: (SetFlowSqlSession) -> T): T {
        if (openMode != DatabaseOpenMode.READ_WRITE) {
            throw ReadOnlyUnsupportedSchemaException(
                foundVersion = schemaVersion,
                maxSupportedVersion = SetFlowSchemaAndMigrations.CURRENT_SCHEMA_VERSION,
                message = readOnlyReason ?: "Database is in read-only recovery mode"
            )
        }
        val session = AndroidSession(db)
        db.beginTransactionNonExclusive()
        try {
            val result = block(session)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    override fun close() {
        runCatching { db.close() }
    }

    private class AndroidSession(private val db: SQLiteDatabase) : SetFlowSqlSession {
        override fun execSql(sql: String, bindArgs: Array<Any?>) {
            if (bindArgs.isEmpty()) db.execSQL(sql) else db.execSQL(sql, bindArgs)
        }

        override fun <T> query(sql: String, bindArgs: Array<Any?>, mapper: (SetFlowSqlCursor) -> T): T {
            val strArgs = bindArgs.map { it?.toString() }.toTypedArray()
            db.rawQuery(sql, strArgs).use { c ->
                return mapper(AndroidCursor(c))
            }
        }
    }

    private class AndroidCursor(private val c: android.database.Cursor) : SetFlowSqlCursor {
        override fun next(): Boolean = c.moveToNext()
        override fun getString(columnIndex: Int): String? = if (c.isNull(columnIndex)) null else c.getString(columnIndex)
        override fun getLong(columnIndex: Int): Long = c.getLong(columnIndex)
        override fun getLongOrNull(columnIndex: Int): Long? = if (c.isNull(columnIndex)) null else c.getLong(columnIndex)
        override fun getInt(columnIndex: Int): Int = c.getInt(columnIndex)
        override fun getIntOrNull(columnIndex: Int): Int? = if (c.isNull(columnIndex)) null else c.getInt(columnIndex)
        override fun getDouble(columnIndex: Int): Double = c.getDouble(columnIndex)
        override fun getDoubleOrNull(columnIndex: Int): Double? = if (c.isNull(columnIndex)) null else c.getDouble(columnIndex)
        override fun close() {
            c.close()
        }
    }
}
