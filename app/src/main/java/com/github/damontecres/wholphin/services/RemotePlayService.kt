package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import com.github.damontecres.wholphin.ui.DetailItemFields
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * The songs of a remote play command in server order, where to start and at which position
 */
internal data class RemoteQueue(
    val items: List<BaseItem>,
    val startIndex: Int,
    val startPositionMs: Long,
)

/**
 * The rule for songs a remote command inserts into the running queue
 *
 * A play next of one song is an explicit choice, like a song started on its own, and is never left out.
 */
internal fun ruleForRemoteInsert(
    command: PlayCommand?,
    idCount: Int,
): EntryRule = if (command == PlayCommand.PLAY_NEXT && idCount == 1) EntryRule.EXEMPT else EntryRule.FULL

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
 * How many ids one request fetches
 *
 * A command may list a whole library. Fetching all of it before playing takes minutes (about 0.45 s per
 * request of 100 on the author's server), so playback starts with the first chunk and the rest follows.
 */
internal const val REMOTE_CHUNK_SIZE = 100

/**
 * The ids of a play command in the order they are loaded: [fromStart] begins at the start index,
 * [beforeStart] holds everything in front of it
 */
internal data class RemoteLoadPlan(
    val fromStart: List<List<UUID>>,
    val beforeStart: List<List<UUID>>,
)

/**
 * Splits the ids of a command into chunks of [REMOTE_CHUNK_SIZE], starting at [startIndex] (0 if null or out of range)
 */
internal fun planRemoteLoad(
    itemIds: List<UUID>,
    startIndex: Int?,
): RemoteLoadPlan {
    val start = startIndex?.takeIf { it in itemIds.indices } ?: 0
    return RemoteLoadPlan(
        fromStart = itemIds.drop(start).chunked(REMOTE_CHUNK_SIZE),
        beforeStart = itemIds.take(start).chunked(REMOTE_CHUNK_SIZE),
    )
}

/**
 * How a play command that lists the reported window of the queue relates to the actual queue
 */
internal sealed interface WindowMatch {
    /** The command lists the window at [offset] of the queue unchanged: jump within the queue */
    data class Jump(
        val offset: Int,
    ) : WindowMatch

    /**
     * The command lists the window at [offset] with one entry moved from [from] to [to]
     * (relative to the window, same meaning as for `Player.moveMediaItem`)
     */
    data class Move(
        val offset: Int,
        val from: Int,
        val to: Int,
    ) : WindowMatch
}

/**
 * Checks whether [ids] is the part of [queue] that was reported to the remote (see [reportedQueueRange]),
 * possibly with one entry moved, so the command is a click or a drag in the remote's queue view
 *
 * The window may have shifted by a song or two since the remote loaded it, so every offset is tried,
 * nearest to where the window currently sits first. Returns null if [ids] is anything else, such as a new album.
 *
 * Entries removed from the queue since the remote loaded its window (disliked songs are removed) are missing
 * from [queue]. They are ignored, and an offset or index of the match then refers to the ids that are still
 * queued (see [presentStartIndex]). With such gaps the window must still be the full report window or all that
 * is left of the queue, so a list that merely overlaps the queue is not taken for a click.
 */
internal fun matchQueueWindow(
    queue: List<UUID>,
    ids: List<UUID>,
    currentIndex: Int,
): WindowMatch? {
    val inQueue = queue.toHashSet()
    val present = ids.filter { it in inQueue }
    val length = present.size
    val complete =
        if (length == ids.size) {
            length == minOf(MAX_REPORTED_QUEUE, queue.size)
        } else {
            ids.size == MAX_REPORTED_QUEUE || length == queue.size
        }
    if (length < 2 || !complete || length > queue.size) return null
    val preferred = currentIndex - REPORTED_BEFORE_CURRENT
    return (0..queue.size - length)
        .sortedBy { abs(it - preferred) }
        .firstNotNullOfOrNull { matchWindowAt(queue, present, it) }
}

/**
 * The index within the ids still queued for a command that starts at [startIndex] of [ids]: the number of ids
 * in front of it that are still queued, so a start entry that was removed gives the next present one
 */
internal fun presentStartIndex(
    queue: List<UUID>,
    ids: List<UUID>,
    startIndex: Int,
): Int {
    val inQueue = queue.toHashSet()
    return ids.take(startIndex).count { it in inQueue }
}

private fun matchWindowAt(
    queue: List<UUID>,
    ids: List<UUID>,
    offset: Int,
): WindowMatch? {
    val length = ids.size
    val first = (0 until length).firstOrNull { queue[offset + it] != ids[it] } ?: return WindowMatch.Jump(offset)
    val last = (length - 1 downTo first).first { queue[offset + it] != ids[it] }
    val forward =
        ids[last] == queue[offset + first] &&
            (first until last).all { ids[it] == queue[offset + it + 1] }
    if (forward) return WindowMatch.Move(offset, first, last)
    val backward =
        ids[first] == queue[offset + last] &&
            (first + 1..last).all { ids[it] == queue[offset + it - 1] }
    return if (backward) WindowMatch.Move(offset, last, first) else null
}

/**
 * Plays music that another client sends to this session as a [PlayRequest]
 *
 * Playback starts with the first chunk of songs, a background job loads the rest of a large command.
 */
@Singleton
class RemotePlayService
    @Inject
    constructor(
        private val api: ApiClient,
        private val musicService: MusicService,
        private val navigationManager: NavigationManager,
        @param:DefaultCoroutineScope private val scope: CoroutineScope,
        private val loadState: QueueLoadState = QueueLoadState(),
    ) {
        private val mutex = Mutex()
        private val loaders = CopyOnWriteArrayList<Job>()

        /**
         * Handle the command in the application scope, one command at a time
         */
        fun handle(request: PlayRequest) {
            scope.launch {
                mutex.withLock {
                    try {
                        execute(request)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.e(ex, "RemotePlay: failed to handle %s", request.playCommand)
                    }
                }
            }
        }

        internal suspend fun fetchItems(ids: List<UUID>): List<BaseItem> =
            ids.chunked(REMOTE_CHUNK_SIZE).flatMap { chunk ->
                api.libraryApi
                    .getItems(ids = chunk, fields = DetailItemFields)
                    .content.items
                    .map { BaseItem(it, false) }
            }

        private suspend fun loadChunk(
            ids: List<UUID>,
            startIndex: Int? = null,
            startPositionTicks: Long? = null,
        ): RemoteQueue = buildRemoteQueue(ids, fetchItems(ids), startIndex, startPositionTicks)

        private class FirstBatch(
            val chunkIndex: Int,
            val queue: RemoteQueue,
        )

        /**
         * Fetches chunks in order until one has songs. [startIndex] and [startPositionTicks] only apply to the first chunk.
         */
        private suspend fun firstBatch(
            chunks: List<List<UUID>>,
            startIndex: Int?,
            startPositionTicks: Long?,
        ): FirstBatch? {
            chunks.forEachIndexed { index, chunk ->
                val queue =
                    if (index == 0) {
                        loadChunk(chunk, startIndex, startPositionTicks)
                    } else {
                        loadChunk(chunk)
                    }
                if (queue.items.isNotEmpty()) return FirstBatch(index, queue)
            }
            return null
        }

        internal suspend fun execute(request: PlayRequest) {
            // Autoplay waits from the first chunk on, not only while the background job runs
            loadState.begin()
            try {
                executeCommand(request)
            } finally {
                loadState.end()
            }
        }

        private suspend fun executeCommand(request: PlayRequest) {
            val ids = request.itemIds.orEmpty()
            val command = request.playCommand
            val queueSize = musicService.state.value.queueSize
            val insert =
                queueSize > 0 && (command == PlayCommand.PLAY_NEXT || command == PlayCommand.PLAY_LAST)
            if (insert) {
                insert(request, ids, command == PlayCommand.PLAY_NEXT)
            } else {
                playNow(request, ids)
            }
        }

        private suspend fun insert(
            request: PlayRequest,
            ids: List<UUID>,
            next: Boolean,
        ) {
            val chunks = planRemoteLoad(ids, null).fromStart
            val batch = firstBatch(chunks, null, null)
            logCommand(request, ids, batch?.queue)
            if (batch == null) {
                Timber.i("RemotePlay: nothing playable, ignored")
                return
            }
            val rule =
                ruleForRemoteInsert(request.playCommand, ids.size)
                    .takeIf { it == EntryRule.EXEMPT }
                    ?: musicService.ruleFor(false, ids.size == 1, ids)
            val keys =
                musicService.insertIntoQueue(
                    batch.queue.items,
                    if (next) musicService.state.value.currentIndex + 1 else null,
                    rule,
                )
            startLoader(ids.size, keys.size, chunks.drop(batch.chunkIndex + 1), emptyList(), keys.firstOrNull(), keys.lastOrNull(), rule)
        }

        private suspend fun playNow(
            request: PlayRequest,
            ids: List<UUID>,
        ) {
            if (followsQueueWindow(request, ids)) return
            val plan = planRemoteLoad(ids, request.startIndex)
            var batch = firstBatch(plan.fromStart, 0, request.startPositionTicks)
            var after = plan.fromStart.drop((batch?.chunkIndex ?: 0) + 1)
            var before = plan.beforeStart
            if (batch == null) {
                // Nothing playable from the start index on, play what is in front of it
                batch = firstBatch(plan.beforeStart, 0, null)
                after = plan.beforeStart.drop((batch?.chunkIndex ?: 0) + 1)
                before = emptyList()
            }
            logCommand(request, ids, batch?.queue)
            if (batch == null) {
                Timber.i("RemotePlay: nothing playable, ignored")
                return
            }
            val queue = batch.queue
            cancelLoaders()
            val rule = musicService.ruleFor(false, ids.size == 1, ids)
            val keys = musicService.playQueue(queue.items, queue.startIndex, queue.startPositionMs, rule)
            showNowPlaying()
            startLoader(ids.size, keys.size, after, before, keys.firstOrNull(), keys.lastOrNull(), rule)
        }

        /**
         * Replace the queue with the songs [ids] in exactly this order: playback starts with the first chunk,
         * a background job loads the rest. For an order the app built itself (shuffle).
         */
        internal suspend fun playIds(
            ids: List<UUID>,
            rule: EntryRule,
        ) = mutex.withLock {
            loadState.begin()
            try {
                playIdsLocked(ids, rule)
            } finally {
                loadState.end()
            }
        }

        private suspend fun playIdsLocked(
            ids: List<UUID>,
            rule: EntryRule,
        ) {
            val chunks = planRemoteLoad(ids, 0).fromStart
            val batch = firstBatch(chunks, 0, null)
            if (batch == null) {
                Timber.i("RemotePlay: nothing playable in %d ids, ignored", ids.size)
                return
            }
            cancelLoaders()
            val keys = musicService.playQueue(batch.queue.items, 0, 0, rule)
            startLoader(ids.size, keys.size, chunks.drop(batch.chunkIndex + 1), emptyList(), keys.firstOrNull(), keys.lastOrNull(), rule)
        }

        private suspend fun showNowPlaying() =
            withContext(WholphinDispatchers.Main) {
                if (navigationManager.backStack.lastOrNull() !is Destination.NowPlaying) {
                    navigationManager.navigateTo(Destination.NowPlaying)
                }
            }

        /**
         * A remote shows only a window of a large queue. Clicking a song in it sends the window's ids again,
         * dragging a song sends them with one entry moved. Replacing the queue with that window would drop
         * the rest of it, so jump or move within the queue instead.
         *
         * @return true if the command was handled that way
         */
        private suspend fun followsQueueWindow(
            request: PlayRequest,
            ids: List<UUID>,
        ): Boolean {
            if (musicService.state.value.queueSize == 0) return false
            val (queue, current) = musicService.queueSnapshot()
            val match =
                withContext(WholphinDispatchers.Default) { matchQueueWindow(queue, ids, current) }
                    ?: return false
            when (match) {
                is WindowMatch.Jump -> {
                    val start = request.startIndex?.takeIf { it in ids.indices } ?: 0
                    val index = match.offset + presentStartIndex(queue, ids, start)
                    Timber.i("RemotePlay: jump to %d", index)
                    musicService.playIndex(index)
                }

                is WindowMatch.Move -> {
                    Timber.i("RemotePlay: move %d -> %d", match.offset + match.from, match.offset + match.to)
                    musicService.moveQueue(match.offset + match.from, match.offset + match.to)
                }
            }
            showNowPlaying()
            return true
        }

        private fun logCommand(
            request: PlayRequest,
            ids: List<UUID>,
            queue: RemoteQueue?,
        ) = Timber.i(
            "RemotePlay: command=%s ids=%d first=%d startIndex=%d positionMs=%d",
            request.playCommand,
            ids.size,
            queue?.items?.size ?: 0,
            queue?.startIndex ?: 0,
            queue?.startPositionMs ?: 0,
        )

        private suspend fun cancelLoaders() {
            val running = loaders.toList()
            running.forEach { it.cancelAndJoin() }
            loaders.removeAll(running)
        }

        /**
         * Loads the remaining chunks of a command in the background
         *
         * [after] chunks go behind [lastKey], one after the other; [before] chunks go in front of [firstKey],
         * all in front of the same entry, which keeps their order. Stops when that entry is gone
         * (the queue was replaced or stopped) and keeps what is loaded when a request fails.
         */
        private fun startLoader(
            total: Int,
            firstCount: Int,
            after: List<List<UUID>>,
            before: List<List<UUID>>,
            firstKey: Long?,
            lastKey: Long?,
            rule: EntryRule,
        ) {
            if (firstKey == null || lastKey == null || (after.isEmpty() && before.isEmpty())) {
                Timber.i("RemotePlay: loaded %d songs from %d ids", firstCount, total)
                return
            }
            val job =
                scope.launch {
                    var loaded = firstCount
                    var stopped = false
                    try {
                        var last: Long = lastKey
                        for (chunk in after) {
                            val songs = loadChunk(chunk).items
                            if (songs.isEmpty()) continue
                            val keys = musicService.insertNextTo(last, false, songs, rule)
                            if (keys == null) {
                                stopped = true
                                break
                            }
                            loaded += keys.size
                            last = keys.last()
                        }
                        if (!stopped) {
                            for (chunk in before) {
                                val songs = loadChunk(chunk).items
                                if (songs.isEmpty()) continue
                                val keys = musicService.insertNextTo(firstKey, true, songs, rule)
                                if (keys == null) {
                                    stopped = true
                                    break
                                }
                                loaded += keys.size
                            }
                        }
                        if (stopped) {
                            Timber.i("RemotePlay: queue changed, stopped loading after %d songs", loaded)
                        } else {
                            Timber.i("RemotePlay: loaded %d songs from %d ids", loaded, total)
                        }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.e(ex, "RemotePlay: failed loading, kept %d songs", loaded)
                    }
                }
            loaders.add(job)
            loadState.begin()
            job.invokeOnCompletion {
                loaders.remove(job)
                loadState.end()
            }
        }
    }
