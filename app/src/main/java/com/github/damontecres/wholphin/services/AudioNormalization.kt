package com.github.damontecres.wholphin.services

import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.github.damontecres.wholphin.data.model.AudioItem
import com.github.damontecres.wholphin.preferences.AudioNormalizationMode
import timber.log.Timber
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Loudness normalization for music playback.
 *
 * The server computes a gain in dB for every song and every album relative to -18 LUFS and reports
 * both on the song (`BaseItemDto.normalizationGain` and `albumNormalizationGain`). This object
 * decides which of the two to use and how to apply the result:
 *
 * - A negative gain (the song is louder than the reference, the usual case) becomes the player
 *   volume, 10^(dB/20). That is exact and does not involve the audio effect chain.
 * - A positive gain needs amplification, which the player volume cannot provide, so it becomes the
 *   target gain of a [LoudnessEnhancer] on the audio session, in millibels. The effect's compressor
 *   keeps the amplified signal from clipping.
 *
 * The enhancer is deliberately not used to turn songs down: the platform effect multiplies the input
 * by 10^(mB/2000) and then runs it through a compressor whose knee sits at -8 dBFS whatever the
 * target gain is (AOSP frameworks/av, media/libeffects/loudness), so it would also compress every
 * song it attenuates.
 */
object AudioNormalization {
    /**
     * Pick the gain to apply for a song, in dB, or null if nothing should be applied.
     *
     * Same fallbacks as the Jellyfin web client: track mode prefers the song's own gain and falls
     * back to the album's, album mode prefers the album's gain and falls back to the song's.
     */
    fun selectGain(
        mode: AudioNormalizationMode,
        trackGain: Float?,
        albumGain: Float?,
    ): Float? =
        when (mode) {
            AudioNormalizationMode.AUDIO_NORMALIZATION_TRACK -> trackGain ?: albumGain

            AudioNormalizationMode.AUDIO_NORMALIZATION_ALBUM -> albumGain ?: trackGain

            AudioNormalizationMode.AUDIO_NORMALIZATION_OFF,
            AudioNormalizationMode.UNRECOGNIZED,
            -> null
        }

    /**
     * Split a gain into the two mechanisms that apply it, see [AudioNormalization]
     */
    fun plan(gainDb: Float?): GainPlan {
        if (gainDb == null || !gainDb.isFinite()) {
            return GainPlan.NONE
        }
        return if (gainDb < 0f) {
            GainPlan(volume = toLinear(gainDb), boostMb = 0)
        } else {
            GainPlan(volume = 1f, boostMb = toMillibels(gainDb))
        }
    }

    /**
     * dB to millibels, the unit of [LoudnessEnhancer.setTargetGain]
     */
    fun toMillibels(gainDb: Float): Int = (gainDb * 100f).roundToInt()

    /**
     * dB to a linear amplitude factor, the unit of [Player.setVolume]
     */
    fun toLinear(gainDb: Float): Float = 10f.pow(gainDb / 20f)
}

/**
 * How a gain is applied: [volume] for the player (1.0 is unity) and [boostMb] for the loudness
 * enhancer (0 disables it)
 */
data class GainPlan(
    val volume: Float,
    val boostMb: Int,
) {
    val boostEnabled: Boolean get() = boostMb > 0

    companion object {
        val NONE = GainPlan(volume = 1f, boostMb = 0)
    }
}

/**
 * The part of [LoudnessEnhancer] that [AudioNormalizer] uses, so that it can be replaced in tests
 */
interface LoudnessBooster {
    fun setTargetGain(gainMb: Int)

    fun setEnabled(enabled: Boolean)

    fun release()
}

private class PlatformLoudnessBooster(
    audioSessionId: Int,
) : LoudnessBooster {
    private val enhancer = LoudnessEnhancer(audioSessionId)

    override fun setTargetGain(gainMb: Int) = enhancer.setTargetGain(gainMb)

    override fun setEnabled(enabled: Boolean) {
        enhancer.setEnabled(enabled)
    }

    override fun release() = enhancer.release()
}

/**
 * Applies the normalization gain of the current [AudioItem] to a [Player] whenever the item or the
 * [mode] changes.
 *
 * All calls happen on the player's application thread, like the [Player.Listener] callbacks.
 */
@OptIn(UnstableApi::class)
class AudioNormalizer(
    private val boosterFactory: (audioSessionId: Int) -> LoudnessBooster = ::PlatformLoudnessBooster,
) : Player.Listener {
    private var player: Player? = null
    private var booster: LoudnessBooster? = null

    var mode: AudioNormalizationMode = AudioNormalizationMode.AUDIO_NORMALIZATION_OFF
        set(value) {
            if (field != value) {
                field = value
                applyToCurrentItem()
            }
        }

    /**
     * Start following the player. The enhancer attaches to the player's current audio session and
     * follows it through [onAudioSessionIdChanged].
     */
    fun attach(player: ExoPlayer) {
        this.player = player
        player.addListener(this)
        runOnApplicationThread(player) { onAudioSessionIdChanged(player.audioSessionId) }
    }

    fun release() {
        player?.removeListener(this)
        player = null
        releaseBooster()
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        releaseBooster()
        if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            booster =
                runCatching { boosterFactory(audioSessionId) }
                    .onFailure { Timber.w(it, "AudioNormalization: no loudness enhancer for session %d", audioSessionId) }
                    .getOrNull()
        }
        applyToCurrentItem()
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        apply(mediaItem)
    }

    fun applyToCurrentItem() {
        player?.let { apply(it.currentMediaItem) }
    }

    private fun apply(mediaItem: MediaItem?) {
        val player = player ?: return
        val audio = mediaItem?.localConfiguration?.tag as? AudioItem
        val gainDb = AudioNormalization.selectGain(mode, audio?.normalizationGain, audio?.albumNormalizationGain)
        val plan = AudioNormalization.plan(gainDb)
        player.volume = plan.volume
        val boosted =
            booster?.let { booster ->
                runCatching {
                    booster.setTargetGain(plan.boostMb)
                    booster.setEnabled(plan.boostEnabled)
                    plan.boostEnabled
                }.getOrElse {
                    Timber.w(it, "AudioNormalization: could not set a boost of %d mB", plan.boostMb)
                    runCatching { booster.setEnabled(false) }
                    false
                }
            } ?: false
        if (audio != null) {
            Timber.i(
                "AudioNormalization: mode=%s track=%s dB album=%s dB -> %s dB (volume=%.3f, boost=%d mB, boost enabled=%s) for %s",
                mode,
                audio.normalizationGain,
                audio.albumNormalizationGain,
                gainDb,
                plan.volume,
                plan.boostMb,
                boosted,
                audio.title,
            )
        }
    }

    private fun releaseBooster() {
        booster?.let { runCatching { it.release() } }
        booster = null
    }

    private fun runOnApplicationThread(
        player: Player,
        block: () -> Unit,
    ) {
        if (Looper.myLooper() == player.applicationLooper) {
            block()
        } else {
            Handler(player.applicationLooper).post(block)
        }
    }
}
