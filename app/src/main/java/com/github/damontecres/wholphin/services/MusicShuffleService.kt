package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.DetailItemFields
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.playlistApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Where a shuffle takes its songs from
 */
sealed interface ShuffleSource {
    data class Playlist(
        val id: UUID,
    ) : ShuffleSource

    data class Album(
        val id: UUID,
    ) : ShuffleSource

    data class Artist(
        val id: UUID,
    ) : ShuffleSource

    /** A music library: too large to load whole, so a random sample is shuffled */
    data class Library(
        val id: UUID,
    ) : ShuffleSource
}

/**
 * Shuffles music with the smart order (see [smartShuffle]) and builds the play order in the app
 *
 * The player's own shuffle mode is never used, so the index order of the player is the play order.
 */
@Singleton
class MusicShuffleService
    @Inject
    constructor(
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val musicService: MusicService,
        private val remotePlayService: RemotePlayService,
        private val dislikes: DislikeService,
    ) {
        /**
         * Replace the queue with the songs of [source] in smart shuffle order
         *
         * A playlist, album or artist loads a light list first, orders it and queues the songs by id in chunks, so the
         * music starts after the first chunk. A library plays a random sample of [LIBRARY_SAMPLE] songs.
         */
        suspend fun shuffle(source: ShuffleSource) {
            try {
                if (source is ShuffleSource.Library) shuffleLibrary(source) else shuffleList(source)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "MusicShuffle: failed for %s", source)
            }
        }

        private suspend fun shuffleList(source: ShuffleSource) {
            val songs = loadLightList(source).filter { it.type == BaseItemKind.AUDIO }.map { it.toLightSong() }
            val rule =
                when (source) {
                    is ShuffleSource.Playlist -> musicService.ruleFor(false, false, songs.map { it.id })
                    else -> musicService.ruleFor(true, false)
                }
            val ordered = smartOrder(songs, rule, dislikes.set.value, Instant.now(), Random.Default) { it }
            Timber.i("MusicShuffle: %s, %d of %d songs", source::class.simpleName, ordered.size, songs.size)
            if (ordered.isNotEmpty()) remotePlayService.playIds(ordered.map { it.id }, rule)
        }

        /**
         * One light request: ids, artists and the user data (last played), no further fields
         */
        private suspend fun loadLightList(source: ShuffleSource): List<BaseItemDto> =
            when (source) {
                is ShuffleSource.Playlist -> {
                    api.playlistApi
                        .getPlaylistItems(playlistId = source.id, userId = serverRepository.currentUser?.id)
                        .content.items
                }

                is ShuffleSource.Album -> {
                    api.libraryApi
                        .getItems(parentId = source.id, includeItemTypes = listOf(BaseItemKind.AUDIO))
                        .content.items
                }

                is ShuffleSource.Artist -> {
                    api.libraryApi
                        .getItems(artistIds = listOf(source.id), recursive = true, includeItemTypes = listOf(BaseItemKind.AUDIO))
                        .content.items
                }

                is ShuffleSource.Library -> {
                    emptyList()
                }
            }

        private suspend fun shuffleLibrary(source: ShuffleSource.Library) {
            // One request for the sample, no paging; reloading when the queue runs low is a later part
            val items =
                api.libraryApi
                    .getItems(
                        parentId = source.id,
                        includeItemTypes = listOf(BaseItemKind.AUDIO),
                        recursive = true,
                        sortBy = listOf(ItemSortBy.RANDOM),
                        limit = LIBRARY_SAMPLE,
                        fields = DetailItemFields,
                    ).content.items
                    .map { BaseItem(it, false) }
            val rule = musicService.ruleFor(false, false)
            val ordered = smartOrder(items, rule, dislikes.set.value, Instant.now(), Random.Default) { it.data.toLightSong() }
            Timber.i("MusicShuffle: library sample, %d of %d songs", ordered.size, items.size)
            if (ordered.isNotEmpty()) musicService.playQueue(ordered, 0, 0, rule)
        }

        /**
         * Reorder the entries after the current song in smart order
         *
         * The current song and everything before it stay. The song that plays now counts as the previous artist, so
         * it does not open the new order.
         */
        suspend fun shuffleUpcoming() {
            try {
                val (queue, current) = musicService.queueSnapshot()
                val upcoming = queue.drop(current + 1)
                if (upcoming.size < 2) return
                val byId = loadLight((upcoming + queue[current]).distinct()).associateBy { it.id }
                val previous = byId[queue[current]]?.toLightSong()?.artistKey
                val songs =
                    upcoming.map { id ->
                        byId[id]?.toLightSong() ?: LightSong(id, "\u0000$id", null)
                    }
                val ordered = smartOrder(songs, EntryRule.EXEMPT, DislikeSet.EMPTY, Instant.now(), Random.Default, previous) { it }
                musicService.applyUpcomingOrder(ordered.map { it.id })
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "MusicShuffle: failed to reorder the queue")
            }
        }

        private suspend fun loadLight(ids: List<UUID>): List<BaseItemDto> =
            ids.chunked(REMOTE_CHUNK_SIZE).flatMap { chunk ->
                api.libraryApi
                    .getItems(ids = chunk)
                    .content.items
            }

        companion object {
            /** How many songs a library-wide shuffle takes */
            const val LIBRARY_SAMPLE = 200
        }
    }
