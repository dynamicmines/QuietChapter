package com.quietchapter

import android.content.Context
import android.net.Uri
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.quietchapter.core.PlaybackAction
import com.quietchapter.core.clampSeekPosition
import java.io.File

/** Media3 is the sole audio focus owner. Production lifetime belongs to PlaybackService. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class])
class AudioPlaybackController(
    context: Context,
    private val interrupted: (String) -> Unit,
    private val finished: () -> Unit,
    private val changed: () -> Unit,
) {
    private val systemAudio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    // Media3 1.11.1 dynamic scheduling reports ~250ms position steps on this target.
    // Sentence-boundary pauses require the supported high-resolution scheduling mode.
    val player: ExoPlayer = ExoPlayer.Builder(context).setSeekBackIncrementMs(3_000)
        .experimentalSetDynamicSchedulingEnabled(false).build().apply {
        setWakeMode(C.WAKE_MODE_LOCAL)
        setAudioAttributes(AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        setHandleAudioBecomingNoisy(true)
        addListener(object : Player.Listener {
            override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                if (reason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) interrupted("Audio focus suppressed")
                changed()
            }
            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                if (!ready && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    interrupted("Audio focus lost")
                } else if (!ready && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                    interrupted("Audio output became noisy")
                }
                changed()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) finished()
                changed()
            }
            override fun onPlayerError(error: PlaybackException) {
                interrupted("Player error: ${error.errorCodeName}")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = changed()
        })
    }

    fun load(file: File, positionMs: Long) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.seekTo(positionMs.coerceAtLeast(0))
        player.prepare()
    }

    fun apply(action: PlaybackAction) {
        when (action) {
            PlaybackAction.PLAY -> {
                // Eligibility veto for a fresh player without suppression history.
                // This reads call/communication mode; it never requests or owns focus.
                if (systemAudio.mode != AudioManager.MODE_NORMAL) {
                    interrupted("System call/communication audio mode blocks playback")
                    return
                }
                // Do not re-request focus while Media3 still reports an interruption.
                // In particular, a delayed focus request must not clear our pause intent.
                if (player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                    interrupted("Playback remains suppressed by Media3")
                    return
                }
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
                // A denied focus request can leave playWhenReady=false unchanged,
                // so no change callback is guaranteed. Media3 still owns the request.
                if (!player.playWhenReady) interrupted("Media3 rejected playback request")
            }
            PlaybackAction.PAUSE -> player.pause()
            PlaybackAction.NONE -> Unit
        }
    }

    fun seek(positionMs: Long) {
        player.seekTo(clampSeekPosition(positionMs, player.duration.takeIf { it != C.TIME_UNSET } ?: 0))
    }

    fun close() = player.release()
}
