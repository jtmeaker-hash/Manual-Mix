/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.db

import app.myspoti.automix.segue.SegueCodec
import app.myspoti.automix.segue.SegueFormatException
import app.myspoti.automix.segue.SeguePlan
import app.myspoti.automix.segue.SegueSideFacts
import app.myspoti.automix.segue.SegueValidator
import app.myspoti.automix.segue.SegueViolation
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.TrackAvailabilityState
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.util.UUID

/**
 * Crash-safe SQLite persistence layer for SetFlow projects, occurrences, adjacency-scoped
 * transition edges, snapshots, beat-grid corrections, and export jobs (Stage 04, ADR-002).
 */
class SetFlowProjectDatabase(
    val driver: SetFlowSqlDriver,
    private val diagnostics: DiagnosticsRingBuffer? = null,
    private val clockUtcMs: () -> Long = { System.currentTimeMillis() }
) : Closeable {

    companion object {
        /** Maximum allowed track or transition duration: 24 hours in microseconds (D11). */
        const val MAX_DURATION_US: Long = 24L * 60L * 60L * 1_000_000L
        const val MIN_RATE_PPM: Int = 250_000
        const val MAX_RATE_PPM: Int = 4_000_000
        const val MAX_KNOTS_PER_LANE: Int = 256

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    @Serializable
    private data class StructuralUndoSnapshot(
        val projectId: String,
        val priorEditRevision: Long,
        val occurrences: List<OccurrenceEntity>,
        val edges: List<EdgeCompleteSnapshot>
    )

    val openMode: DatabaseOpenMode
        get() = driver.openMode

    val schemaVersion: Int
        get() = driver.schemaVersion

    val readOnlyReason: String?
        get() = driver.readOnlyReason

    // -------------------------------------------------------------------------
    // Track CRUD & Availability (D09)
    // -------------------------------------------------------------------------

    fun upsertTrack(track: TrackEntity) {
        validateTrackBounds(track)
        driver.writeTransaction { s ->
            s.execSql(
                """
                INSERT INTO tracks (
                    trackId, displayName, title, artist, album, durationUs, contentUri,
                    providerAuthority, mime, codecDescription, sampleRateHz, channelCount,
                    bitsPerSample, size, modifiedTime, mediaStoreId, grantStatus,
                    contentFingerprint, audioFingerprint, fingerprintVersion, cachedArtworkKey,
                    lastVerified, availabilityState
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(trackId) DO UPDATE SET
                    displayName = excluded.displayName,
                    title = excluded.title,
                    artist = excluded.artist,
                    album = excluded.album,
                    durationUs = excluded.durationUs,
                    contentUri = excluded.contentUri,
                    providerAuthority = excluded.providerAuthority,
                    mime = excluded.mime,
                    codecDescription = excluded.codecDescription,
                    sampleRateHz = excluded.sampleRateHz,
                    channelCount = excluded.channelCount,
                    bitsPerSample = excluded.bitsPerSample,
                    size = excluded.size,
                    modifiedTime = excluded.modifiedTime,
                    mediaStoreId = excluded.mediaStoreId,
                    grantStatus = excluded.grantStatus,
                    contentFingerprint = excluded.contentFingerprint,
                    audioFingerprint = COALESCE(excluded.audioFingerprint, tracks.audioFingerprint),
                    fingerprintVersion = excluded.fingerprintVersion,
                    cachedArtworkKey = COALESCE(excluded.cachedArtworkKey, tracks.cachedArtworkKey),
                    lastVerified = excluded.lastVerified,
                    availabilityState = excluded.availabilityState
                """.trimIndent(),
                arrayOf(
                    track.trackId,
                    track.displayName,
                    track.title,
                    track.artist,
                    track.album,
                    track.durationUs,
                    track.contentUri,
                    track.providerAuthority,
                    track.mime,
                    track.codecDescription,
                    track.sampleRateHz,
                    track.channelCount,
                    track.bitsPerSample,
                    track.size,
                    track.modifiedTime,
                    track.mediaStoreId,
                    track.grantStatus,
                    track.contentFingerprint,
                    track.audioFingerprint,
                    track.fingerprintVersion,
                    track.cachedArtworkKey,
                    track.lastVerified,
                    track.availabilityState
                )
            )
        }
    }

    fun getTrack(trackId: String): TrackEntity? = driver.read { s ->
        queryTrackById(s, trackId)
    }

    fun listTracks(): List<TrackEntity> = driver.read { s ->
        s.query(
            """
            SELECT trackId, displayName, title, artist, album, durationUs, contentUri,
                   providerAuthority, mime, codecDescription, sampleRateHz, channelCount,
                   bitsPerSample, size, modifiedTime, mediaStoreId, grantStatus,
                   contentFingerprint, audioFingerprint, fingerprintVersion, cachedArtworkKey,
                   lastVerified, availabilityState
            FROM tracks
            ORDER BY lastVerified DESC, trackId ASC
            """.trimIndent()
        ) { c ->
            val list = mutableListOf<TrackEntity>()
            while (c.next()) {
                list.add(readTrackRow(c))
            }
            list
        }
    }

    fun updateTrackAvailability(
        trackId: String,
        availabilityState: TrackAvailabilityState,
        newContentUri: String? = null
    ) {
        driver.writeTransaction { s ->
            val now = clockUtcMs()
            if (newContentUri != null) {
                s.execSql(
                    "UPDATE tracks SET availabilityState = ?, contentUri = ?, lastVerified = ? WHERE trackId = ?",
                    arrayOf(availabilityState.name, newContentUri, now, trackId)
                )
            } else {
                s.execSql(
                    "UPDATE tracks SET availabilityState = ?, lastVerified = ? WHERE trackId = ?",
                    arrayOf(availabilityState.name, now, trackId)
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Project CRUD (Task 2)
    // -------------------------------------------------------------------------

    fun createProject(
        name: String,
        settingsSnapshot: String = "{}",
        projectId: String = "proj_${UUID.randomUUID()}"
    ): ProjectEntity {
        val trimmed = name.trim().ifEmpty { "Untitled Set" }
        val now = clockUtcMs()
        val entity = ProjectEntity(
            projectId = projectId,
            schemaVersion = ProjectEntity.CURRENT_PROJECT_SCHEMA_VERSION,
            name = trimmed,
            createdAtUtc = now,
            updatedAtUtc = now,
            editRevision = 1L,
            settingsSnapshot = settingsSnapshot,
            deletedAt = null
        )
        driver.writeTransaction { s ->
            insertProject(s, entity)
        }
        return entity
    }

    fun renameProject(
        projectId: String,
        expectedRevision: Long,
        newName: String
    ): ProjectEntity = driver.writeTransaction { s ->
        val current = requireActiveProjectAndRevision(s, projectId, expectedRevision)
        val trimmed = newName.trim().ifEmpty { current.name }
        val updated = current.copy(
            name = trimmed,
            updatedAtUtc = clockUtcMs(),
            editRevision = current.editRevision + 1L
        )
        s.execSql(
            "UPDATE projects SET name = ?, updatedAtUtc = ?, editRevision = ? WHERE projectId = ?",
            arrayOf(updated.name, updated.updatedAtUtc, updated.editRevision, projectId)
        )
        updated
    }

    fun softDeleteProject(projectId: String, expectedRevision: Long) {
        driver.writeTransaction { s ->
            val current = requireActiveProjectAndRevision(s, projectId, expectedRevision)
            val now = clockUtcMs()
            s.execSql(
                "UPDATE projects SET deletedAt = ?, updatedAtUtc = ?, editRevision = ? WHERE projectId = ?",
                arrayOf(now, now, current.editRevision + 1L, projectId)
            )
        }
    }

    fun duplicateProject(
        sourceProjectId: String,
        newName: String? = null,
        newProjectId: String = "proj_${UUID.randomUUID()}"
    ): ProjectDetailSnapshot = driver.writeTransaction { s ->
        val srcDetail = queryProjectDetail(s, sourceProjectId)
            ?: throw IllegalArgumentException("Project not found: $sourceProjectId")
        val now = clockUtcMs()
        val copyProject = ProjectEntity(
            projectId = newProjectId,
            schemaVersion = ProjectEntity.CURRENT_PROJECT_SCHEMA_VERSION,
            name = newName?.trim()?.takeIf { it.isNotEmpty() } ?: "${srcDetail.project.name} (Copy)",
            createdAtUtc = now,
            updatedAtUtc = now,
            editRevision = 1L,
            settingsSnapshot = srcDetail.project.settingsSnapshot,
            deletedAt = null
        )
        insertProject(s, copyProject)

        val occIdMap = LinkedHashMap<String, String>()
        for (occWithTrack in srcDetail.occurrences) {
            val oldOcc = occWithTrack.occurrence
            val newOccId = "occ_${UUID.randomUUID()}"
            occIdMap[oldOcc.occurrenceId] = newOccId
            insertOccurrence(
                s,
                oldOcc.copy(
                    occurrenceId = newOccId,
                    projectId = newProjectId
                )
            )
        }

        for (edgeSnap in srcDetail.edges) {
            val newOut = occIdMap[edgeSnap.edge.outgoingOccurrenceId] ?: continue
            val newIn = occIdMap[edgeSnap.edge.incomingOccurrenceId] ?: continue
            val newEdgeId = "edge_${UUID.randomUUID()}"
            insertEdgeCompleteSnapshot(
                s,
                EdgeCompleteSnapshot(
                    edge = edgeSnap.edge.copy(
                        edgeId = newEdgeId,
                        projectId = newProjectId,
                        outgoingOccurrenceId = newOut,
                        incomingOccurrenceId = newIn
                    ),
                    autoPlan = edgeSnap.autoPlan?.copy(edgeId = newEdgeId),
                    overridePlan = edgeSnap.overridePlan?.copy(edgeId = newEdgeId),
                    resolvedPlan = edgeSnap.resolvedPlan?.copy(edgeId = newEdgeId)
                )
            )
        }

        validateProjectStructuralInvariants(s, newProjectId)
        queryProjectDetail(s, newProjectId)!!
    }

    fun getProject(projectId: String): ProjectEntity? = driver.read { s ->
        queryProjectById(s, projectId)
    }

    fun listProjects(includeDeleted: Boolean = false): List<ProjectEntity> = driver.read { s ->
        val sql = if (includeDeleted) {
            """
            SELECT projectId, schemaVersion, name, createdAtUtc, updatedAtUtc, editRevision, settingsSnapshot, deletedAt
            FROM projects
            ORDER BY updatedAtUtc DESC, projectId ASC
            """.trimIndent()
        } else {
            """
            SELECT projectId, schemaVersion, name, createdAtUtc, updatedAtUtc, editRevision, settingsSnapshot, deletedAt
            FROM projects
            WHERE deletedAt IS NULL
            ORDER BY updatedAtUtc DESC, projectId ASC
            """.trimIndent()
        }
        s.query(sql) { c ->
            val list = mutableListOf<ProjectEntity>()
            while (c.next()) {
                list.add(readProjectRow(c))
            }
            list
        }
    }

    fun getProjectDetail(projectId: String): ProjectDetailSnapshot? = driver.read { s ->
        queryProjectDetail(s, projectId)
    }

    // -------------------------------------------------------------------------
    // Occurrences, Reorder, Remove, and Undo (Tasks 2 & 3, D01-D05)
    // -------------------------------------------------------------------------

    fun addTracksToProject(
        projectId: String,
        expectedRevision: Long,
        trackIds: List<String>,
        faultInjectorBeforeCommit: (() -> Unit)? = null
    ): ProjectDetailSnapshot {
        if (trackIds.isEmpty()) {
            return getProjectDetail(projectId)
                ?: throw IllegalArgumentException("Project not found: $projectId")
        }
        return driver.writeTransaction { s ->
            val project = requireActiveProjectAndRevision(s, projectId, expectedRevision)
            for (tid in trackIds) {
                if (queryTrackById(s, tid) == null) {
                    throw IllegalArgumentException("Track not found in database: $tid")
                }
            }
            val currentOccurrences = queryOccurrencesForProject(s, projectId)
            val currentEdges = queryEdgesForProject(s, projectId)
            pushStructuralUndoSnapshot(s, project, currentOccurrences, currentEdges)

            val startPos = currentOccurrences.size
            val newOccurrences = currentOccurrences.toMutableList()
            trackIds.forEachIndexed { idx, tid ->
                val occ = OccurrenceEntity(
                    occurrenceId = "occ_${UUID.randomUUID()}",
                    projectId = projectId,
                    trackId = tid,
                    positionIndex = startPos + idx
                )
                insertOccurrence(s, occ)
                newOccurrences.add(occ)
            }

            reconcileAdjacentEdges(
                s = s,
                projectId = projectId,
                orderedOccurrences = newOccurrences,
                existingEdges = currentEdges,
                restoreMatchingOrphanedEdges = false
            )

            faultInjectorBeforeCommit?.invoke()

            val nextRevision = project.editRevision + 1L
            s.execSql(
                "UPDATE projects SET updatedAtUtc = ?, editRevision = ? WHERE projectId = ?",
                arrayOf(clockUtcMs(), nextRevision, projectId)
            )
            validateProjectStructuralInvariants(s, projectId)
            queryProjectDetail(s, projectId)!!
        }
    }

    fun reorderOccurrences(
        projectId: String,
        expectedRevision: Long,
        newOrderedOccurrenceIds: List<String>,
        restoreMatchingOrphanedEdges: Boolean = false,
        faultInjectorBeforeCommit: (() -> Unit)? = null
    ): ProjectDetailSnapshot = driver.writeTransaction { s ->
        val project = requireActiveProjectAndRevision(s, projectId, expectedRevision)
        val currentOccurrences = queryOccurrencesForProject(s, projectId)
        val currentById = currentOccurrences.associateBy { it.occurrenceId }

        if (newOrderedOccurrenceIds.size != currentOccurrences.size ||
            newOrderedOccurrenceIds.toSet() != currentById.keys
        ) {
            throw IllegalArgumentException(
                "Reorder occurrence IDs must be an exact permutation of current occurrences (${currentById.keys})"
            )
        }

        val currentEdges = queryEdgesForProject(s, projectId)
        pushStructuralUndoSnapshot(s, project, currentOccurrences, currentEdges)

        // Step 1: Move all positions to temporary negative values to avoid transient UNIQUE(projectId, positionIndex) collisions
        s.execSql(
            "UPDATE occurrences SET positionIndex = -(positionIndex + 100000) WHERE projectId = ?",
            arrayOf(projectId)
        )

        // Step 2: Assign contiguous 0..N-1 positions
        val reorderedOccurrences = newOrderedOccurrenceIds.mapIndexed { index, occId ->
            val updated = currentById.getValue(occId).copy(positionIndex = index)
            s.execSql(
                "UPDATE occurrences SET positionIndex = ? WHERE occurrenceId = ? AND projectId = ?",
                arrayOf(index, occId, projectId)
            )
            updated
        }

        // Step 3: Reconcile adjacent edges (old overrides NEVER copy onto new pairs!)
        reconcileAdjacentEdges(
            s = s,
            projectId = projectId,
            orderedOccurrences = reorderedOccurrences,
            existingEdges = currentEdges,
            restoreMatchingOrphanedEdges = restoreMatchingOrphanedEdges
        )

        faultInjectorBeforeCommit?.invoke()

        val nextRevision = project.editRevision + 1L
        s.execSql(
            "UPDATE projects SET updatedAtUtc = ?, editRevision = ? WHERE projectId = ?",
            arrayOf(clockUtcMs(), nextRevision, projectId)
        )
        validateProjectStructuralInvariants(s, projectId)
        queryProjectDetail(s, projectId)!!
    }

    fun removeOccurrence(
        projectId: String,
        expectedRevision: Long,
        occurrenceId: String,
        faultInjectorBeforeCommit: (() -> Unit)? = null
    ): ProjectDetailSnapshot = driver.writeTransaction { s ->
        val project = requireActiveProjectAndRevision(s, projectId, expectedRevision)
        val currentOccurrences = queryOccurrencesForProject(s, projectId)
        if (currentOccurrences.none { it.occurrenceId == occurrenceId }) {
            throw IllegalArgumentException("Occurrence $occurrenceId not found in project $projectId")
        }
        val currentEdges = queryEdgesForProject(s, projectId)
        pushStructuralUndoSnapshot(s, project, currentOccurrences, currentEdges)

        val remaining = currentOccurrences
            .filterNot { it.occurrenceId == occurrenceId }
            .mapIndexed { idx, occ -> occ.copy(positionIndex = idx) }

        // First reconcile edges so any edge referencing the removed occurrence is archived & deleted BEFORE deleting the occurrence (FK ON DELETE RESTRICT)
        reconcileAdjacentEdges(
            s = s,
            projectId = projectId,
            orderedOccurrences = remaining,
            existingEdges = currentEdges,
            restoreMatchingOrphanedEdges = false
        )

        // Delete the removed occurrence
        s.execSql(
            "DELETE FROM occurrences WHERE occurrenceId = ? AND projectId = ?",
            arrayOf(occurrenceId, projectId)
        )

        // Shift remaining to negative then assign 0..N-1
        s.execSql(
            "UPDATE occurrences SET positionIndex = -(positionIndex + 100000) WHERE projectId = ?",
            arrayOf(projectId)
        )
        for (occ in remaining) {
            s.execSql(
                "UPDATE occurrences SET positionIndex = ? WHERE occurrenceId = ? AND projectId = ?",
                arrayOf(occ.positionIndex, occ.occurrenceId, projectId)
            )
        }

        faultInjectorBeforeCommit?.invoke()

        val nextRevision = project.editRevision + 1L
        s.execSql(
            "UPDATE projects SET updatedAtUtc = ?, editRevision = ? WHERE projectId = ?",
            arrayOf(clockUtcMs(), nextRevision, projectId)
        )
        validateProjectStructuralInvariants(s, projectId)
        queryProjectDetail(s, projectId)!!
    }

    /**
     * Undoes the most recent structural change (reorder, add, or remove occurrence),
     * restoring exact occurrence positions and exact prior edge IDs and snapshots (D04).
     */
    fun undoLastStructuralChange(
        projectId: String,
        expectedRevision: Long
    ): ProjectDetailSnapshot = driver.writeTransaction { s ->
        val project = requireActiveProjectAndRevision(s, projectId, expectedRevision)
        val undoRow = s.query(
            """
            SELECT undoId, snapshotJson
            FROM project_undo_log
            WHERE projectId = ?
            ORDER BY undoId DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(projectId)
        ) { c ->
            if (c.next()) Pair(c.getLong(0), c.getString(1)!!) else null
        } ?: throw IllegalStateException("No undo history available for project $projectId")

        val (undoId, snapshotJson) = undoRow
        val snapshot = json.decodeFromString<StructuralUndoSnapshot>(snapshotJson)

        // Delete all active edges & their snapshots first (FK ON DELETE RESTRICT)
        val currentEdges = queryEdgesForProject(s, projectId)
        for (e in currentEdges) {
            deleteEdgeAndSnapshots(s, e.edge.edgeId)
        }

        // Delete all current occurrences for project
        s.execSql("DELETE FROM occurrences WHERE projectId = ?", arrayOf(projectId))

        // Re-insert prior occurrences
        for (occ in snapshot.occurrences.sortedBy { it.positionIndex }) {
            insertOccurrence(s, occ)
        }

        // Re-insert exact prior edges and their auto/override/resolved snapshots
        for (edgeSnap in snapshot.edges) {
            insertEdgeCompleteSnapshot(s, edgeSnap)
        }

        // Pop the consumed undo record
        s.execSql("DELETE FROM project_undo_log WHERE undoId = ?", arrayOf(undoId))

        val nextRevision = project.editRevision + 1L
        s.execSql(
            "UPDATE projects SET updatedAtUtc = ?, editRevision = ? WHERE projectId = ?",
            arrayOf(clockUtcMs(), nextRevision, projectId)
        )
        validateProjectStructuralInvariants(s, projectId)
        queryProjectDetail(s, projectId)!!
    }

    // -------------------------------------------------------------------------
    // Transition Plans, Overrides, Draft Derivation, and Reanalysis (Task 4, D01, D03, D10-D12)
    // -------------------------------------------------------------------------

    fun saveAutoPlanSnapshot(
        edgeId: String,
        autoPlan: AutoPlanSnapshotEntity,
        resolvedCanonicalJson: String? = null,
        outgoingFacts: SegueSideFacts? = null,
        incomingFacts: SegueSideFacts? = null
    ): EdgeCompleteSnapshot = driver.writeTransaction { s ->
        val edge = queryEdgeById(s, edgeId)
            ?: throw IllegalArgumentException("Edge not found: $edgeId")
        val parsedAuto = parseAndValidateSegueJson(autoPlan.planCanonicalJson, outgoingFacts, incomingFacts)
        upsertAutoPlanSnapshot(s, autoPlan.copy(edgeId = edgeId))

        val existingOverride = queryOverrideSnapshot(s, edgeId)
        if (existingOverride == null && resolvedCanonicalJson != null) {
            val parsedResolved = if (resolvedCanonicalJson == autoPlan.planCanonicalJson) {
                parsedAuto
            } else {
                parseAndValidateSegueJson(resolvedCanonicalJson, outgoingFacts, incomingFacts)
            }
            val hash = SegueCodec.fingerprint(parsedResolved)
            val now = clockUtcMs()
            val nextPlanRev = edge.planRevision + 1L
            upsertResolvedPlanSnapshot(
                s,
                ResolvedPlanSnapshotEntity(
                    edgeId = edgeId,
                    resolutionRevision = nextPlanRev,
                    canonicalSegueJson = SegueCodec.encode(parsedResolved),
                    planHashSha256 = hash,
                    sourceKeys = "${autoPlan.analysisKeyA}:${autoPlan.analysisKeyB}",
                    warnings = "",
                    validatedAtUtc = now
                )
            )
            s.execSql(
                """
                UPDATE edges
                SET planRevision = ?, origin = ?, lastResolvedFingerprint = ?, lastValidationStatus = ?
                WHERE edgeId = ?
                """.trimIndent(),
                arrayOf(
                    nextPlanRev,
                    EdgeOrigin.AUTO.name,
                    hash,
                    EdgeValidationStatus.VALID.name,
                    edgeId
                )
            )
        }
        queryEdgeCompleteSnapshot(s, edgeId)!!
    }

    /**
     * Derives an in-memory editor draft by combining [AutoPlanSnapshotEntity] and [OverrideSnapshotEntity]
     * without mutating the database. Cancelling the editor simply discards this draft (D12).
     */
    fun deriveEditorDraft(edgeId: String): EdgeDraftState = driver.read { s ->
        val edgeSnap = queryEdgeCompleteSnapshot(s, edgeId)
            ?: throw IllegalArgumentException("Edge not found: $edgeId")
        val autoPlan = edgeSnap.autoPlan?.planCanonicalJson?.let { runCatching { SegueCodec.decode(it) }.getOrNull() }
        val ov = edgeSnap.overridePlan
        val bitset = ov?.userEditedFieldsBitset ?: 0

        fun hasBit(bit: Int): Boolean = (bitset and bit) != 0

        val exitAnchorUs = if (ov != null && (hasBit(OverrideSnapshotEntity.BIT_EXIT_ANCHOR) || ov.exitAnchorUs != null)) {
            ov.exitAnchorUs ?: autoPlan?.outgoing?.anchorUs ?: 0L
        } else {
            autoPlan?.outgoing?.anchorUs ?: 0L
        }

        val entryAnchorUs = if (ov != null && (hasBit(OverrideSnapshotEntity.BIT_ENTRY_ANCHOR) || ov.entryAnchorUs != null)) {
            ov.entryAnchorUs ?: autoPlan?.incoming?.anchorUs ?: 0L
        } else {
            autoPlan?.incoming?.anchorUs ?: 0L
        }

        val spanUs = if (ov != null && (hasBit(OverrideSnapshotEntity.BIT_SPAN) || ov.spanUs != null)) {
            ov.spanUs ?: autoPlan?.span?.lengthUs ?: 8_000_000L
        } else {
            autoPlan?.span?.lengthUs ?: 8_000_000L
        }

        val barCount = if (ov != null && (hasBit(OverrideSnapshotEntity.BIT_BAR_COUNT) || ov.barCount != null)) {
            ov.barCount
        } else {
            autoPlan?.span?.musicalUnits
        }

        val pulseMode = if (ov != null && (hasBit(OverrideSnapshotEntity.BIT_PULSE_MODE) || ov.pulseMode != null)) {
            ov.pulseMode
        } else {
            autoPlan?.pulse?.state?.wire
        }

        val plannedRatePpm = if (ov != null && (hasBit(OverrideSnapshotEntity.BIT_PLANNED_RATE) || ov.plannedRatePpm != null)) {
            ov.plannedRatePpm ?: autoPlan?.incoming?.ratePpm?.toInt() ?: 1_000_000
        } else {
            autoPlan?.incoming?.ratePpm?.toInt() ?: 1_000_000
        }

        EdgeDraftState(
            edgeId = edgeId,
            basePlanRevision = edgeSnap.edge.planRevision,
            exitAnchorUs = exitAnchorUs,
            entryAnchorUs = entryAnchorUs,
            spanUs = spanUs,
            barCount = barCount,
            pulseMode = pulseMode,
            plannedRatePpm = plannedRatePpm,
            laneOverridesJson = ov?.laneOverridesJson,
            presetId = ov?.presetId,
            userEditedFieldsBitset = bitset
        )
    }

    /**
     * Atomically validates and saves a user [OverrideSnapshotEntity] + [ResolvedPlanSnapshotEntity],
     * checking both edge planRevision and optional project editRevision to prevent stale or wrong-neighbor saves (D03, D05, D11, S01, S16, S28).
     */
    fun saveEdgeOverrideAndResolvedPlan(
        edgeId: String,
        expectedEdgePlanRevision: Long,
        overrideSnapshot: OverrideSnapshotEntity,
        canonicalSegueJson: String,
        outgoingFacts: SegueSideFacts? = null,
        incomingFacts: SegueSideFacts? = null,
        expectedProjectRevision: Long? = null,
        expectedOutgoingOccurrenceId: String? = null,
        expectedIncomingOccurrenceId: String? = null,
        faultInjectorBeforeCommit: (() -> Unit)? = null
    ): EdgeCompleteSnapshot {
        // Validate override bounds & SeguePlan before acquiring write lock
        validateOverrideBounds(overrideSnapshot)
        val parsedPlan = parseAndValidateSegueJson(canonicalSegueJson, outgoingFacts, incomingFacts)
        val canonicalEncoded = SegueCodec.encode(parsedPlan)
        val planHash = SegueCodec.fingerprint(parsedPlan)

        return try {
            driver.writeTransaction { s ->
                val edge = queryEdgeById(s, edgeId)
                    ?: run {
                        diagnostics?.record(
                            category = "SetFlowDB",
                            errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                            message = "Save rejected: edge $edgeId no longer exists (likely removed or reordered)"
                        )
                        throw OptimisticConcurrencyException(
                            entityType = "Edge",
                            entityId = edgeId,
                            expectedRevision = expectedEdgePlanRevision,
                            actualRevision = -1L
                        )
                    }

                val project = queryProjectById(s, edge.projectId)
                    ?: throw IllegalStateException("Project ${edge.projectId} missing for edge $edgeId")

                if (expectedProjectRevision != null && project.editRevision != expectedProjectRevision) {
                    diagnostics?.record(
                        category = "SetFlowDB",
                        errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                        message = "Save rejected: project ${project.projectId} revision changed from $expectedProjectRevision to ${project.editRevision}"
                    )
                    throw OptimisticConcurrencyException(
                        entityType = "Project",
                        entityId = project.projectId,
                        expectedRevision = expectedProjectRevision,
                        actualRevision = project.editRevision
                    )
                }

                if (expectedOutgoingOccurrenceId != null && edge.outgoingOccurrenceId != expectedOutgoingOccurrenceId ||
                    expectedIncomingOccurrenceId != null && edge.incomingOccurrenceId != expectedIncomingOccurrenceId
                ) {
                    diagnostics?.record(
                        category = "SetFlowDB",
                        errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                        message = "Save rejected: neighbor pair mismatch on edge $edgeId"
                    )
                    throw OptimisticConcurrencyException(
                        entityType = "EdgePair",
                        entityId = edgeId,
                        expectedRevision = expectedEdgePlanRevision,
                        actualRevision = edge.planRevision
                    )
                }

                if (edge.planRevision != expectedEdgePlanRevision) {
                    diagnostics?.record(
                        category = "SetFlowDB",
                        errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                        message = "Save rejected: edge $edgeId revision expected $expectedEdgePlanRevision but was ${edge.planRevision}"
                    )
                    throw OptimisticConcurrencyException(
                        entityType = "Edge",
                        entityId = edgeId,
                        expectedRevision = expectedEdgePlanRevision,
                        actualRevision = edge.planRevision
                    )
                }

                val now = clockUtcMs()
                val nextPlanRev = edge.planRevision + 1L

                // 1. Write OverrideSnapshot first
                upsertOverrideSnapshot(
                    s,
                    overrideSnapshot.copy(
                        edgeId = edgeId,
                        revision = nextPlanRev,
                        editTimestampUtc = now
                    )
                )

                // Fault injection hook between OverrideSnapshot and ResolvedPlanSnapshot to prove atomic rollback (D05, S01)
                faultInjectorBeforeCommit?.invoke()

                // 2. Write ResolvedPlanSnapshot
                val sourceKeys = "${parsedPlan.outgoing.audioKey}:${parsedPlan.incoming.audioKey}"
                upsertResolvedPlanSnapshot(
                    s,
                    ResolvedPlanSnapshotEntity(
                        edgeId = edgeId,
                        resolutionRevision = nextPlanRev,
                        canonicalSegueJson = canonicalEncoded,
                        planHashSha256 = planHash,
                        sourceKeys = sourceKeys,
                        warnings = "",
                        validatedAtUtc = now
                    )
                )

                // 3. Update Edge metadata & Project revision
                s.execSql(
                    """
                    UPDATE edges
                    SET planRevision = ?, origin = ?, lastResolvedFingerprint = ?, lastValidationStatus = ?
                    WHERE edgeId = ?
                    """.trimIndent(),
                    arrayOf(
                        nextPlanRev,
                        EdgeOrigin.CUSTOM.name,
                        planHash,
                        EdgeValidationStatus.VALID.name,
                        edgeId
                    )
                )

                s.execSql(
                    "UPDATE projects SET updatedAtUtc = ?, editRevision = ? WHERE projectId = ?",
                    arrayOf(now, project.editRevision + 1L, project.projectId)
                )

                queryEdgeCompleteSnapshot(s, edgeId)!!
            }
        } catch (t: Throwable) {
            if (t !is OptimisticConcurrencyException && t !is InvalidPlanPersistenceException) {
                diagnostics?.record(
                    category = "SetFlowDB",
                    errorCode = AudioErrorCode.A10_EXPORT_IO_FAILURE,
                    message = "Transaction rolled back on edge $edgeId: ${t.message}"
                )
            }
            throw t
        }
    }

    /**
     * Records a background AutoPlan reanalysis on [edgeId].
     * Never deletes or overwrites an existing user [OverrideSnapshotEntity] (D10).
     * Marks [EdgeEntity.lastValidationStatus] as [EdgeValidationStatus.STALE_AUDIO_KEY] or [EdgeValidationStatus.STALE_ANALYSIS].
     */
    fun recordBackgroundAutoPlanReanalysis(
        edgeId: String,
        newAutoPlan: AutoPlanSnapshotEntity
    ): EdgeCompleteSnapshot = driver.writeTransaction { s ->
        val edgeSnap = queryEdgeCompleteSnapshot(s, edgeId)
            ?: throw IllegalArgumentException("Edge not found: $edgeId")
        val previousAuto = edgeSnap.autoPlan
        val audioKeyChanged = previousAuto != null && (
            previousAuto.analysisKeyA != newAutoPlan.analysisKeyA ||
                previousAuto.analysisKeyB != newAutoPlan.analysisKeyB
            )

        upsertAutoPlanSnapshot(s, newAutoPlan.copy(edgeId = edgeId))

        if (edgeSnap.overridePlan != null) {
            val newStatus = if (audioKeyChanged) {
                EdgeValidationStatus.STALE_AUDIO_KEY
            } else {
                EdgeValidationStatus.STALE_ANALYSIS
            }
            s.execSql(
                "UPDATE edges SET lastValidationStatus = ? WHERE edgeId = ?",
                arrayOf(newStatus.name, edgeId)
            )
        } else {
            // No user override exists; mark resolved plan stale if one existed
            if (edgeSnap.resolvedPlan != null) {
                val newStatus = if (audioKeyChanged) {
                    EdgeValidationStatus.STALE_AUDIO_KEY
                } else {
                    EdgeValidationStatus.STALE_ANALYSIS
                }
                s.execSql(
                    "UPDATE edges SET lastValidationStatus = ? WHERE edgeId = ?",
                    arrayOf(newStatus.name, edgeId)
                )
            }
        }
        queryEdgeCompleteSnapshot(s, edgeId)!!
    }

    // -------------------------------------------------------------------------
    // BeatGridCorrection & ExportJob CRUD (Task 1)
    // -------------------------------------------------------------------------

    fun saveBeatGridCorrection(correction: BeatGridCorrectionEntity) {
        if (correction.bpmOverride != null &&
            (!correction.bpmOverride.isFinite() || correction.bpmOverride !in 20.0..300.0)
        ) {
            throw InvalidPlanPersistenceException(
                errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                message = "Invalid bpmOverride: ${correction.bpmOverride}"
            )
        }
        if (correction.confidenceOverride != null &&
            (!correction.confidenceOverride.isFinite() || correction.confidenceOverride !in 0.0..1.0)
        ) {
            throw InvalidPlanPersistenceException(
                errorCode = AudioErrorCode.A04_BAD_ANALYSIS,
                message = "Invalid confidenceOverride: ${correction.confidenceOverride}"
            )
        }
        driver.writeTransaction { s ->
            s.execSql(
                """
                INSERT INTO beat_grid_corrections (
                    trackId, revision, bpmOverride, phaseAnchorUs, beatsPerBar,
                    regionStartUs, regionEndUs, confidenceOverride, createdAtUtc
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(trackId) DO UPDATE SET
                    revision = excluded.revision,
                    bpmOverride = excluded.bpmOverride,
                    phaseAnchorUs = excluded.phaseAnchorUs,
                    beatsPerBar = excluded.beatsPerBar,
                    regionStartUs = excluded.regionStartUs,
                    regionEndUs = excluded.regionEndUs,
                    confidenceOverride = excluded.confidenceOverride,
                    createdAtUtc = excluded.createdAtUtc
                """.trimIndent(),
                arrayOf(
                    correction.trackId,
                    correction.revision,
                    correction.bpmOverride,
                    correction.phaseAnchorUs,
                    correction.beatsPerBar,
                    correction.regionStartUs,
                    correction.regionEndUs,
                    correction.confidenceOverride,
                    correction.createdAtUtc
                )
            )
        }
    }

    fun getBeatGridCorrection(trackId: String): BeatGridCorrectionEntity? = driver.read { s ->
        s.query(
            """
            SELECT trackId, revision, bpmOverride, phaseAnchorUs, beatsPerBar,
                   regionStartUs, regionEndUs, confidenceOverride, createdAtUtc
            FROM beat_grid_corrections
            WHERE trackId = ?
            """.trimIndent(),
            arrayOf(trackId)
        ) { c ->
            if (!c.next()) null else BeatGridCorrectionEntity(
                trackId = c.getString(0)!!,
                revision = c.getLong(1),
                bpmOverride = c.getDoubleOrNull(2),
                phaseAnchorUs = c.getLongOrNull(3),
                beatsPerBar = c.getIntOrNull(4),
                regionStartUs = c.getLongOrNull(5),
                regionEndUs = c.getLongOrNull(6),
                confidenceOverride = c.getDoubleOrNull(7),
                createdAtUtc = c.getLong(8)
            )
        }
    }

    fun saveExportJob(job: ExportJobEntity) {
        driver.writeTransaction { s ->
            s.execSql(
                """
                INSERT INTO export_jobs (
                    jobId, projectId, projectRevision, format, destinationUri,
                    status, progress, plannedDurationFrames, failureCode, createdAt, completedAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(jobId) DO UPDATE SET
                    status = excluded.status,
                    progress = excluded.progress,
                    failureCode = excluded.failureCode,
                    completedAt = excluded.completedAt
                """.trimIndent(),
                arrayOf(
                    job.jobId,
                    job.projectId,
                    job.projectRevision,
                    job.format,
                    job.destinationUri,
                    job.status,
                    job.progress,
                    job.plannedDurationFrames,
                    job.failureCode,
                    job.createdAt,
                    job.completedAt
                )
            )
        }
    }

    fun getExportJob(jobId: String): ExportJobEntity? = driver.read { s ->
        s.query(
            """
            SELECT jobId, projectId, projectRevision, format, destinationUri,
                   status, progress, plannedDurationFrames, failureCode, createdAt, completedAt
            FROM export_jobs
            WHERE jobId = ?
            """.trimIndent(),
            arrayOf(jobId)
        ) { c ->
            if (!c.next()) null else ExportJobEntity(
                jobId = c.getString(0)!!,
                projectId = c.getString(1)!!,
                projectRevision = c.getLong(2),
                format = c.getString(3)!!,
                destinationUri = c.getString(4)!!,
                status = c.getString(5)!!,
                progress = c.getDouble(6),
                plannedDurationFrames = c.getLong(7),
                failureCode = c.getString(8),
                createdAt = c.getLong(9),
                completedAt = c.getLongOrNull(10)
            )
        }
    }

    fun recordBackupRecord(record: ProjectBackupRecordEntity) {
        driver.writeTransaction { s ->
            s.execSql(
                """
                INSERT INTO project_backup_records (
                    backupId, projectId, backupRevision, fileUri, checksum, timestamp, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(backupId) DO UPDATE SET
                    status = excluded.status,
                    checksum = excluded.checksum,
                    timestamp = excluded.timestamp
                """.trimIndent(),
                arrayOf(
                    record.backupId,
                    record.projectId,
                    record.backupRevision,
                    record.fileUri,
                    record.checksum,
                    record.timestamp,
                    record.status
                )
            )
        }
    }

    // -------------------------------------------------------------------------
    // Read-Only Recovery Export (D08, ACC-39)
    // -------------------------------------------------------------------------

    @Serializable
    data class ReadOnlyRecoveryDump(
        val schemaVersion: Int,
        val openMode: DatabaseOpenMode,
        val readOnlyReason: String?,
        val projectsCount: Int,
        val projectNames: List<String>,
        val rawProjectsJson: List<Map<String, String>>
    )

    /**
     * Safely reads project rows even when the database is in [DatabaseOpenMode.READ_ONLY_UNSUPPORTED_SCHEMA]
     * without deleting or mutating any user data (D08, ACC-39).
     */
    fun exportReadOnlyRecoveryJson(): String = driver.read { s ->
        val hasProjectsTable = s.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='projects'"
        ) { c -> c.next() }

        val rawProjects = mutableListOf<Map<String, String>>()
        val names = mutableListOf<String>()
        if (hasProjectsTable) {
            runCatching {
                s.query("SELECT projectId, name, editRevision FROM projects") { c ->
                    while (c.next()) {
                        val id = c.getString(0).orEmpty()
                        val name = c.getString(1).orEmpty()
                        val rev = c.getLong(2).toString()
                        names.add(name)
                        rawProjects.add(
                            mapOf(
                                "projectId" to id,
                                "name" to name,
                                "editRevision" to rev
                            )
                        )
                    }
                }
            }
        }
        val dump = ReadOnlyRecoveryDump(
            schemaVersion = driver.schemaVersion,
            openMode = driver.openMode,
            readOnlyReason = driver.readOnlyReason,
            projectsCount = rawProjects.size,
            projectNames = names,
            rawProjectsJson = rawProjects
        )
        json.encodeToString(dump)
    }

    override fun close() {
        driver.close()
    }

    // -------------------------------------------------------------------------
    // Internal Helpers, Validation & Adjacency Reconciliation
    // -------------------------------------------------------------------------

    private fun validateTrackBounds(track: TrackEntity) {
        if (track.durationUs <= 0L || track.durationUs > MAX_DURATION_US) {
            throw InvalidPlanPersistenceException(
                errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                message = "Track durationUs out of bounds (1..$MAX_DURATION_US): ${track.durationUs}"
            )
        }
        if (track.size < 0L) {
            throw InvalidPlanPersistenceException(
                errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                message = "Track size cannot be negative: ${track.size}"
            )
        }
    }

    private fun validateOverrideBounds(ov: OverrideSnapshotEntity) {
        val violations = mutableListOf<String>()
        ov.exitAnchorUs?.let {
            if (it < 0L || it > MAX_DURATION_US) violations.add("exitAnchorUs out of bounds: $it")
        }
        ov.entryAnchorUs?.let {
            if (it < 0L || it > MAX_DURATION_US) violations.add("entryAnchorUs out of bounds: $it")
        }
        ov.spanUs?.let {
            if (it <= 0L || it > MAX_DURATION_US) violations.add("spanUs out of bounds: $it")
        }
        ov.barCount?.let {
            if (it <= 0 || it > 1024) violations.add("barCount out of bounds: $it")
        }
        ov.plannedRatePpm?.let {
            if (it !in MIN_RATE_PPM..MAX_RATE_PPM) violations.add("plannedRatePpm out of bounds: $it")
        }
        if (violations.isNotEmpty()) {
            diagnostics?.record(
                category = "SetFlowDB",
                errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                message = "Override bounds rejected: ${violations.joinToString("; ")}"
            )
            throw InvalidPlanPersistenceException(
                errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                message = "Override bounds rejected: ${violations.joinToString("; ")}",
                violations = violations
            )
        }
    }

    fun parseAndValidateSegueJson(
        canonicalSegueJson: String,
        outgoingFacts: SegueSideFacts? = null,
        incomingFacts: SegueSideFacts? = null
    ): SeguePlan {
        val plan = try {
            SegueCodec.decode(canonicalSegueJson)
        } catch (e: SegueFormatException) {
            diagnostics?.record(
                category = "SetFlowDB",
                errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                message = "SegueFormatException: ${e.message}"
            )
            throw InvalidPlanPersistenceException(
                errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                message = "Invalid SeguePlan JSON: ${e.message}",
                violations = listOf(e.message ?: "SegueFormatException")
            )
        }

        val violations = mutableListOf<String>()
        val structural = if (outgoingFacts != null && incomingFacts != null) {
            SegueValidator.against(plan, outgoingFacts, incomingFacts)
        } else {
            SegueValidator.structural(plan)
        }
        structural.forEach { v: SegueViolation -> violations.add(v.name) }

        if (plan.outgoing.anchorUs > MAX_DURATION_US || plan.incoming.anchorUs > MAX_DURATION_US) {
            violations.add("ANCHOR_EXCEEDS_24H")
        }
        if (plan.span.lengthUs > MAX_DURATION_US) {
            violations.add("SPAN_EXCEEDS_24H")
        }
        if (plan.outgoing.ratePpm !in MIN_RATE_PPM.toLong()..MAX_RATE_PPM.toLong() ||
            plan.incoming.ratePpm !in MIN_RATE_PPM.toLong()..MAX_RATE_PPM.toLong()
        ) {
            violations.add("RATE_PPM_OUT_OF_BOUNDS")
        }
        for (lane in plan.lanes) {
            if (lane.knots.size > MAX_KNOTS_PER_LANE) {
                violations.add("LANE_KNOTS_EXCEED_MAX_${MAX_KNOTS_PER_LANE}")
            }
        }

        if (violations.isNotEmpty()) {
            diagnostics?.record(
                category = "SetFlowDB",
                errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                message = "SeguePlan validation failed: ${violations.joinToString(",")}"
            )
            throw InvalidPlanPersistenceException(
                errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                message = "SeguePlan validation failed: ${violations.joinToString(", ")}",
                violations = violations
            )
        }
        return plan
    }

    private fun reconcileAdjacentEdges(
        s: SetFlowSqlSession,
        projectId: String,
        orderedOccurrences: List<OccurrenceEntity>,
        existingEdges: List<EdgeCompleteSnapshot>,
        restoreMatchingOrphanedEdges: Boolean
    ) {
        val targetPairs = mutableListOf<Pair<String, String>>()
        for (i in 0 until (orderedOccurrences.size - 1)) {
            targetPairs.add(orderedOccurrences[i].occurrenceId to orderedOccurrences[i + 1].occurrenceId)
        }
        val targetPairSet = targetPairs.toSet()

        val survivingByPair = LinkedHashMap<Pair<String, String>, EdgeCompleteSnapshot>()
        val now = clockUtcMs()

        // Archive and delete any active edge whose (outgoingOccurrenceId, incomingOccurrenceId) is no longer adjacent
        for (edgeSnap in existingEdges) {
            val pair = edgeSnap.edge.outgoingOccurrenceId to edgeSnap.edge.incomingOccurrenceId
            if (pair in targetPairSet) {
                survivingByPair[pair] = edgeSnap
            } else {
                archiveOrphanedEdge(s, edgeSnap, now)
                deleteEdgeAndSnapshots(s, edgeSnap.edge.edgeId)
            }
        }

        // Ensure every target adjacent pair has an active edge
        for (pair in targetPairs) {
            if (pair in survivingByPair) continue
            val (outId, inId) = pair
            val restored = if (restoreMatchingOrphanedEdges) {
                queryOrphanedEdge(s, projectId, outId, inId)
            } else {
                null
            }
            if (restored != null) {
                insertEdgeCompleteSnapshot(s, restored)
            } else {
                val freshEdge = EdgeEntity(
                    edgeId = "edge_${UUID.randomUUID()}",
                    projectId = projectId,
                    outgoingOccurrenceId = outId,
                    incomingOccurrenceId = inId,
                    relationVersion = 1,
                    planRevision = 1L,
                    origin = EdgeOrigin.AUTO,
                    lastResolvedFingerprint = "",
                    lastValidationStatus = EdgeValidationStatus.UNRESOLVED
                )
                insertEdge(s, freshEdge)
            }
        }
    }

    private fun validateProjectStructuralInvariants(s: SetFlowSqlSession, projectId: String) {
        val occs = queryOccurrencesForProject(s, projectId)
        for (i in occs.indices) {
            if (occs[i].positionIndex != i) {
                throw IllegalStateException(
                    "Invariant violation: non-contiguous positionIndex ${occs[i].positionIndex} at index $i in project $projectId"
                )
            }
        }
        val edges = queryEdgesForProject(s, projectId)
        val expectedEdgeCount = (occs.size - 1).coerceAtLeast(0)
        if (edges.size != expectedEdgeCount) {
            throw IllegalStateException(
                "Invariant violation: expected $expectedEdgeCount edges for ${occs.size} occurrences, found ${edges.size}"
            )
        }
        val expectedPairs = (0 until expectedEdgeCount).map {
            occs[it].occurrenceId to occs[it + 1].occurrenceId
        }.toSet()
        val actualPairs = edges.map {
            it.edge.outgoingOccurrenceId to it.edge.incomingOccurrenceId
        }.toSet()
        if (expectedPairs != actualPairs) {
            throw IllegalStateException(
                "Invariant violation: edge pairs $actualPairs do not match adjacent occurrence pairs $expectedPairs"
            )
        }
    }

    private fun pushStructuralUndoSnapshot(
        s: SetFlowSqlSession,
        project: ProjectEntity,
        occurrences: List<OccurrenceEntity>,
        edges: List<EdgeCompleteSnapshot>
    ) {
        val snap = StructuralUndoSnapshot(
            projectId = project.projectId,
            priorEditRevision = project.editRevision,
            occurrences = occurrences,
            edges = edges
        )
        s.execSql(
            """
            INSERT INTO project_undo_log (projectId, priorRevision, snapshotJson, createdAtUtc)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                project.projectId,
                project.editRevision,
                json.encodeToString(snap),
                clockUtcMs()
            )
        )
    }

    private fun archiveOrphanedEdge(
        s: SetFlowSqlSession,
        edgeSnap: EdgeCompleteSnapshot,
        archivedAtUtc: Long
    ) {
        s.execSql(
            """
            INSERT OR REPLACE INTO orphaned_edge_history (
                projectId, outgoingOccurrenceId, incomingOccurrenceId,
                edgeJson, autoPlanJson, overridePlanJson, resolvedPlanJson, archivedAtUtc
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                edgeSnap.edge.projectId,
                edgeSnap.edge.outgoingOccurrenceId,
                edgeSnap.edge.incomingOccurrenceId,
                json.encodeToString(edgeSnap.edge),
                edgeSnap.autoPlan?.let { json.encodeToString(it) },
                edgeSnap.overridePlan?.let { json.encodeToString(it) },
                edgeSnap.resolvedPlan?.let { json.encodeToString(it) },
                archivedAtUtc
            )
        )
    }

    private fun queryOrphanedEdge(
        s: SetFlowSqlSession,
        projectId: String,
        outgoingOccurrenceId: String,
        incomingOccurrenceId: String
    ): EdgeCompleteSnapshot? = s.query(
        """
        SELECT edgeJson, autoPlanJson, overridePlanJson, resolvedPlanJson
        FROM orphaned_edge_history
        WHERE projectId = ? AND outgoingOccurrenceId = ? AND incomingOccurrenceId = ?
        """.trimIndent(),
        arrayOf(projectId, outgoingOccurrenceId, incomingOccurrenceId)
    ) { c ->
        if (!c.next()) null else {
            val edge = json.decodeFromString<EdgeEntity>(c.getString(0)!!)
            val auto = c.getString(1)?.let { json.decodeFromString<AutoPlanSnapshotEntity>(it) }
            val ov = c.getString(2)?.let { json.decodeFromString<OverrideSnapshotEntity>(it) }
            val res = c.getString(3)?.let { json.decodeFromString<ResolvedPlanSnapshotEntity>(it) }
            EdgeCompleteSnapshot(edge, auto, ov, res)
        }
    }

    private fun requireActiveProjectAndRevision(
        s: SetFlowSqlSession,
        projectId: String,
        expectedRevision: Long
    ): ProjectEntity {
        val project = queryProjectById(s, projectId)
            ?: throw IllegalArgumentException("Project not found: $projectId")
        if (project.deletedAt != null) {
            throw IllegalStateException("Project $projectId has been deleted")
        }
        if (project.editRevision != expectedRevision) {
            diagnostics?.record(
                category = "SetFlowDB",
                errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                message = "Project($projectId) revision conflict: expected $expectedRevision, found ${project.editRevision}"
            )
            throw OptimisticConcurrencyException(
                entityType = "Project",
                entityId = projectId,
                expectedRevision = expectedRevision,
                actualRevision = project.editRevision
            )
        }
        return project
    }

    internal fun insertProject(s: SetFlowSqlSession, p: ProjectEntity) {
        s.execSql(
            """
            INSERT INTO projects (
                projectId, schemaVersion, name, createdAtUtc, updatedAtUtc, editRevision, settingsSnapshot, deletedAt
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                p.projectId,
                p.schemaVersion,
                p.name,
                p.createdAtUtc,
                p.updatedAtUtc,
                p.editRevision,
                p.settingsSnapshot,
                p.deletedAt
            )
        )
    }

    internal fun insertOccurrence(s: SetFlowSqlSession, occ: OccurrenceEntity) {
        s.execSql(
            """
            INSERT INTO occurrences (
                occurrenceId, projectId, trackId, positionIndex, playbackGainDb, sourceRangeStartUs, sourceRangeEndUs
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                occ.occurrenceId,
                occ.projectId,
                occ.trackId,
                occ.positionIndex,
                occ.playbackGainDb,
                occ.sourceRangeStartUs,
                occ.sourceRangeEndUs
            )
        )
    }

    internal fun insertEdge(s: SetFlowSqlSession, e: EdgeEntity) {
        s.execSql(
            """
            INSERT INTO edges (
                edgeId, projectId, outgoingOccurrenceId, incomingOccurrenceId,
                relationVersion, planRevision, origin, lastResolvedFingerprint, lastValidationStatus
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                e.edgeId,
                e.projectId,
                e.outgoingOccurrenceId,
                e.incomingOccurrenceId,
                e.relationVersion,
                e.planRevision,
                e.origin.name,
                e.lastResolvedFingerprint,
                e.lastValidationStatus.name
            )
        )
    }

    internal fun insertEdgeCompleteSnapshot(s: SetFlowSqlSession, snap: EdgeCompleteSnapshot) {
        insertEdge(s, snap.edge)
        snap.autoPlan?.let { upsertAutoPlanSnapshot(s, it) }
        snap.overridePlan?.let { upsertOverrideSnapshot(s, it) }
        snap.resolvedPlan?.let { upsertResolvedPlanSnapshot(s, it) }
    }

    private fun deleteEdgeAndSnapshots(s: SetFlowSqlSession, edgeId: String) {
        s.execSql("DELETE FROM auto_plan_snapshots WHERE edgeId = ?", arrayOf(edgeId))
        s.execSql("DELETE FROM override_snapshots WHERE edgeId = ?", arrayOf(edgeId))
        s.execSql("DELETE FROM resolved_plan_snapshots WHERE edgeId = ?", arrayOf(edgeId))
        s.execSql("DELETE FROM edges WHERE edgeId = ?", arrayOf(edgeId))
    }

    internal fun upsertAutoPlanSnapshot(s: SetFlowSqlSession, a: AutoPlanSnapshotEntity) {
        s.execSql(
            """
            INSERT INTO auto_plan_snapshots (
                edgeId, engineVersion, analyzerVersion, parametersJson,
                analysisKeyA, analysisKeyB, planCanonicalJson, generatedAtUtc
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(edgeId) DO UPDATE SET
                engineVersion = excluded.engineVersion,
                analyzerVersion = excluded.analyzerVersion,
                parametersJson = excluded.parametersJson,
                analysisKeyA = excluded.analysisKeyA,
                analysisKeyB = excluded.analysisKeyB,
                planCanonicalJson = excluded.planCanonicalJson,
                generatedAtUtc = excluded.generatedAtUtc
            """.trimIndent(),
            arrayOf(
                a.edgeId,
                a.engineVersion,
                a.analyzerVersion,
                a.parametersJson,
                a.analysisKeyA,
                a.analysisKeyB,
                a.planCanonicalJson,
                a.generatedAtUtc
            )
        )
    }

    internal fun upsertOverrideSnapshot(s: SetFlowSqlSession, ov: OverrideSnapshotEntity) {
        s.execSql(
            """
            INSERT INTO override_snapshots (
                edgeId, overrideSchema, revision, exitAnchorUs, entryAnchorUs,
                spanUs, barCount, pulseMode, plannedRatePpm, laneOverridesJson,
                presetId, correctedGridVersionA, correctedGridVersionB,
                userEditedFieldsBitset, editTimestampUtc
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(edgeId) DO UPDATE SET
                overrideSchema = excluded.overrideSchema,
                revision = excluded.revision,
                exitAnchorUs = excluded.exitAnchorUs,
                entryAnchorUs = excluded.entryAnchorUs,
                spanUs = excluded.spanUs,
                barCount = excluded.barCount,
                pulseMode = excluded.pulseMode,
                plannedRatePpm = excluded.plannedRatePpm,
                laneOverridesJson = excluded.laneOverridesJson,
                presetId = excluded.presetId,
                correctedGridVersionA = excluded.correctedGridVersionA,
                correctedGridVersionB = excluded.correctedGridVersionB,
                userEditedFieldsBitset = excluded.userEditedFieldsBitset,
                editTimestampUtc = excluded.editTimestampUtc
            """.trimIndent(),
            arrayOf(
                ov.edgeId,
                ov.overrideSchema,
                ov.revision,
                ov.exitAnchorUs,
                ov.entryAnchorUs,
                ov.spanUs,
                ov.barCount,
                ov.pulseMode,
                ov.plannedRatePpm,
                ov.laneOverridesJson,
                ov.presetId,
                ov.correctedGridVersionA,
                ov.correctedGridVersionB,
                ov.userEditedFieldsBitset,
                ov.editTimestampUtc
            )
        )
    }

    internal fun upsertResolvedPlanSnapshot(s: SetFlowSqlSession, r: ResolvedPlanSnapshotEntity) {
        s.execSql(
            """
            INSERT INTO resolved_plan_snapshots (
                edgeId, resolutionRevision, canonicalSegueJson, planHashSha256,
                sourceKeys, warnings, validatedAtUtc
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(edgeId) DO UPDATE SET
                resolutionRevision = excluded.resolutionRevision,
                canonicalSegueJson = excluded.canonicalSegueJson,
                planHashSha256 = excluded.planHashSha256,
                sourceKeys = excluded.sourceKeys,
                warnings = excluded.warnings,
                validatedAtUtc = excluded.validatedAtUtc
            """.trimIndent(),
            arrayOf(
                r.edgeId,
                r.resolutionRevision,
                r.canonicalSegueJson,
                r.planHashSha256,
                r.sourceKeys,
                r.warnings,
                r.validatedAtUtc
            )
        )
    }

    private fun queryProjectById(s: SetFlowSqlSession, projectId: String): ProjectEntity? =
        s.query(
            """
            SELECT projectId, schemaVersion, name, createdAtUtc, updatedAtUtc, editRevision, settingsSnapshot, deletedAt
            FROM projects
            WHERE projectId = ?
            """.trimIndent(),
            arrayOf(projectId)
        ) { c ->
            if (c.next()) readProjectRow(c) else null
        }

    private fun readProjectRow(c: SetFlowSqlCursor) = ProjectEntity(
        projectId = c.getString(0)!!,
        schemaVersion = c.getInt(1),
        name = c.getString(2)!!,
        createdAtUtc = c.getLong(3),
        updatedAtUtc = c.getLong(4),
        editRevision = c.getLong(5),
        settingsSnapshot = c.getString(6)!!,
        deletedAt = c.getLongOrNull(7)
    )

    private fun queryTrackById(s: SetFlowSqlSession, trackId: String): TrackEntity? =
        s.query(
            """
            SELECT trackId, displayName, title, artist, album, durationUs, contentUri,
                   providerAuthority, mime, codecDescription, sampleRateHz, channelCount,
                   bitsPerSample, size, modifiedTime, mediaStoreId, grantStatus,
                   contentFingerprint, audioFingerprint, fingerprintVersion, cachedArtworkKey,
                   lastVerified, availabilityState
            FROM tracks
            WHERE trackId = ?
            """.trimIndent(),
            arrayOf(trackId)
        ) { c ->
            if (c.next()) readTrackRow(c) else null
        }

    private fun readTrackRow(c: SetFlowSqlCursor) = TrackEntity(
        trackId = c.getString(0)!!,
        displayName = c.getString(1)!!,
        title = c.getString(2)!!,
        artist = c.getString(3)!!,
        album = c.getString(4)!!,
        durationUs = c.getLong(5),
        contentUri = c.getString(6)!!,
        providerAuthority = c.getString(7),
        mime = c.getString(8)!!,
        codecDescription = c.getString(9)!!,
        sampleRateHz = c.getInt(10),
        channelCount = c.getInt(11),
        bitsPerSample = c.getInt(12),
        size = c.getLong(13),
        modifiedTime = c.getLong(14),
        mediaStoreId = c.getLongOrNull(15),
        grantStatus = c.getString(16)!!,
        contentFingerprint = c.getString(17)!!,
        audioFingerprint = c.getString(18),
        fingerprintVersion = c.getInt(19),
        cachedArtworkKey = c.getString(20),
        lastVerified = c.getLong(21),
        availabilityState = c.getString(22)!!
    )

    private fun queryOccurrencesForProject(s: SetFlowSqlSession, projectId: String): List<OccurrenceEntity> =
        s.query(
            """
            SELECT occurrenceId, projectId, trackId, positionIndex, playbackGainDb, sourceRangeStartUs, sourceRangeEndUs
            FROM occurrences
            WHERE projectId = ?
            ORDER BY positionIndex ASC
            """.trimIndent(),
            arrayOf(projectId)
        ) { c ->
            val list = mutableListOf<OccurrenceEntity>()
            while (c.next()) {
                list.add(
                    OccurrenceEntity(
                        occurrenceId = c.getString(0)!!,
                        projectId = c.getString(1)!!,
                        trackId = c.getString(2)!!,
                        positionIndex = c.getInt(3),
                        playbackGainDb = c.getDoubleOrNull(4),
                        sourceRangeStartUs = c.getLongOrNull(5),
                        sourceRangeEndUs = c.getLongOrNull(6)
                    )
                )
            }
            list
        }

    private fun queryEdgeById(s: SetFlowSqlSession, edgeId: String): EdgeEntity? =
        s.query(
            """
            SELECT edgeId, projectId, outgoingOccurrenceId, incomingOccurrenceId,
                   relationVersion, planRevision, origin, lastResolvedFingerprint, lastValidationStatus
            FROM edges
            WHERE edgeId = ?
            """.trimIndent(),
            arrayOf(edgeId)
        ) { c ->
            if (c.next()) readEdgeRow(c) else null
        }

    private fun readEdgeRow(c: SetFlowSqlCursor) = EdgeEntity(
        edgeId = c.getString(0)!!,
        projectId = c.getString(1)!!,
        outgoingOccurrenceId = c.getString(2)!!,
        incomingOccurrenceId = c.getString(3)!!,
        relationVersion = c.getInt(4),
        planRevision = c.getLong(5),
        origin = EdgeOrigin.valueOf(c.getString(6)!!),
        lastResolvedFingerprint = c.getString(7)!!,
        lastValidationStatus = EdgeValidationStatus.valueOf(c.getString(8)!!)
    )

    private fun queryAutoPlanSnapshot(s: SetFlowSqlSession, edgeId: String): AutoPlanSnapshotEntity? =
        s.query(
            """
            SELECT edgeId, engineVersion, analyzerVersion, parametersJson,
                   analysisKeyA, analysisKeyB, planCanonicalJson, generatedAtUtc
            FROM auto_plan_snapshots
            WHERE edgeId = ?
            """.trimIndent(),
            arrayOf(edgeId)
        ) { c ->
            if (!c.next()) null else AutoPlanSnapshotEntity(
                edgeId = c.getString(0)!!,
                engineVersion = c.getString(1)!!,
                analyzerVersion = c.getString(2)!!,
                parametersJson = c.getString(3)!!,
                analysisKeyA = c.getString(4)!!,
                analysisKeyB = c.getString(5)!!,
                planCanonicalJson = c.getString(6)!!,
                generatedAtUtc = c.getLong(7)
            )
        }

    private fun queryOverrideSnapshot(s: SetFlowSqlSession, edgeId: String): OverrideSnapshotEntity? =
        s.query(
            """
            SELECT edgeId, overrideSchema, revision, exitAnchorUs, entryAnchorUs,
                   spanUs, barCount, pulseMode, plannedRatePpm, laneOverridesJson,
                   presetId, correctedGridVersionA, correctedGridVersionB,
                   userEditedFieldsBitset, editTimestampUtc
            FROM override_snapshots
            WHERE edgeId = ?
            """.trimIndent(),
            arrayOf(edgeId)
        ) { c ->
            if (!c.next()) null else OverrideSnapshotEntity(
                edgeId = c.getString(0)!!,
                overrideSchema = c.getInt(1),
                revision = c.getLong(2),
                exitAnchorUs = c.getLongOrNull(3),
                entryAnchorUs = c.getLongOrNull(4),
                spanUs = c.getLongOrNull(5),
                barCount = c.getIntOrNull(6),
                pulseMode = c.getString(7),
                plannedRatePpm = c.getIntOrNull(8),
                laneOverridesJson = c.getString(9),
                presetId = c.getString(10),
                correctedGridVersionA = c.getIntOrNull(11),
                correctedGridVersionB = c.getIntOrNull(12),
                userEditedFieldsBitset = c.getInt(13),
                editTimestampUtc = c.getLong(14)
            )
        }

    private fun queryResolvedPlanSnapshot(s: SetFlowSqlSession, edgeId: String): ResolvedPlanSnapshotEntity? =
        s.query(
            """
            SELECT edgeId, resolutionRevision, canonicalSegueJson, planHashSha256,
                   sourceKeys, warnings, validatedAtUtc
            FROM resolved_plan_snapshots
            WHERE edgeId = ?
            """.trimIndent(),
            arrayOf(edgeId)
        ) { c ->
            if (!c.next()) null else ResolvedPlanSnapshotEntity(
                edgeId = c.getString(0)!!,
                resolutionRevision = c.getLong(1),
                canonicalSegueJson = c.getString(2)!!,
                planHashSha256 = c.getString(3)!!,
                sourceKeys = c.getString(4)!!,
                warnings = c.getString(5)!!,
                validatedAtUtc = c.getLong(6)
            )
        }

    private fun queryEdgeCompleteSnapshot(s: SetFlowSqlSession, edgeId: String): EdgeCompleteSnapshot? {
        val edge = queryEdgeById(s, edgeId) ?: return null
        return EdgeCompleteSnapshot(
            edge = edge,
            autoPlan = queryAutoPlanSnapshot(s, edgeId),
            overridePlan = queryOverrideSnapshot(s, edgeId),
            resolvedPlan = queryResolvedPlanSnapshot(s, edgeId)
        )
    }

    private fun queryEdgesForProject(s: SetFlowSqlSession, projectId: String): List<EdgeCompleteSnapshot> {
        val occOrder = queryOccurrencesForProject(s, projectId)
            .withIndex()
            .associate { it.value.occurrenceId to it.index }
        val rawEdges = s.query(
            """
            SELECT edgeId, projectId, outgoingOccurrenceId, incomingOccurrenceId,
                   relationVersion, planRevision, origin, lastResolvedFingerprint, lastValidationStatus
            FROM edges
            WHERE projectId = ?
            """.trimIndent(),
            arrayOf(projectId)
        ) { c ->
            val list = mutableListOf<EdgeEntity>()
            while (c.next()) {
                list.add(readEdgeRow(c))
            }
            list
        }
        return rawEdges
            .sortedBy { occOrder[it.outgoingOccurrenceId] ?: Int.MAX_VALUE }
            .map { e ->
                EdgeCompleteSnapshot(
                    edge = e,
                    autoPlan = queryAutoPlanSnapshot(s, e.edgeId),
                    overridePlan = queryOverrideSnapshot(s, e.edgeId),
                    resolvedPlan = queryResolvedPlanSnapshot(s, e.edgeId)
                )
            }
    }

    private fun queryProjectDetail(s: SetFlowSqlSession, projectId: String): ProjectDetailSnapshot? {
        val project = queryProjectById(s, projectId) ?: return null
        val occs = queryOccurrencesForProject(s, projectId)
        val occsWithTrack = occs.map { occ ->
            val track = queryTrackById(s, occ.trackId)
                ?: throw IllegalStateException("Track ${occ.trackId} missing for occurrence ${occ.occurrenceId}")
            OccurrenceWithTrack(occ, track)
        }
        val edges = queryEdgesForProject(s, projectId)
        val canUndo = s.query(
            "SELECT 1 FROM project_undo_log WHERE projectId = ? LIMIT 1",
            arrayOf(projectId)
        ) { c -> c.next() }
        return ProjectDetailSnapshot(
            project = project,
            occurrences = occsWithTrack,
            edges = edges,
            canUndo = canUndo
        )
    }
}
