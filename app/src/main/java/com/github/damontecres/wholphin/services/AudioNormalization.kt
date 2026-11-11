package com.github.damontecres.wholphin.services

import android.media.audiofx.AudioEffect
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
 * - [HEADROOM_DB] is subtracted from every gain first, so that nearly every song ends up needing an
 *   attenuation rather than an amplification.
 * - An attenuation becomes the player volume, 10^(dB/20). That is exact and does not involve the
 *   audio effect chain.
 * - What is left of an amplification after the headroom needs more than unity gain, which the player
 *   volume cannot provide, so it becomes the target gain of a [LoudnessEnhancer] on the audio
 *   session, in millibels.
 *
 * The enhancer is used as little as possible because it is a compressor, not a plain gain: the
 * platform effect multiplies the input by 10^(mB/2000) and then compresses everything above a knee
 * of -8 dBFS at a ratio of 7:1 (AOSP frameworks/av, media/libeffects/loudness; the knee depends on
 * the target gain but never rises above -8 dBFS). Peaks of ordinary music sit well above that knee,
 * so the effect audibly compresses every song it touches, including songs it would only turn down.
 * Keeping it off for every song that fits into the headroom keeps those songs untouched; only songs
 * that are quieter than the reference by more than the headroom go through it, and there the
 * compression is the price for not clipping.
 */
object AudioNormalization {
    /**
     * Fixed attenuation on top of the server's gain, in dB.
     *
     * With the server's reference of -18 LUFS, songs down to -24 LUFS are played through the player
     * volume alone. The listener compensates once with the device volume.
     */
    const val HEADROOM_DB = 6f

    /**
     * Whether [mode] applies any gain at all
     */
    fun isActive(mode: AudioNormalizationMode): Boolean =
        mode == AudioNormalizationMode.AUDIO_NORMALIZATION_TRACK ||
            mode == AudioNormalizationMode.AUDIO_NORMALIZATION_ALBUM

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
        val appliedDb = gainDb - HEADROOM_DB
        return if (appliedDb <= 0f) {
            GainPlan(appliedDb = appliedDb, volume = toLinear(appliedDb), boostMb = 0)
        } else {
            GainPlan(appliedDb = appliedDb, volume = 1f, boostMb = toMillibels(appliedDb))
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
 * How a gain is applied: [appliedDb] is the gain after the headroom, [volume] goes to the player
 * (1.0 is unity) and [boostMb] to the loudness enhancer (0 disables it)
 */
data class GainPlan(
    val appliedDb: Float,
    val volume: Float,
    val boostMb: Int,
) {
    val boostEnabled: Boolean get() = boostMb > 0

    companion object {
        val NONE = GainPlan(appliedDb = 0f, volume = 1f, boostMb = 0)
    }
}

/**
 * What [AudioNormalizer] did for the current item, for the log and for tests
 */
data class AppliedGain(
    /**
     * The gain the server reported for the chosen mode, null if it had none
     */
    val serverGainDb: Float?,
    val plan: GainPlan,
    /**
     * Whether the loudness enhancer is enabled now; false when the plan has no boost, when there is
     * no enhancer, or when the platform refused to enable it
     */
    val boostActive: Boolean,
)

/**
 * The part of [LoudnessEnhancer] that [AudioNormalizer] uses, so that it can be replaced in tests
 */
interface LoudnessBooster {
    fun setTargetGain(gainMb: Int)

    /**
     * Enable or disable the effect and return whether it is enabled afterwards
     */
    fun setEnabled(enabled: Boolean): Boolean

    fun release()
}

private class PlatformLoudnessBooster(
    audioSessionId: Int,
) : LoudnessBooster {
    private val enhancer = LoudnessEnhancer(audioSessionId)

    override fun setTargetGain(gainMb: Int) = enhancer.setTargetGain(gainMb)

    override fun setEnabled(enabled: Boolean): Boolean {
        // Unlike setTargetGain, AudioEffect.setEnabled reports a refusal through its return value
        val status = enhancer.setEnabled(enabled)
        if (status != AudioEffect.SUCCESS) {
            Timber.w("AudioNormalization: LoudnessEnhancer.setEnabled(%s) returned %d", enabled, status)
        }
        return enhancer.enabled
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
     * The outcome of the most recent application, null before the first one
     */
    var lastApplied: AppliedGain? = null
        private set

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
        val boostActive =
            booster?.let { booster ->
                runCatching {
                    booster.setTargetGain(plan.boostMb)
                    booster.setEnabled(plan.boostEnabled)
                }.getOrElse {
                    Timber.w(it, "AudioNormalization: could not set a boost of %d mB", plan.boostMb)
                    runCatching { booster.setEnabled(false) }
                    false
                }
            } ?: false
        if (plan.boostEnabled && !boostActive) {
            Timber.w("AudioNormalization: a boost of %d mB was wanted but the enhancer is not active", plan.boostMb)
        }
        lastApplied = AppliedGain(gainDb, plan, boostActive)
        if (audio != null) {
            Timber.i(
                "AudioNormalization: mode=%s track=%s dB album=%s dB -> %s dB, applied %.2f dB after %.1f dB headroom " +
                    "(volume=%.3f, boost=%d mB, boost active=%s) for %s",
                mode,
                audio.normalizationGain,
                audio.albumNormalizationGain,
                gainDb,
                plan.appliedDb,
                AudioNormalization.HEADROOM_DB,
                plan.volume,
                plan.boostMb,
                boostActive,
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
