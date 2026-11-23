package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import com.github.damontecres.wholphin.ui.DetailItemFields
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlayRequest
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The songs of a remote play command in server order, where to start and at which position
 */
internal data class RemoteQueue(
    val items: List<BaseItem>,
    val startIndex: Int,
    val startPositionMs: Long,
)

/**
 * Maps the ids of a play command and the fetched items to a queue of songs
 *
 * Order follows [itemIds]. Missing and non-audio items are dropped. [startIndex] refers to [itemIds];
 * if that item was dropped, the next remaining song is used and [startPositionTicks] is ignored.
 */
internal fun buildRemoteQueue(
    itemIds: List<UUID>,
    fetched: Collection<BaseItem>,
    startIndex: Int?,
    startPositionTicks: Long?,
): RemoteQueue {
    val byId = fetched.associateBy { it.id }
    val songs = itemIds.map { id -> byId[id]?.takeIf { it.type == BaseItemKind.AUDIO } }
    val items = songs.filterNotNull()
    if (items.isEmpty()) return RemoteQueue(emptyList(), 0, 0)

    val requested = startIndex?.takeIf { it in itemIds.indices } ?: 0
    val survived = songs[requested] != null
    val startSongIndex =
        (requested until songs.size)
            .firstOrNull { songs[it] != null }
            ?.let { songs.subList(0, it).count { song -> song != null } }
            ?: 0
    val positionMs = if (survived) (startPositionTicks ?: 0L) / 10_000 else 0L
    return RemoteQueue(items, startSongIndex, positionMs.coerceAtLeast(0L))
}

/**
 * Plays music that another client sends to this session as a [PlayRequest]
 */
@Singleton
class RemotePlayService
    @Inject
    constructor(
        private val api: ApiClient,
        private val musicService: MusicService,
        private val navigationManager: NavigationManager,
        @param:DefaultCoroutineScope private val scope: CoroutineScope,
    ) {
        private val mutex = Mutex()

        /**
         * Handle the command in the application scope, one command at a time
         */
        fun handle(request: PlayRequest) {
            scope.launch {
                mutex.withLock {
                    try {
                        val ids = request.itemIds.orEmpty()
                        execute(request, fetchItems(ids))
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.e(ex, "RemotePlay: failed to handle %s", request.playCommand)
                    }
                }
            }
        }

        internal suspend fun fetchItems(ids: List<UUID>): List<BaseItem> =
            ids.chunked(100).flatMap { chunk ->
                api.libraryApi
                    .getItems(ids = chunk, fields = DetailItemFields)
                    .content.items
                    .map { BaseItem(it, false) }
            }

        internal suspend fun execute(
            request: PlayRequest,
            fetched: List<BaseItem>,
        ) {
            val ids = request.itemIds.orEmpty()
            val queue = buildRemoteQueue(ids, fetched, request.startIndex, request.startPositionTicks)
            Timber.i(
                "RemotePlay: command=%s ids=%d songs=%d startIndex=%d positionMs=%d",
                request.playCommand,
                ids.size,
                queue.items.size,
                queue.startIndex,
                queue.startPositionMs,
            )
            if (queue.items.isEmpty()) {
                Timber.i("RemotePlay: nothing playable, ignored")
                return
            }
            val state = musicService.state.value
            when {
                request.playCommand == PlayCommand.PLAY_NEXT && state.queueSize > 0 -> {
                    musicService.insertIntoQueue(queue.items, state.currentIndex + 1)
                }

                request.playCommand == PlayCommand.PLAY_LAST && state.queueSize > 0 -> {
                    musicService.insertIntoQueue(queue.items, null)
                }

                else -> {
                    musicService.playQueue(queue.items, queue.startIndex, queue.startPositionMs)
                    withContext(WholphinDispatchers.Main) {
                        if (navigationManager.backStack.lastOrNull() !is Destination.NowPlaying) {
                            navigationManager.navigateTo(Destination.NowPlaying)
                        }
                    }
                }
            }
        }
    }
