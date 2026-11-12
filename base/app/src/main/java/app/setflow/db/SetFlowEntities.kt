/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.LocalTrackRecord
import app.setflow.domain.PersistableGrantStatus
import app.setflow.domain.TrackAvailabilityState
import app.setflow.domain.TrackIdentity
import app.setflow.domain.TrackImportSource
import kotlinx.serialization.Serializable

@Serializable
enum class EdgeOrigin {
    AUTO,
    CUSTOM
}

@Serializable
enum class EdgeValidationStatus {
    UNRESOLVED,
    VALID,
    STALE_ANALYSIS,
    STALE_AUDIO_KEY,
    INVALID
}

@Serializable
enum class DatabaseOpenMode {
    READ_WRITE,
    READ_ONLY_UNSUPPORTED_SCHEMA
}

/**
 * Thrown when an optimistic concurrency check (`expectedRevision != actualRevision`) fails on Save or Reorder.
 */
class OptimisticConcurrencyException(
    val entityType: String,
    val entityId: String,
    val expectedRevision: Long,
    val actualRevision: Long
) : IllegalStateException(
    "Optimistic concurrency conflict on $entityType($entityId): expected revision $expectedRevision, found $actualRevision"
)

/**
 * Thrown when attempting a destructive or mutating write on a database opened in read-only recovery mode
 * due to an unknown future schema version or corrupt metadata (D08, ACC-39).
 */
class ReadOnlyUnsupportedSchemaException(
    val foundVersion: Int,
    val maxSupportedVersion: Int,
    override val message: String
) : IllegalStateException(message)

/**
 * Thrown when a transition plan, override, or backup violates structural/bounds invariants (D11, S16, S17).
 */
class InvalidPlanPersistenceException(
    val errorCode: AudioErrorCode,
    override val message: String,
    val violations: List<String> = emptyList()
) : IllegalArgumentException(message)

@Entity(
    tableName = "projects",
    indices = [Index(value = ["updatedAtUtc"])]
)
@Serializable
data class ProjectEntity(
    @PrimaryKey
    @ColumnInfo(name = "projectId")
    val projectId: String,
    @ColumnInfo(name = "schemaVersion")
    val schemaVersion: Int = CURRENT_PROJECT_SCHEMA_VERSION,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "createdAtUtc")
    val createdAtUtc: Long,
    @ColumnInfo(name = "updatedAtUtc")
    val updatedAtUtc: Long,
    @ColumnInfo(name = "editRevision")
    val editRevision: Long = 1L,
    @ColumnInfo(name = "settingsSnapshot")
    val settingsSnapshot: String = "{}",
    @ColumnInfo(name = "deletedAt")
    val deletedAt: Long? = null
) {
    companion object {
        const val CURRENT_PROJECT_SCHEMA_VERSION = 3
    }
}

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["contentFingerprint"]),
        Index(value = ["contentUri"])
    ]
)
@Serializable
data class TrackEntity(
    @PrimaryKey
    @ColumnInfo(name = "trackId")
    val trackId: String,
    @ColumnInfo(name = "displayName")
    val displayName: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "artist")
    val artist: String,
    @ColumnInfo(name = "album")
    val album: String,
    @ColumnInfo(name = "durationUs")
    val durationUs: Long,
    @ColumnInfo(name = "contentUri")
    val contentUri: String,
    @ColumnInfo(name = "providerAuthority")
    val providerAuthority: String? = null,
    @ColumnInfo(name = "mime")
    val mime: String,
    @ColumnInfo(name = "codecDescription")
    val codecDescription: String = mime,
    @ColumnInfo(name = "sampleRateHz")
    val sampleRateHz: Int = 44100,
    @ColumnInfo(name = "channelCount")
    val channelCount: Int = 2,
    @ColumnInfo(name = "bitsPerSample")
    val bitsPerSample: Int = 16,
    @ColumnInfo(name = "size")
    val size: Long,
    @ColumnInfo(name = "modifiedTime")
    val modifiedTime: Long,
    @ColumnInfo(name = "mediaStoreId")
    val mediaStoreId: Long? = null,
    @ColumnInfo(name = "grantStatus")
    val grantStatus: String = PersistableGrantStatus.PERSISTED.name,
    @ColumnInfo(name = "contentFingerprint")
    val contentFingerprint: String,
    @ColumnInfo(name = "audioFingerprint")
    val audioFingerprint: String? = null,
    @ColumnInfo(name = "fingerprintVersion")
    val fingerprintVersion: Int = TrackIdentity.FINGERPRINT_VERSION_V1,
    @ColumnInfo(name = "cachedArtworkKey")
    val cachedArtworkKey: String? = null,
    @ColumnInfo(name = "lastVerified")
    val lastVerified: Long,
    @ColumnInfo(name = "availabilityState")
    val availabilityState: String = TrackAvailabilityState.AVAILABLE.name
) {
    fun toLocalTrackRecord(): LocalTrackRecord = LocalTrackRecord(
        trackId = trackId,
        displayName = displayName,
        title = title,
        artist = artist,
        album = album,
        durationUs = durationUs,
        mimeType = mime,
        codecDescription = codecDescription,
        sampleRateHz = sampleRateHz,
        channelCount = channelCount,
        bitsPerSample = bitsPerSample,
        identity = TrackIdentity(
            contentUri = contentUri,
            providerAuthority = providerAuthority,
            mediaStoreId = mediaStoreId,
            sizeBytes = size,
            lastModifiedMs = modifiedTime,
            grantStatus = runCatching { PersistableGrantStatus.valueOf(grantStatus) }
                .getOrDefault(PersistableGrantStatus.PERSISTED),
            contentFingerprint = contentFingerprint,
            fingerprintVersion = fingerprintVersion,
            verified10sAudioKey = audioFingerprint,
            audioKey = audioFingerprint
        ),
        importSource = TrackImportSource.SAF_DOCUMENT_PICKER,
        cachedArtworkKey = cachedArtworkKey,
        importedAtUtcMs = lastVerified,
        lastVerifiedUtcMs = lastVerified,
        availabilityState = runCatching { TrackAvailabilityState.valueOf(availabilityState) }
            .getOrDefault(TrackAvailabilityState.AVAILABLE)
    )

    companion object {
        fun fromLocalTrackRecord(r: LocalTrackRecord): TrackEntity = TrackEntity(
            trackId = r.trackId,
            displayName = r.displayName,
            title = r.title,
            artist = r.artist,
            album = r.album,
            durationUs = r.durationUs,
            contentUri = r.identity.contentUri,
            providerAuthority = r.identity.providerAuthority,
            mime = r.mimeType,
            codecDescription = r.codecDescription,
            sampleRateHz = r.sampleRateHz,
            channelCount = r.channelCount,
            bitsPerSample = r.bitsPerSample,
            size = r.identity.sizeBytes,
            modifiedTime = r.identity.lastModifiedMs,
            mediaStoreId = r.identity.mediaStoreId,
            grantStatus = r.identity.grantStatus.name,
            contentFingerprint = r.identity.contentFingerprint,
            audioFingerprint = r.identity.audioKey ?: r.identity.verified10sAudioKey,
            fingerprintVersion = r.identity.fingerprintVersion,
            cachedArtworkKey = r.cachedArtworkKey,
            lastVerified = r.lastVerifiedUtcMs,
            availabilityState = r.availabilityState.name
        )
    }
}

@Entity(
    tableName = "occurrences",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["projectId"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["trackId"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["projectId", "positionIndex"], unique = true),
        Index(value = ["trackId"])
    ]
)
@Serializable
data class OccurrenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "occurrenceId")
    val occurrenceId: String,
    @ColumnInfo(name = "projectId")
    val projectId: String,
    @ColumnInfo(name = "trackId")
    val trackId: String,
    @ColumnInfo(name = "positionIndex")
    val positionIndex: Int,
    @ColumnInfo(name = "playbackGainDb")
    val playbackGainDb: Double? = null,
    @ColumnInfo(name = "sourceRangeStartUs")
    val sourceRangeStartUs: Long? = null,
    @ColumnInfo(name = "sourceRangeEndUs")
    val sourceRangeEndUs: Long? = null
)

@Entity(
    tableName = "edges",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["projectId"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = OccurrenceEntity::class,
            parentColumns = ["occurrenceId"],
            childColumns = ["outgoingOccurrenceId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = OccurrenceEntity::class,
            parentColumns = ["occurrenceId"],
            childColumns = ["incomingOccurrenceId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(
            value = ["projectId", "outgoingOccurrenceId", "incomingOccurrenceId"],
            unique = true
        ),
        Index(value = ["outgoingOccurrenceId"]),
        Index(value = ["incomingOccurrenceId"])
    ]
)
@Serializable
data class EdgeEntity(
    @PrimaryKey
    @ColumnInfo(name = "edgeId")
    val edgeId: String,
    @ColumnInfo(name = "projectId")
    val projectId: String,
    @ColumnInfo(name = "outgoingOccurrenceId")
    val outgoingOccurrenceId: String,
    @ColumnInfo(name = "incomingOccurrenceId")
    val incomingOccurrenceId: String,
    @ColumnInfo(name = "relationVersion")
    val relationVersion: Int = 1,
    @ColumnInfo(name = "planRevision")
    val planRevision: Long = 1L,
    @ColumnInfo(name = "origin")
    val origin: EdgeOrigin = EdgeOrigin.AUTO,
    @ColumnInfo(name = "lastResolvedFingerprint")
    val lastResolvedFingerprint: String = "",
    @ColumnInfo(name = "lastValidationStatus")
    val lastValidationStatus: EdgeValidationStatus = EdgeValidationStatus.UNRESOLVED
)

@Entity(
    tableName = "auto_plan_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = EdgeEntity::class,
            parentColumns = ["edgeId"],
            childColumns = ["edgeId"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
@Serializable
data class AutoPlanSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "edgeId")
    val edgeId: String,
    @ColumnInfo(name = "engineVersion")
    val engineVersion: String,
    @ColumnInfo(name = "analyzerVersion")
    val analyzerVersion: String,
    @ColumnInfo(name = "parametersJson")
    val parametersJson: String = "{}",
    @ColumnInfo(name = "analysisKeyA")
    val analysisKeyA: String,
    @ColumnInfo(name = "analysisKeyB")
    val analysisKeyB: String,
    @ColumnInfo(name = "planCanonicalJson")
    val planCanonicalJson: String,
    @ColumnInfo(name = "generatedAtUtc")
    val generatedAtUtc: Long
)

@Entity(
    tableName = "override_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = EdgeEntity::class,
            parentColumns = ["edgeId"],
            childColumns = ["edgeId"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
@Serializable
data class OverrideSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "edgeId")
    val edgeId: String,
    @ColumnInfo(name = "overrideSchema")
    val overrideSchema: Int = CURRENT_OVERRIDE_SCHEMA_VERSION,
    @ColumnInfo(name = "revision")
    val revision: Long = 1L,
    @ColumnInfo(name = "exitAnchorUs")
    val exitAnchorUs: Long? = null,
    @ColumnInfo(name = "entryAnchorUs")
    val entryAnchorUs: Long? = null,
    @ColumnInfo(name = "spanUs")
    val spanUs: Long? = null,
    @ColumnInfo(name = "barCount")
    val barCount: Int? = null,
    @ColumnInfo(name = "pulseMode")
    val pulseMode: String? = null,
    @ColumnInfo(name = "plannedRatePpm")
    val plannedRatePpm: Int? = null,
    @ColumnInfo(name = "laneOverridesJson")
    val laneOverridesJson: String? = null,
    @ColumnInfo(name = "presetId")
    val presetId: String? = null,
    @ColumnInfo(name = "correctedGridVersionA")
    val correctedGridVersionA: Int? = null,
    @ColumnInfo(name = "correctedGridVersionB")
    val correctedGridVersionB: Int? = null,
    @ColumnInfo(name = "userEditedFieldsBitset")
    val userEditedFieldsBitset: Int = 0,
    @ColumnInfo(name = "editTimestampUtc")
    val editTimestampUtc: Long
) {
    companion object {
        const val CURRENT_OVERRIDE_SCHEMA_VERSION = 1
        const val BIT_EXIT_ANCHOR = 1 shl 0
        const val BIT_ENTRY_ANCHOR = 1 shl 1
        const val BIT_SPAN = 1 shl 2
        const val BIT_BAR_COUNT = 1 shl 3
        const val BIT_PULSE_MODE = 1 shl 4
        const val BIT_PLANNED_RATE = 1 shl 5
        const val BIT_LANE_OVERRIDES = 1 shl 6
        const val BIT_PRESET_ID = 1 shl 7
    }
}

@Entity(
    tableName = "resolved_plan_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = EdgeEntity::class,
            parentColumns = ["edgeId"],
            childColumns = ["edgeId"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
@Serializable
data class ResolvedPlanSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "edgeId")
    val edgeId: String,
    @ColumnInfo(name = "resolutionRevision")
    val resolutionRevision: Long,
    @ColumnInfo(name = "canonicalSegueJson")
    val canonicalSegueJson: String,
    @ColumnInfo(name = "planHashSha256")
    val planHashSha256: String,
    @ColumnInfo(name = "sourceKeys")
    val sourceKeys: String,
    @ColumnInfo(name = "warnings")
    val warnings: String = "",
    @ColumnInfo(name = "validatedAtUtc")
    val validatedAtUtc: Long
)

@Entity(
    tableName = "beat_grid_corrections",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["trackId"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
@Serializable
data class BeatGridCorrectionEntity(
    @PrimaryKey
    @ColumnInfo(name = "trackId")
    val trackId: String,
    @ColumnInfo(name = "revision")
    val revision: Long = 1L,
    @ColumnInfo(name = "bpmOverride")
    val bpmOverride: Double? = null,
    @ColumnInfo(name = "phaseAnchorUs")
    val phaseAnchorUs: Long? = null,
    @ColumnInfo(name = "beatsPerBar")
    val beatsPerBar: Int? = null,
    @ColumnInfo(name = "regionStartUs")
    val regionStartUs: Long? = null,
    @ColumnInfo(name = "regionEndUs")
    val regionEndUs: Long? = null,
    @ColumnInfo(name = "confidenceOverride")
    val confidenceOverride: Double? = null,
    @ColumnInfo(name = "createdAtUtc")
    val createdAtUtc: Long
)

@Entity(
    tableName = "project_backup_records",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["projectId"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index(value = ["projectId"])]
)
@Serializable
data class ProjectBackupRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "backupId")
    val backupId: String,
    @ColumnInfo(name = "projectId")
    val projectId: String,
    @ColumnInfo(name = "backupRevision")
    val backupRevision: Long,
    @ColumnInfo(name = "fileUri")
    val fileUri: String,
    @ColumnInfo(name = "checksum")
    val checksum: String,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "status")
    val status: String
)

@Entity(
    tableName = "export_jobs",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["projectId"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index(value = ["projectId"])]
)
@Serializable
data class ExportJobEntity(
    @PrimaryKey
    @ColumnInfo(name = "jobId")
    val jobId: String,
    @ColumnInfo(name = "projectId")
    val projectId: String,
    @ColumnInfo(name = "projectRevision")
    val projectRevision: Long,
    @ColumnInfo(name = "format")
    val format: String,
    @ColumnInfo(name = "destinationUri")
    val destinationUri: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "progress")
    val progress: Double,
    @ColumnInfo(name = "plannedDurationFrames")
    val plannedDurationFrames: Long,
    @ColumnInfo(name = "failureCode")
    val failureCode: String? = null,
    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
    @ColumnInfo(name = "completedAt")
    val completedAt: Long? = null
)

/**
 * Composite bundle of an edge and its associated plan snapshots.
 */
@Serializable
data class EdgeCompleteSnapshot(
    val edge: EdgeEntity,
    val autoPlan: AutoPlanSnapshotEntity? = null,
    val overridePlan: OverrideSnapshotEntity? = null,
    val resolvedPlan: ResolvedPlanSnapshotEntity? = null
)

@Serializable
data class OccurrenceWithTrack(
    val occurrence: OccurrenceEntity,
    val track: TrackEntity
)

@Serializable
data class ProjectDetailSnapshot(
    val project: ProjectEntity,
    val occurrences: List<OccurrenceWithTrack>,
    val edges: List<EdgeCompleteSnapshot>,
    val canUndo: Boolean = false
)

/**
 * In-memory editor draft derived by combining [AutoPlanSnapshotEntity] + [OverrideSnapshotEntity]
 * without mutating the committed database until explicit Save (D12).
 */
data class EdgeDraftState(
    val edgeId: String,
    val basePlanRevision: Long,
    val exitAnchorUs: Long,
    val entryAnchorUs: Long,
    val spanUs: Long,
    val barCount: Int?,
    val pulseMode: String?,
    val plannedRatePpm: Int,
    val laneOverridesJson: String?,
    val presetId: String?,
    val userEditedFieldsBitset: Int
)
