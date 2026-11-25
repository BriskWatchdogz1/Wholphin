package com.github.damontecres.wholphin.data.model

import androidx.compose.runtime.Stable
import com.github.damontecres.wholphin.services.EntryRule
import com.github.damontecres.wholphin.services.artistKeyOf
import org.jellyfin.sdk.model.extensions.ticks
import java.util.UUID
import kotlin.time.Duration

/**
 * Represents audio or a song as a stripped down [BaseItem] since there may be a lot of these created.
 *
 * Typically added to a MediaItem as the tag for reference later
 *
 * The "key" can be used by a Compose LazyList key function as it will uniquely identify this particular
 * audio even if the same song is added to the queue multiple times
 */
@Stable
data class AudioItem(
    val key: Long = keyTracker++,
    val id: UUID,
    val albumId: UUID?,
    val artistId: UUID?,
    val title: String?,
    val albumTitle: String?,
    val artistNames: String?,
    val runtime: Duration?,
    val imageUrl: String?,
    val hasLyrics: Boolean,
    /** How this entry is judged against the disliked songs, set when it is queued */
    val rule: EntryRule = EntryRule.FULL,
    /** The artist as the smart shuffle and autoplay compare it, see [artistKeyOf] */
    val artistKey: String? = null,
) {
    companion object {
        private var keyTracker = 0L

        fun from(
            item: BaseItem,
            imageUrl: String?,
            rule: EntryRule = EntryRule.FULL,
        ): AudioItem =
            AudioItem(
                id = item.id,
                albumId = item.data.albumId,
                artistId =
                    item.data.artistItems
                        ?.firstOrNull()
                        ?.id,
                title = item.title,
                albumTitle = item.data.album,
                artistNames = item.data.artists?.joinToString(", "),
                runtime = item.data.runTimeTicks?.ticks,
                imageUrl = imageUrl,
                hasLyrics = item.data.hasLyrics == true,
                rule = rule,
                artistKey = artistKeyOf(item.data),
            )
    }
}
