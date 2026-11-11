package com.github.damontecres.wholphin.services

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.github.damontecres.wholphin.data.model.AudioItem
import com.github.damontecres.wholphin.preferences.AudioNormalizationMode
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

class AudioNormalizationTest {
    @Test
    fun selectGain_offAppliesNothing() {
        assertNull(AudioNormalization.selectGain(AudioNormalizationMode.AUDIO_NORMALIZATION_OFF, -6f, -3f))
        assertNull(AudioNormalization.selectGain(AudioNormalizationMode.UNRECOGNIZED, -6f, -3f))
    }

    @Test
    fun selectGain_trackPrefersTrackAndFallsBackToAlbum() {
        val mode = AudioNormalizationMode.AUDIO_NORMALIZATION_TRACK
        assertEquals(-6f, AudioNormalization.selectGain(mode, -6f, -3f))
        assertEquals(-3f, AudioNormalization.selectGain(mode, null, -3f))
        assertNull(AudioNormalization.selectGain(mode, null, null))
    }

    @Test
    fun selectGain_albumPrefersAlbumAndFallsBackToTrack() {
        val mode = AudioNormalizationMode.AUDIO_NORMALIZATION_ALBUM
        assertEquals(-3f, AudioNormalization.selectGain(mode, -6f, -3f))
        assertEquals(-6f, AudioNormalization.selectGain(mode, -6f, null))
        assertNull(AudioNormalization.selectGain(mode, null, null))
    }

    @Test
    fun plan_noGainLeavesPlaybackUntouched() {
        assertEquals(GainPlan.NONE, AudioNormalization.plan(null))
        assertEquals(GainPlan.NONE, AudioNormalization.plan(0f))
        assertEquals(GainPlan.NONE, AudioNormalization.plan(Float.NaN))
        assertEquals(GainPlan.NONE, AudioNormalization.plan(Float.POSITIVE_INFINITY))
        assertEquals(GainPlan.NONE, AudioNormalization.plan(Float.NEGATIVE_INFINITY))
        assertFalse(GainPlan.NONE.boostEnabled)
    }

    @Test
    fun plan_negativeGainBecomesVolume() {
        val plan = AudioNormalization.plan(-6.0206f)
        assertEquals(0.5f, plan.volume, 0.0005f)
        assertEquals(0, plan.boostMb)
        assertFalse(plan.boostEnabled)

        val quiet = AudioNormalization.plan(-20f)
        assertEquals(0.1f, quiet.volume, 0.0001f)
    }

    @Test
    fun plan_positiveGainBecomesBoost() {
        val plan = AudioNormalization.plan(3.2f)
        assertEquals(1f, plan.volume, 0f)
        assertEquals(320, plan.boostMb)
        assertTrue(plan.boostEnabled)
    }

    @Test
    fun plan_tinyPositiveGainRoundsToNoBoost() {
        val plan = AudioNormalization.plan(0.004f)
        assertEquals(1f, plan.volume, 0f)
        assertEquals(0, plan.boostMb)
        assertFalse(plan.boostEnabled)
    }

    @Test
    fun toMillibels_roundsToNearest() {
        assertEquals(346, AudioNormalization.toMillibels(3.456f))
        assertEquals(-250, AudioNormalization.toMillibels(-2.5f))
        assertEquals(1200, AudioNormalization.toMillibels(12f))
    }

    @Test
    fun toLinear_matchesDecibelFormula() {
        assertEquals(1f, AudioNormalization.toLinear(0f), 0f)
        assertEquals(0.5f, AudioNormalization.toLinear(-6.0206f), 0.0005f)
        assertEquals(2f, AudioNormalization.toLinear(6.0206f), 0.002f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class AudioNormalizerTest {
    private class FakeBooster(
        val sessionId: Int,
    ) : LoudnessBooster {
        var gainMb = 0
        var isOn = false
        var wasReleased = false

        override fun setTargetGain(gainMb: Int) {
            this.gainMb = gainMb
        }

        override fun setEnabled(enabled: Boolean) {
            isOn = enabled
        }

        override fun release() {
            wasReleased = true
        }
    }

    private class Harness(
        boosterFactory: ((Int) -> LoudnessBooster)? = null,
    ) {
        val boosters = mutableListOf<FakeBooster>()
        val volumes = mutableListOf<Float>()
        var currentItem: MediaItem? = null
        val player =
            mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true) {
                every { applicationLooper } returns Looper.getMainLooper()
                every { audioSessionId } returns 42
                every { currentMediaItem } answers { currentItem }
                justRun { addListener(any()) }
                every { setVolume(capture(volumes)) } answers { }
            }
        val normalizer =
            AudioNormalizer(
                boosterFactory ?: { id -> FakeBooster(id).also { boosters.add(it) } },
            )

        fun transitionTo(
            track: Float?,
            album: Float?,
        ) {
            currentItem = mediaItem(track, album)
            normalizer.onMediaItemTransition(currentItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        }

        val lastVolume: Float get() = volumes.last()
        val booster: FakeBooster get() = boosters.last()
    }

    @Test
    fun attach_createsBoosterForPlayerSession() {
        val h = Harness()
        h.normalizer.attach(h.player)
        assertEquals(1, h.boosters.size)
        assertEquals(42, h.booster.sessionId)
        // No item yet: unity volume, boost off
        assertEquals(1f, h.lastVolume, 0f)
        assertFalse(h.booster.isOn)
    }

    @Test
    fun negativeGain_goesToVolumeOnly() {
        val h = Harness()
        h.normalizer.attach(h.player)
        h.normalizer.mode = AudioNormalizationMode.AUDIO_NORMALIZATION_TRACK
        h.transitionTo(track = -6.0206f, album = -2f)
        assertEquals(0.5f, h.lastVolume, 0.0005f)
        assertEquals(0, h.booster.gainMb)
        assertFalse(h.booster.isOn)
    }

    @Test
    fun positiveGain_goesToBoosterOnly() {
        val h = Harness()
        h.normalizer.attach(h.player)
        h.normalizer.mode = AudioNormalizationMode.AUDIO_NORMALIZATION_TRACK
        h.transitionTo(track = 4f, album = null)
        assertEquals(1f, h.lastVolume, 0f)
        assertEquals(400, h.booster.gainMb)
        assertTrue(h.booster.isOn)

        // The next song is louder than the reference: boost off again, volume down
        h.transitionTo(track = -3f, album = null)
        assertEquals(AudioNormalization.toLinear(-3f), h.lastVolume, 0.0001f)
        assertEquals(0, h.booster.gainMb)
        assertFalse(h.booster.isOn)
    }

    @Test
    fun albumMode_usesAlbumGain() {
        val h = Harness()
        h.normalizer.attach(h.player)
        h.normalizer.mode = AudioNormalizationMode.AUDIO_NORMALIZATION_ALBUM
        h.transitionTo(track = -6f, album = -2f)
        assertEquals(AudioNormalization.toLinear(-2f), h.lastVolume, 0.0001f)
    }

    @Test
    fun modeChange_reappliesToCurrentItem() {
        val h = Harness()
        h.normalizer.attach(h.player)
        h.normalizer.mode = AudioNormalizationMode.AUDIO_NORMALIZATION_TRACK
        h.transitionTo(track = -6f, album = -2f)
        h.normalizer.mode = AudioNormalizationMode.AUDIO_NORMALIZATION_ALBUM
        assertEquals(AudioNormalization.toLinear(-2f), h.lastVolume, 0.0001f)
        h.normalizer.mode = AudioNormalizationMode.AUDIO_NORMALIZATION_OFF
        assertEquals(1f, h.lastVolume, 0f)
        assertFalse(h.booster.isOn)
    }

    @Test
    fun offMode_ignoresGains() {
        val h = Harness()
        h.normalizer.attach(h.player)
        h.transitionTo(track = 4f, album = -2f)
        assertEquals(1f, h.lastVolume, 0f)
        assertEquals(0, h.booster.gainMb)
        assertFalse(h.booster.isOn)
    }

    @Test
    fun itemWithoutGains_playsUnchanged() {
        val h = Harness()
        h.normalizer.attach(h.player)
        h.normalizer.mode = AudioNormalizationMode.AUDIO_NORMALIZATION_TRACK
        h.transitionTo(track = null, album = null)
        assertEquals(1f, h.lastVolume, 0f)
        assertFalse(h.booster.isOn)
        // Playlist cleared
        h.normalizer.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertEquals(1f, h.lastVolume, 0f)
    }

    @Test
    fun sessionChange_recreatesBoosterAndReapplies() {
        val h = Harness()
        h.normalizer.attach(h.player)
        h.normalizer.mode = AudioNormalizationMode.AUDIO_NORMALIZATION_TRACK
        h.transitionTo(track = 2f, album = null)
        val first = h.booster
        h.normalizer.onAudioSessionIdChanged(77)
        assertTrue(first.wasReleased)
        assertEquals(2, h.boosters.size)
        assertEquals(77, h.booster.sessionId)
        assertEquals(200, h.booster.gainMb)
        assertTrue(h.booster.isOn)
    }

    @Test
    fun missingEnhancer_stillAppliesVolume() {
        val h = Harness(boosterFactory = { throw UnsupportedOperationException("no effects") })
        h.normalizer.attach(h.player)
        h.normalizer.mode = AudioNormalizationMode.AUDIO_NORMALIZATION_TRACK
        h.transitionTo(track = -6.0206f, album = null)
        assertEquals(0.5f, h.lastVolume, 0.0005f)
        // A boost cannot be applied without the enhancer, playback continues at unity
        h.transitionTo(track = 4f, album = null)
        assertEquals(1f, h.lastVolume, 0f)
    }

    @Test
    fun release_releasesBooster() {
        val h = Harness()
        h.normalizer.attach(h.player)
        h.normalizer.release()
        assertTrue(h.booster.wasReleased)
    }

    companion object {
        fun mediaItem(
            track: Float?,
            album: Float?,
        ): MediaItem =
            MediaItem
                .Builder()
                .setUri("https://example.invalid/audio")
                .setMediaId("test")
                .setTag(
                    AudioItem(
                        id = UUID.randomUUID(),
                        albumId = null,
                        artistId = null,
                        title = "Song",
                        albumTitle = null,
                        artistNames = null,
                        runtime = null,
                        imageUrl = null,
                        hasLyrics = false,
                        normalizationGain = track,
                        albumNormalizationGain = album,
                    ),
                ).build()
    }
}
