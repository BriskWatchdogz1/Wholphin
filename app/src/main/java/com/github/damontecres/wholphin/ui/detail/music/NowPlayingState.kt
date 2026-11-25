package com.github.damontecres.wholphin.ui.detail.music

import androidx.compose.runtime.Stable
import com.github.damontecres.wholphin.data.model.AudioItem
import com.github.damontecres.wholphin.services.BackdropResult
import com.github.damontecres.wholphin.services.MusicServiceState
import org.jellyfin.sdk.model.api.LyricDto
import java.util.UUID

@Stable
data class NowPlayingState(
    val musicServiceState: MusicServiceState,
    val currentMediaItem: AudioItem? = null,
    val lyrics: LyricDto? = null,
    val currentLyricIndex: Int? = null,
    val visualizerPermissions: Boolean = false,
    val backdropResult: BackdropResult = BackdropResult.NONE,
    /** Whether the current song is a favorite, null while unknown */
    val isFavorite: Boolean? = null,
) {
    val hasLyrics: Boolean get() = lyrics != null && lyrics.lyrics.isNotEmpty()
}

/**
 * Sets the favorite state only if [itemId] is still the current song, so a late answer for a previous
 * song does not set the heart of the new one
 */
internal fun NowPlayingState.withFavorite(
    itemId: UUID,
    isFavorite: Boolean,
): NowPlayingState = if (currentMediaItem?.id == itemId) copy(isFavorite = isFavorite) else this

/**
 * The state after the player moved to [audio]: lyrics and the favorite state are unknown until loaded
 */
internal fun NowPlayingState.onSongChange(audio: AudioItem?): NowPlayingState =
    copy(
        currentMediaItem = audio,
        lyrics = null,
        currentLyricIndex = null,
        isFavorite = null,
    )
