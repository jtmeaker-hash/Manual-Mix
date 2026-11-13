/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.setflow.R
import app.setflow.SetFlowApplication
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.SchedulerPhase
import app.setflow.service.SetFlowServiceBoundary
import app.setflow.ui.SetFlowActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground MediaSessionService orchestrating whole-set automatic DJ playback (Stage 10, Task 3 & 4).
 *
 * Guarantees:
 * - Runs as a foreground service with `foregroundServiceType="mediaPlayback"` outside Activity lifecycle.
 * - Single-owner MediaSession providing lock screen controls, system notification, and headset events.
 * - Handles `AUDIOFOCUS_GAIN`, `AUDIOFOCUS_LOSS`, `AUDIOFOCUS_LOSS_TRANSIENT`, and ducking (-12 dB).
 * - Handles `ACTION_AUDIO_BECOMING_NOISY` (e.g. headphone unplug / Bluetooth disconnect) by pausing safely.
 * - Continuously persists safe resume checkpoints to [PlaybackResumeStore] for process recreation.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SetPlaybackService : MediaSessionService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var mediaSession: MediaSession? = null
    private var basePlayer: ExoPlayer? = null
    private var forwardingPlayer: ForwardingPlayer? = null

    private var audioFocusRequest: AudioFocusRequest? = null
    private var wasPlayingBeforeTransientLoss: Boolean = false
    private var isReceiverRegistered: Boolean = false

    private val services: SetFlowServiceBoundary
        get() = (application as SetFlowApplication).services

    private val queueController: SetPlaybackQueueController
        get() = services.setPlaybackQueueController

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                services.diagnostics.record(
                    category = "SetPlaybackService",
                    phase = SchedulerPhase.SAFE_FALLBACK,
                    errorCode = AudioErrorCode.A09_OUTPUT_ROUTE_CHANGED,
                    message = "Audio becoming noisy received (headphone unplugged/BT disconnected); pausing set playback"
                )
                queueController.pause()
                queueController.twoDeckController.onOutputRouteChanged("becoming_noisy")
                updateNotification(isForeground = true)
            }
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                services.diagnostics.record(
                    category = "SetPlaybackService",
                    phase = SchedulerPhase.RUNNING,
                    message = "Audio focus regained"
                )
                queueController.twoDeckController.onAudioFocusChange(focusLost = false)
                if (wasPlayingBeforeTransientLoss) {
                    wasPlayingBeforeTransientLoss = false
                    queueController.resume()
                }
                updateNotification(isForeground = true)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                services.diagnostics.record(
                    category = "SetPlaybackService",
                    phase = SchedulerPhase.RUNNING,
                    errorCode = AudioErrorCode.A08_AUDIO_FOCUS_LOST,
                    message = "Audio focus lost transient (can duck): ducking -12 dB"
                )
                queueController.twoDeckController.onAudioFocusChange(
                    focusLost = true,
                    transient = true,
                    canDuck = true
                )
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                services.diagnostics.record(
                    category = "SetPlaybackService",
                    phase = SchedulerPhase.SAFE_FALLBACK,
                    errorCode = AudioErrorCode.A08_AUDIO_FOCUS_LOST,
                    message = "Audio focus lost transient (e.g. call/notification): pausing set playback"
                )
                wasPlayingBeforeTransientLoss = queueController.isPlaying
                queueController.twoDeckController.onAudioFocusChange(
                    focusLost = true,
                    transient = true,
                    canDuck = false
                )
                queueController.pause()
                updateNotification(isForeground = true)
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                services.diagnostics.record(
                    category = "SetPlaybackService",
                    phase = SchedulerPhase.STOPPED,
                    errorCode = AudioErrorCode.A08_AUDIO_FOCUS_LOST,
                    message = "Audio focus lost permanently: pausing and releasing focus"
                )
                wasPlayingBeforeTransientLoss = false
                queueController.twoDeckController.onAudioFocusChange(
                    focusLost = true,
                    transient = false,
                    canDuck = false
                )
                queueController.pause()
                abandonAudioFocus()
                updateNotification(isForeground = false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Build base player and forwarding player routing controls to SetPlaybackQueueController
        val exo = ExoPlayer.Builder(this).build()
        basePlayer = exo

        val fwd = object : ForwardingPlayer(exo) {
            override fun play() {
                requestAudioFocus()
                queueController.resume()
            }

            override fun pause() {
                queueController.pause()
            }

            override fun seekToNext() {
                queueController.skipToNext()
            }

            override fun seekToPrevious() {
                queueController.skipToPrevious()
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                queueController.seekToGlobalUs(positionMs * 1_000L)
            }
        }
        forwardingPlayer = fwd

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SetFlowActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, fwd)
            .setSessionActivity(sessionActivityIntent)
            .setId("SetFlowMediaSession")
            .build()

        registerNoisyReceiver()

        // Observe queue progression and update notification & checkpoints
        serviceScope.launch {
            queueController.queueState.collectLatest { state ->
                onQueueStateChanged(state)
            }
        }

        services.diagnostics.record(
            category = "SetPlaybackService",
            phase = SchedulerPhase.IDLE,
            message = "SetPlaybackService initialized with MediaSession"
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SET -> {
                val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
                val occurrenceId = intent.getStringExtra(EXTRA_OCCURRENCE_ID)
                val localPosUs = intent.getLongExtra(EXTRA_LOCAL_POS_US, 0L)
                if (!projectId.isNullOrEmpty()) {
                    requestAudioFocus()
                    queueController.startSet(projectId, occurrenceId, localPosUs)
                    startForegroundNotification()
                }
            }

            ACTION_PAUSE -> {
                queueController.pause()
                updateNotification(isForeground = true)
            }

            ACTION_RESUME -> {
                requestAudioFocus()
                queueController.resume()
                updateNotification(isForeground = true)
            }

            ACTION_SKIP_NEXT -> {
                queueController.skipToNext()
                updateNotification(isForeground = true)
            }

            ACTION_SKIP_PREV -> {
                queueController.skipToPrevious()
                updateNotification(isForeground = true)
            }

            ACTION_STOP -> {
                queueController.stop()
                abandonAudioFocus()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_SEEK -> {
                val globalUs = intent.getLongExtra(EXTRA_GLOBAL_POS_US, 0L)
                queueController.seekToGlobalUs(globalUs)
                updateNotification(isForeground = true)
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    private fun onQueueStateChanged(state: SetPlaybackQueueState) {
        val player = basePlayer ?: return

        // Update player metadata for system lock screen & notification
        val meta = MediaMetadata.Builder()
            .setTitle(state.currentTrackTitle.ifEmpty { "SetFlow Set" })
            .setArtist(state.currentTrackArtist.ifEmpty { "Continuous Mix" })
            .setDisplayTitle(state.currentTrackTitle.ifEmpty { "SetFlow Set" })
            .build()

        player.playlistMetadata = meta

        // Save resume checkpoint when actively playing or paused
        val projectId = state.projectId
        val occId = state.currentOccurrenceId
        if (!projectId.isNullOrEmpty() && !occId.isNullOrEmpty() && state.phase != SetQueuePhase.IDLE) {
            services.playbackResumeStore.saveCheckpoint(
                PlaybackResumeCheckpoint(
                    projectId = projectId,
                    projectRevision = state.projectRevision,
                    occurrenceId = occId,
                    occurrenceIndex = state.currentOccurrenceIndex,
                    localPositionUs = state.currentLocalPositionUs,
                    globalPositionUs = state.currentGlobalPositionUs
                )
            )
        }

        if (state.phase == SetQueuePhase.SET_COMPLETED) {
            services.playbackResumeStore.clearCheckpoint()
            abandonAudioFocus()
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else if (state.isPlaying) {
            updateNotification(isForeground = true)
        } else {
            updateNotification(isForeground = false)
        }
    }

    private fun requestAudioFocus(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            this.audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    private fun registerNoisyReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            ContextCompat.registerReceiver(
                this,
                noisyReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isReceiverRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (isReceiverRegistered) {
            runCatching { unregisterReceiver(noisyReceiver) }
            isReceiverRegistered = false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SetFlow Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "DJ Set playback controls and status"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val state = queueController.queueState.value
        val isPlaying = state.isPlaying

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SetFlowActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SetPlaybackService::class.java).apply { action = ACTION_SKIP_PREV },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, SetPlaybackService::class.java).apply {
                action = if (isPlaying) ACTION_PAUSE else ACTION_RESUME
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, SetPlaybackService::class.java).apply { action = ACTION_SKIP_NEXT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = state.currentTrackTitle.ifEmpty { "SetFlow Set Playback" }
        val artist = if (state.currentTrackArtist.isNotEmpty()) {
            "${state.currentTrackArtist} • Track ${state.currentOccurrenceIndex + 1}/${state.totalOccurrences}"
        } else {
            state.statusMessage
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                playPauseIntent
            )
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)

        return builder.build()
    }

    private fun startForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(isForeground: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildNotification()
        if (isForeground) {
            nm.notify(NOTIFICATION_ID, notification)
        } else {
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        unregisterNoisyReceiver()
        abandonAudioFocus()
        serviceScope.cancel()

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        basePlayer?.release()
        basePlayer = null

        services.diagnostics.record(
            category = "SetPlaybackService",
            phase = SchedulerPhase.STOPPED,
            message = "SetPlaybackService destroyed and resources released"
        )
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "setflow_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SET = "app.setflow.action.START_SET"
        const val ACTION_PAUSE = "app.setflow.action.PAUSE"
        const val ACTION_RESUME = "app.setflow.action.RESUME"
        const val ACTION_STOP = "app.setflow.action.STOP"
        const val ACTION_SKIP_NEXT = "app.setflow.action.SKIP_NEXT"
        const val ACTION_SKIP_PREV = "app.setflow.action.SKIP_PREV"
        const val ACTION_SEEK = "app.setflow.action.SEEK"

        const val EXTRA_PROJECT_ID = "extra_project_id"
        const val EXTRA_OCCURRENCE_ID = "extra_occurrence_id"
        const val EXTRA_LOCAL_POS_US = "extra_local_pos_us"
        const val EXTRA_GLOBAL_POS_US = "extra_global_pos_us"
    }
}
