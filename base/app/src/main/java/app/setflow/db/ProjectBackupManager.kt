/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.db

import app.setflow.domain.AudioErrorCode
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.PersistableGrantStatus
import app.setflow.domain.TrackAvailabilityState
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Versioned project JSON backup exporter and importer with SHA-256 checksums,
 * URI stripping, resource limits, duplicate-safe reimport, and missing-track relink prompts
 * (Stage 04 Task 5, D07, D08, D09, S16, S17, S30, ACC-27).
 */
class ProjectBackupManager(
    private val database: SetFlowProjectDatabase,
    private val diagnostics: DiagnosticsRingBuffer? = null,
    private val clockUtcMs: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val BACKUP_FORMAT_ID = "setflow-project"
        const val CURRENT_BACKUP_MAJOR_VERSION = 1

        /** Hard limits against malformed or adversarial backup payloads (Task 5, S16, S17). */
        const val MAX_BACKUP_BYTES: Int = 10 * 1024 * 1024 // 10 MiB
        const val MAX_JSON_NESTING_DEPTH: Int = 24
        const val MAX_TRACKS: Int = 1_000
        const val MAX_OCCURRENCES: Int = 1_000
        const val MAX_EDGES: Int = 999

        private val KNOWN_TOP_LEVEL_KEYS = setOf(
            "format",
            "version",
            "project",
            "tracks",
            "occurrences",
            "edges",
            "exportedAtUtc",
            "checksum"
        )

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }

    @Serializable
    data class BackupTrackEntry(
        val trackId: String,
        val displayName: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationUs: Long,
        val mime: String,
        val codecDescription: String,
        val sampleRateHz: Int,
        val channelCount: Int,
        val bitsPerSample: Int,
        val size: Long,
        val modifiedTime: Long,
        val contentFingerprint: String,
        val audioFingerprint: String? = null,
        val fingerprintVersion: Int = 1,
        val beatGridCorrection: BeatGridCorrectionEntity? = null
    )

    @Serializable
    data class ProjectBackupPayload(
        val format: String = BACKUP_FORMAT_ID,
        val version: Int = CURRENT_BACKUP_MAJOR_VERSION,
        val project: ProjectEntity,
        val tracks: List<BackupTrackEntry>,
        val occurrences: List<OccurrenceEntity>,
        val edges: List<EdgeCompleteSnapshot>,
        val exportedAtUtc: Long
    )

    data class ExportBackupResult(
        val projectId: String,
        val backupRevision: Long,
        val jsonText: String,
        val checksumSha256: String,
        val record: ProjectBackupRecordEntity
    )

    data class ImportBackupResult(
        val importedProjectDetail: ProjectDetailSnapshot,
        val wasRemappedForDuplicateId: Boolean,
        val originalProjectId: String,
        val tracksRequiringRelink: List<BackupTrackEntry>,
        val preservedUnknownFields: Map<String, JsonElement>
    )

    /**
     * Exports a project and all its occurrences, edges, overrides, resolved plans, and track
     * fingerprints to a shareable JSON document with private URIs stripped (Task 5, S30).
     */
    fun exportProjectBackup(
        projectId: String,
        destinationLabelUri: String = "backup://local/$projectId.json",
        extraOptionalFields: Map<String, JsonElement> = emptyMap()
    ): ExportBackupResult {
        val detail = database.getProjectDetail(projectId)
            ?: throw IllegalArgumentException("Project not found: $projectId")

        val uniqueTracks = detail.occurrences
            .map { it.track }
            .distinctBy { it.trackId }
            .map { t ->
                BackupTrackEntry(
                    trackId = t.trackId,
                    displayName = sanitizePublicLabel(t.displayName),
                    title = sanitizePublicLabel(t.title),
                    artist = sanitizePublicLabel(t.artist),
                    album = sanitizePublicLabel(t.album),
                    durationUs = t.durationUs,
                    mime = t.mime,
                    codecDescription = t.codecDescription,
                    sampleRateHz = t.sampleRateHz,
                    channelCount = t.channelCount,
                    bitsPerSample = t.bitsPerSample,
                    size = t.size,
                    modifiedTime = t.modifiedTime,
                    contentFingerprint = t.contentFingerprint,
                    audioFingerprint = t.audioFingerprint,
                    fingerprintVersion = t.fingerprintVersion,
                    beatGridCorrection = database.getBeatGridCorrection(t.trackId)
                )
            }

        val now = clockUtcMs()
        val payload = ProjectBackupPayload(
            format = BACKUP_FORMAT_ID,
            version = CURRENT_BACKUP_MAJOR_VERSION,
            project = detail.project,
            tracks = uniqueTracks,
            occurrences = detail.occurrences.map { it.occurrence },
            edges = detail.edges,
            exportedAtUtc = now
        )

        val baseObj = json.encodeToJsonElement(payload).jsonObject
        val mergedWithoutChecksum = LinkedHashMap<String, JsonElement>()
        for ((k, v) in baseObj) {
            if (k != "checksum") mergedWithoutChecksum[k] = v
        }
        for ((k, v) in extraOptionalFields) {
            if (k !in KNOWN_TOP_LEVEL_KEYS) {
                mergedWithoutChecksum[k] = v
            }
        }

        val checksum = computeObjectChecksum(JsonObject(mergedWithoutChecksum))
        val finalMap = LinkedHashMap<String, JsonElement>(mergedWithoutChecksum)
        finalMap["checksum"] = JsonPrimitive(checksum)
        val finalJsonText = json.encodeToString(JsonObject(finalMap))

        val record = ProjectBackupRecordEntity(
            backupId = "bkp_${UUID.randomUUID()}",
            projectId = projectId,
            backupRevision = detail.project.editRevision,
            fileUri = sanitizePublicLabel(destinationLabelUri),
            checksum = checksum,
            timestamp = now,
            status = "COMPLETED"
        )
        if (database.openMode == DatabaseOpenMode.READ_WRITE) {
            database.recordBackupRecord(record)
        }

        return ExportBackupResult(
            projectId = projectId,
            backupRevision = detail.project.editRevision,
            jsonText = finalJsonText,
            checksumSha256 = checksum,
            record = record
        )
    }

    /**
     * Streaming overload that enforces [MAX_BACKUP_BYTES] before reading into memory (S17).
     */
    fun importProjectBackupStream(inputStream: InputStream, declaredSizeBytes: Long = -1L): ImportBackupResult {
        if (declaredSizeBytes > MAX_BACKUP_BYTES) {
            rejectBackup("Backup size $declaredSizeBytes bytes exceeds maximum $MAX_BACKUP_BYTES bytes")
        }
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val r = inputStream.read(buffer)
            if (r < 0) break
            total += r
            if (total > MAX_BACKUP_BYTES) {
                rejectBackup("Backup stream exceeds maximum $MAX_BACKUP_BYTES bytes")
            }
            out.write(buffer, 0, r)
        }
        return importProjectBackup(out.toString(Charsets.UTF_8.name()))
    }

    /**
     * Validates and imports a versioned project backup JSON document (Task 5, D07, D08, D09, S16, S17).
     */
    fun importProjectBackup(rawJson: String): ImportBackupResult {
        val byteLen = rawJson.length // fast char bound check before UTF-8 byte array allocation
        if (byteLen > MAX_BACKUP_BYTES || rawJson.toByteArray(Charsets.UTF_8).size > MAX_BACKUP_BYTES) {
            rejectBackup("Backup payload exceeds maximum size of $MAX_BACKUP_BYTES bytes")
        }
        if (rawJson.isBlank()) {
            rejectBackup("Backup payload is empty")
        }

        verifyNestingDepth(rawJson)

        val rootElement = try {
            json.parseToJsonElement(rawJson)
        } catch (t: Throwable) {
            rejectBackup("Malformed backup JSON: ${t.message}")
        }
        val rootObj = rootElement as? JsonObject
            ?: rejectBackup("Backup root must be a JSON object")

        val format = (rootObj["format"] as? JsonPrimitive)?.content
            ?: rejectBackup("Missing backup 'format' field")
        if (format != BACKUP_FORMAT_ID) {
            rejectBackup("Unsupported backup format: $format")
        }

        val version = (rootObj["version"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: rejectBackup("Missing or non-integer backup 'version' field")
        if (version != CURRENT_BACKUP_MAJOR_VERSION) {
            rejectBackup("Unsupported backup major version: $version (supported: $CURRENT_BACKUP_MAJOR_VERSION)")
        }

        val expectedChecksum = (rootObj["checksum"] as? JsonPrimitive)?.content
            ?: rejectBackup("Missing backup 'checksum' field")

        val mapWithoutChecksum = LinkedHashMap<String, JsonElement>()
        val unknownFields = LinkedHashMap<String, JsonElement>()
        for ((k, v) in rootObj) {
            if (k != "checksum") {
                mapWithoutChecksum[k] = v
                if (k !in KNOWN_TOP_LEVEL_KEYS) {
                    unknownFields[k] = v
                }
            }
        }

        val actualChecksum = computeObjectChecksum(JsonObject(mapWithoutChecksum))
        if (!actualChecksum.equals(expectedChecksum, ignoreCase = true)) {
            rejectBackup("Backup checksum mismatch: expected $expectedChecksum, computed $actualChecksum")
        }

        val payload = try {
            json.decodeFromJsonElement<ProjectBackupPayload>(JsonObject(mapWithoutChecksum))
        } catch (t: Throwable) {
            rejectBackup("Invalid backup schema structure: ${t.message}")
        }

        if (payload.tracks.size > MAX_TRACKS) {
            rejectBackup("Backup track count ${payload.tracks.size} exceeds limit $MAX_TRACKS")
        }
        if (payload.occurrences.size > MAX_OCCURRENCES) {
            rejectBackup("Backup occurrence count ${payload.occurrences.size} exceeds limit $MAX_OCCURRENCES")
        }
        if (payload.edges.size > MAX_EDGES) {
            rejectBackup("Backup edge count ${payload.edges.size} exceeds limit $MAX_EDGES")
        }

        // Pre-validate occurrence positions and edge plans/overrides before touching the database
        val sortedOccs = payload.occurrences.sortedBy { it.positionIndex }
        for (i in sortedOccs.indices) {
            if (sortedOccs[i].positionIndex != i) {
                rejectBackup("Backup occurrences have non-contiguous positionIndex at $i")
            }
        }
        val expectedEdgeCount = (sortedOccs.size - 1).coerceAtLeast(0)
        if (payload.edges.size != expectedEdgeCount) {
            rejectBackup("Backup edge count ${payload.edges.size} does not match expected $expectedEdgeCount")
        }

        for (edgeSnap in payload.edges) {
            edgeSnap.autoPlan?.let { database.parseAndValidateSegueJson(it.planCanonicalJson) }
            edgeSnap.resolvedPlan?.let { database.parseAndValidateSegueJson(it.canonicalSegueJson) }
            edgeSnap.overridePlan?.let { ov ->
                if (ov.exitAnchorUs != null && (ov.exitAnchorUs < 0L || ov.exitAnchorUs > SetFlowProjectDatabase.MAX_DURATION_US)) {
                    rejectBackup("Backup override exitAnchorUs out of bounds: ${ov.exitAnchorUs}")
                }
                if (ov.entryAnchorUs != null && (ov.entryAnchorUs < 0L || ov.entryAnchorUs > SetFlowProjectDatabase.MAX_DURATION_US)) {
                    rejectBackup("Backup override entryAnchorUs out of bounds: ${ov.entryAnchorUs}")
                }
                if (ov.spanUs != null && (ov.spanUs <= 0L || ov.spanUs > SetFlowProjectDatabase.MAX_DURATION_US)) {
                    rejectBackup("Backup override spanUs out of bounds: ${ov.spanUs}")
                }
            }
        }

        // Perform duplicate-safe import inside a single transaction (D07, D09, ACC-27)
        val existingTracksById = database.listTracks().associateBy { it.trackId }
        val existingTracksByFingerprint = database.listTracks().associateBy { it.contentFingerprint }
        val tracksRequiringRelink = mutableListOf<BackupTrackEntry>()
        val trackIdRemap = LinkedHashMap<String, String>()

        val existingProject = database.getProject(payload.project.projectId)
        val isDuplicateProject = existingProject != null
        val targetProjectId = if (isDuplicateProject) {
            "${payload.project.projectId}_import_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        } else {
            payload.project.projectId
        }
        val targetProjectName = if (isDuplicateProject) {
            "${payload.project.name} (Imported)"
        } else {
            payload.project.name
        }

        val now = clockUtcMs()

        // Ensure all referenced tracks exist in the local DB (matching available local track or creating MISSING_OR_REVOKED placeholder)
        for (bt in payload.tracks) {
            val matchedLocal = existingTracksById[bt.trackId]
                ?: existingTracksByFingerprint[bt.contentFingerprint]?.takeIf {
                    it.size == bt.size && it.durationUs == bt.durationUs
                }

            if (matchedLocal != null && matchedLocal.availabilityState == TrackAvailabilityState.AVAILABLE.name) {
                trackIdRemap[bt.trackId] = matchedLocal.trackId
            } else {
                trackIdRemap[bt.trackId] = bt.trackId
                tracksRequiringRelink.add(bt)
                database.upsertTrack(
                    TrackEntity(
                        trackId = bt.trackId,
                        displayName = bt.displayName,
                        title = bt.title,
                        artist = bt.artist,
                        album = bt.album,
                        durationUs = bt.durationUs,
                        contentUri = "relink://missing/${bt.trackId}",
                        providerAuthority = null,
                        mime = bt.mime,
                        codecDescription = bt.codecDescription,
                        sampleRateHz = bt.sampleRateHz,
                        channelCount = bt.channelCount,
                        bitsPerSample = bt.bitsPerSample,
                        size = bt.size,
                        modifiedTime = bt.modifiedTime,
                        mediaStoreId = null,
                        grantStatus = PersistableGrantStatus.REVOKED_OR_DENIED.name,
                        contentFingerprint = bt.contentFingerprint,
                        audioFingerprint = bt.audioFingerprint,
                        fingerprintVersion = bt.fingerprintVersion,
                        cachedArtworkKey = null,
                        lastVerified = now,
                        availabilityState = TrackAvailabilityState.MISSING_OR_REVOKED.name
                    )
                )
            }
            bt.beatGridCorrection?.let { corr ->
                val mappedTrackId = trackIdRemap.getValue(bt.trackId)
                database.saveBeatGridCorrection(corr.copy(trackId = mappedTrackId))
            }
        }

        database.driver.writeTransaction { s ->
            database.insertProject(
                s,
                payload.project.copy(
                    projectId = targetProjectId,
                    name = targetProjectName,
                    updatedAtUtc = now,
                    deletedAt = null
                )
            )

            val occIdMap = LinkedHashMap<String, String>()
            for (occ in sortedOccs) {
                val newOccId = if (isDuplicateProject) {
                    "occ_${UUID.randomUUID()}"
                } else {
                    occ.occurrenceId
                }
                occIdMap[occ.occurrenceId] = newOccId
                val mappedTrackId = trackIdRemap[occ.trackId] ?: occ.trackId
                database.insertOccurrence(
                    s,
                    occ.copy(
                        occurrenceId = newOccId,
                        projectId = targetProjectId,
                        trackId = mappedTrackId
                    )
                )
            }

            for (edgeSnap in payload.edges) {
                val newEdgeId = if (isDuplicateProject) {
                    "edge_${UUID.randomUUID()}"
                } else {
                    edgeSnap.edge.edgeId
                }
                val newOut = occIdMap[edgeSnap.edge.outgoingOccurrenceId]
                    ?: rejectBackup("Edge outgoingOccurrenceId ${edgeSnap.edge.outgoingOccurrenceId} missing from occurrences")
                val newIn = occIdMap[edgeSnap.edge.incomingOccurrenceId]
                    ?: rejectBackup("Edge incomingOccurrenceId ${edgeSnap.edge.incomingOccurrenceId} missing from occurrences")

                database.insertEdgeCompleteSnapshot(
                    s,
                    EdgeCompleteSnapshot(
                        edge = edgeSnap.edge.copy(
                            edgeId = newEdgeId,
                            projectId = targetProjectId,
                            outgoingOccurrenceId = newOut,
                            incomingOccurrenceId = newIn
                        ),
                        autoPlan = edgeSnap.autoPlan?.copy(edgeId = newEdgeId),
                        overridePlan = edgeSnap.overridePlan?.copy(edgeId = newEdgeId),
                        resolvedPlan = edgeSnap.resolvedPlan?.copy(edgeId = newEdgeId)
                    )
                )
            }
        }

        val importedDetail = database.getProjectDetail(targetProjectId)!!
        return ImportBackupResult(
            importedProjectDetail = importedDetail,
            wasRemappedForDuplicateId = isDuplicateProject,
            originalProjectId = payload.project.projectId,
            tracksRequiringRelink = tracksRequiringRelink,
            preservedUnknownFields = unknownFields
        )
    }

    private fun verifyNestingDepth(rawJson: String) {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in rawJson.indices) {
            val c = rawJson[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{' || c == '[') {
                    depth++
                    if (depth > MAX_JSON_NESTING_DEPTH) {
                        rejectBackup("Backup JSON nesting depth exceeds maximum $MAX_JSON_NESTING_DEPTH")
                    }
                } else if (c == '}' || c == ']') {
                    depth = (depth - 1).coerceAtLeast(0)
                }
            }
        }
    }

    private fun computeObjectChecksum(objWithoutChecksum: JsonObject): String {
        val sortedEntries = objWithoutChecksum.entries.sortedBy { it.key }
        val canonicalMap = LinkedHashMap<String, JsonElement>()
        for ((k, v) in sortedEntries) {
            canonicalMap[k] = v
        }
        val bytes = json.encodeToString(JsonObject(canonicalMap)).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun sanitizePublicLabel(input: String): String {
        return DiagnosticsRingBuffer.redact(input)
    }

    private fun rejectBackup(reason: String): Nothing {
        diagnostics?.record(
            category = "ProjectBackup",
            errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
            message = reason
        )
        throw InvalidPlanPersistenceException(
            errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
            message = reason
        )
    }
}
