/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.playback

import android.content.Context
import android.content.SharedPreferences
import app.setflow.db.ProjectDetailSnapshot

/**
 * Saved set playback position for process recreation and resume (Stage 10, Task 5).
 */
data class PlaybackResumeCheckpoint(
    val projectId: String,
    val projectRevision: Long,
    val occurrenceId: String,
    val occurrenceIndex: Int,
    val localPositionUs: Long,
    val globalPositionUs: Long,
    val savedTimestampMs: Long = System.currentTimeMillis()
)

/**
 * Persists and validates playback resume checkpoints safely (Stage 10 Task 5, F13).
 * Rejects stale checkpoints if the project edit revision has changed since the checkpoint was written.
 */
class PlaybackResumeStore(
    private val preferences: SharedPreferences? = null
) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val inMemoryFallback = mutableMapOf<String, Any>()

    fun saveCheckpoint(checkpoint: PlaybackResumeCheckpoint) {
        preferences?.edit()
            ?.putString(KEY_PROJECT_ID, checkpoint.projectId)
            ?.putLong(KEY_PROJECT_REVISION, checkpoint.projectRevision)
            ?.putString(KEY_OCCURRENCE_ID, checkpoint.occurrenceId)
            ?.putInt(KEY_OCCURRENCE_INDEX, checkpoint.occurrenceIndex)
            ?.putLong(KEY_LOCAL_POSITION_US, checkpoint.localPositionUs)
            ?.putLong(KEY_GLOBAL_POSITION_US, checkpoint.globalPositionUs)
            ?.putLong(KEY_TIMESTAMP_MS, checkpoint.savedTimestampMs)
            ?.apply()

        inMemoryFallback[KEY_PROJECT_ID] = checkpoint.projectId
        inMemoryFallback[KEY_PROJECT_REVISION] = checkpoint.projectRevision
        inMemoryFallback[KEY_OCCURRENCE_ID] = checkpoint.occurrenceId
        inMemoryFallback[KEY_OCCURRENCE_INDEX] = checkpoint.occurrenceIndex
        inMemoryFallback[KEY_LOCAL_POSITION_US] = checkpoint.localPositionUs
        inMemoryFallback[KEY_GLOBAL_POSITION_US] = checkpoint.globalPositionUs
        inMemoryFallback[KEY_TIMESTAMP_MS] = checkpoint.savedTimestampMs
    }

    fun loadCheckpoint(): PlaybackResumeCheckpoint? {
        val prefs = preferences
        if (prefs != null) {
            val projectId = prefs.getString(KEY_PROJECT_ID, null) ?: return null
            val rev = prefs.getLong(KEY_PROJECT_REVISION, -1L)
            val occId = prefs.getString(KEY_OCCURRENCE_ID, null) ?: return null
            val occIdx = prefs.getInt(KEY_OCCURRENCE_INDEX, 0)
            val localUs = prefs.getLong(KEY_LOCAL_POSITION_US, 0L)
            val globalUs = prefs.getLong(KEY_GLOBAL_POSITION_US, 0L)
            val ts = prefs.getLong(KEY_TIMESTAMP_MS, 0L)

            return PlaybackResumeCheckpoint(
                projectId = projectId,
                projectRevision = rev,
                occurrenceId = occId,
                occurrenceIndex = occIdx,
                localPositionUs = localUs,
                globalPositionUs = globalUs,
                savedTimestampMs = ts
            )
        }

        val projectId = inMemoryFallback[KEY_PROJECT_ID] as? String ?: return null
        val rev = inMemoryFallback[KEY_PROJECT_REVISION] as? Long ?: -1L
        val occId = inMemoryFallback[KEY_OCCURRENCE_ID] as? String ?: return null
        val occIdx = inMemoryFallback[KEY_OCCURRENCE_INDEX] as? Int ?: 0
        val localUs = inMemoryFallback[KEY_LOCAL_POSITION_US] as? Long ?: 0L
        val globalUs = inMemoryFallback[KEY_GLOBAL_POSITION_US] as? Long ?: 0L
        val ts = inMemoryFallback[KEY_TIMESTAMP_MS] as? Long ?: 0L

        return PlaybackResumeCheckpoint(
            projectId = projectId,
            projectRevision = rev,
            occurrenceId = occId,
            occurrenceIndex = occIdx,
            localPositionUs = localUs,
            globalPositionUs = globalUs,
            savedTimestampMs = ts
        )
    }

    fun clearCheckpoint() {
        preferences?.edit()?.clear()?.apply()
        inMemoryFallback.clear()
    }

    /**
     * Validates [checkpoint] against [currentDetail].
     * Returns true if and only if project revision matches and the checkpoint occurrence is still present.
     */
    fun validateCheckpoint(
        checkpoint: PlaybackResumeCheckpoint,
        currentDetail: ProjectDetailSnapshot
    ): Boolean {
        if (checkpoint.projectId != currentDetail.project.projectId) return false
        if (checkpoint.projectRevision != currentDetail.project.editRevision) return false
        return currentDetail.occurrences.any { it.occurrence.occurrenceId == checkpoint.occurrenceId }
    }

    companion object {
        const val PREFS_NAME = "setflow_playback_resume"
        private const val KEY_PROJECT_ID = "resume_project_id"
        private const val KEY_PROJECT_REVISION = "resume_project_revision"
        private const val KEY_OCCURRENCE_ID = "resume_occurrence_id"
        private const val KEY_OCCURRENCE_INDEX = "resume_occurrence_index"
        private const val KEY_LOCAL_POSITION_US = "resume_local_pos_us"
        private const val KEY_GLOBAL_POSITION_US = "resume_global_pos_us"
        private const val KEY_TIMESTAMP_MS = "resume_timestamp_ms"
    }
}
