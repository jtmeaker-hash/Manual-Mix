/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import app.myspoti.automix.segue.LaneSide
import app.myspoti.automix.segue.SeguePlan
import com.music.spotui.automix.SegueProcessor
import com.music.spotui.automix.SegueSink
import com.music.spotui.di.EqualizerProcessor
import com.music.spotui.di.SpatialProcessor
import java.io.Closeable

/**
 * Android Media3 dual-ExoPlayer hardware bridge preserving upstream Myspoti's `SegueProcessor` + `SegueSink`
 * audio chain (`setEnableAudioTrackPlaybackParams(true)`) while ensuring:
 * 1. Each deck loads ONLY its own single `MediaItem` (`REPEAT_MODE_OFF`) so Deck A can never auto-advance
 *    into Track B while Deck B is also playing Track B (preventing duplicate audio `F01`/`F02`).
 * 2. Only the primary deck holds Android AudioFocus and `handleAudioBecomingNoisy`.
 * 3. Deck B prebuffers at `volume = 0.0f` before the cue point.
 */
@OptIn(UnstableApi::class)
class AndroidMedia3DualDeckBridge(
    context: Context
) : Closeable {

    private val appContext: Context = context.applicationContext
    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    val stageA: SegueProcessor = SegueProcessor()
    val stageB: SegueProcessor = SegueProcessor()

    val playerA: ExoPlayer = buildDeckPlayer(isPrimary = true, processor = stageA)
    val playerB: ExoPlayer = buildDeckPlayer(isPrimary = false, processor = stageB)

    private fun buildDeckPlayer(isPrimary: Boolean, processor: SegueProcessor): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            init {
                setEnableAudioTrackPlaybackParams(true)
            }

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                val baseSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(processor, EqualizerProcessor(), SpatialProcessor()))
                    .build()
                return SegueSink(baseSink, processor)
            }
        }
        return ExoPlayer.Builder(appContext, renderersFactory)
            .setAudioAttributes(audioAttributes, isPrimary)
            .setHandleAudioBecomingNoisy(isPrimary)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                shuffleModeEnabled = false
                if (!isPrimary) {
                    volume = 0.0f
                }
            }
    }

    fun prepareOutgoingDeckA(contentUri: String, startPositionMs: Long) {
        stageA.disarm()
        playerA.setMediaItem(MediaItem.fromUri(Uri.parse(contentUri)), startPositionMs.coerceAtLeast(0L))
        playerA.volume = 1.0f
        playerA.prepare()
    }

    fun prebufferIncomingDeckB(contentUri: String, entryAnchorMs: Long, ratePpm: Long = 1_000_000L) {
        stageB.disarm()
        playerB.pause()
        playerB.volume = 0.0f
        val speed = (ratePpm.toDouble() / 1_000_000.0).toFloat().coerceIn(0.25f, 4.0f)
        playerB.playbackParameters = PlaybackParameters(speed)
        playerB.setMediaItem(MediaItem.fromUri(Uri.parse(contentUri)), entryAnchorMs.coerceAtLeast(0L))
        playerB.prepare()
    }

    fun armSeguePlan(plan: SeguePlan) {
        stageA.arm(plan, LaneSide.OUTGOING)
        stageB.arm(plan, LaneSide.INCOMING)
    }

    fun disarmSeguePlan() {
        stageA.disarm()
        stageB.disarm()
    }

    override fun close() {
        runCatching {
            stageA.disarm()
            stageB.disarm()
            playerA.release()
            playerB.release()
        }
    }
}
