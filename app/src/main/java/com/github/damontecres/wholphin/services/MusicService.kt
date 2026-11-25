package com.github.damontecres.wholphin.services

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.AudioItem
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.enabled
import com.github.damontecres.wholphin.preferences.get
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import com.github.damontecres.wholphin.ui.DetailItemFields
import com.github.damontecres.wholphin.ui.gt
import com.github.damontecres.wholphin.ui.main.settings.MoveDirection
import com.github.damontecres.wholphin.ui.onMain
import com.github.damontecres.wholphin.ui.seekBack
import com.github.damontecres.wholphin.ui.seekForward
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.util.BlockingList
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.profile.Codec
import com.github.damontecres.wholphin.util.profile.supportedAudioCodecs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.instantMixApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.playlistApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.PlaystateMessage
import org.jellyfin.sdk.model.api.QueueItem
import org.jellyfin.sdk.model.api.UserDataChangeInfo
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Manage the global state for playing music
 *
 * Has functions for modifying the queue
 */
@OptIn(UnstableApi::class)
@Singleton
class MusicService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:DefaultCoroutineScope private val defaultScope: CoroutineScope,
        private val api: ApiClient,
        private val playerFactory: PlayerFactory,
        private val serverRepository: ServerRepository,
        private val imageUrlService: ImageUrlService,
        private val userPreferencesService: UserPreferencesService,
        private val dislikes: DislikeService,
        private val queueLoads: QueueLoadState,
    ) {
        private val _state = MutableStateFlow(MusicServiceState.EMPTY)
        val state: StateFlow<MusicServiceState> = _state

        private val audioFormats by lazy { listOf(*supportedAudioCodecs) }

        val player: Player by lazy {
            val disableAudioOffload =
                runBlocking {
                    userPreferencesService
                        .getCurrent()
                        .appPreferences.experimentalPreferences
                        .get { disableAudioOffload } ?: false
                }
            playerFactory
                .createAudioPlayer(disableAudioOffload = disableAudioOffload)
                .also {
                    it.addListener(MusicPlayerListener(it, _state))
                }
        }

        private val mutex = Mutex()
        var mediaSession: MediaSession? = null
            private set
        private var reporter: MusicPlaybackReporter? = null
        private var websocketJob: Job? = null
        private var dislikeJobs: List<Job> = emptyList()
        private var autoplayJob: Job? = null
        private val autoplayMutex = Mutex()
        private val autoplaySession = AutoplaySession()

        private suspend fun preferAc3Surround() =
            userPreferencesService
                .getCurrent()
                .appPreferences.experimentalPreferences
                .enabled { preferAc3Surround }

        /**
         * Start music playback
         *
         * Sets up the media session, activity tracking, and actual playback
         */
        suspend fun start() {
            if (mediaSession == null) {
                mutex.withLock {
                    if (mediaSession == null) {
                        Timber.i("Starting music MediaSession")
                        mediaSession =
                            MediaSession
                                .Builder(context, player)
                                .setCallback(TrustAllControllersCallback)
                                .build()
                        reporter =
                            MusicPlaybackReporter(api, player, defaultScope) {
                                // Runs on the main thread, the only one that may read the player
                                val itemId = (player.currentMediaItem?.localConfiguration?.tag as? AudioItem)?.id
                                if (itemId == null || !isPlayingSong(player.playbackState)) {
                                    null
                                } else {
                                    val currentIndex = player.currentMediaItemIndex
                                    val range = reportedQueueRange(player.mediaItemCount, currentIndex)
                                    val (queue, playlistItemId) =
                                        queueReport(
                                            range.map {
                                                player.getMediaItemAt(it).localConfiguration?.tag as? AudioItem
                                            },
                                            currentIndex - range.first,
                                        )
                                    CurrentSong(
                                        itemId = itemId,
                                        positionMs = player.currentPosition,
                                        isPaused = !player.isPlaying,
                                        queue = ReportedQueue(playlistItemId, queue),
                                    )
                                }
                            }.also { r -> onMain { r.start() } }
                        websocketJob = subscribe()
                        dislikeJobs = startDislikeJobs()
                        autoplayJob = startAutoplayJob()
                    }
                }
            }
            onMain {
                player.prepare()
                player.play()
            }
        }

        /**
         * Stop music playback
         */
        suspend fun stop() {
            mutex.withLock {
                Timber.i("Stopping music")
                if (mediaSession == null) {
                    Timber.w("Stopping but no MediaSession")
                }
                mediaSession?.release()
                mediaSession = null
                onMain {
                    websocketJob?.cancel()
                    websocketJob = null
                    dislikeJobs.forEach { it.cancel() }
                    dislikeJobs = emptyList()
                    autoplayJob?.cancel()
                    autoplayJob = null
                    autoplaySession.clear()
                    reporter?.release()
                    reporter = null
                    player.stop()
                    player.setMediaItems(emptyList())
                }
                _state.update {
                    MusicServiceState.EMPTY
                }
            }
        }

        /**
         * Fetches instant mix items, replaces the queue, and begins playback
         */
        suspend fun startInstantMix(itemId: UUID) =
            loading {
                val items =
                    api.instantMixApi
                        .getInstantMixFromItem(
                            userId = serverRepository.currentUser?.id,
                            itemId = itemId,
                            limit = 200,
                            fields = DetailItemFields,
                        ).content.items
                        .map { BaseItem(it, false) }
                // The server already filters the mix; the client filter is a cheap safety net
                dislikes.refresh()
                setQueue(items, EntryRule.FULL)
            }

        /**
         * Replace the queue with the given list and starting playing the song as startIndex as soon as its ready
         *
         * Fetches each item in a blocking way and adds to the queue
         */
        suspend fun setQueue(
            items: BlockingList<BaseItem?>,
            startIndex: Int,
            rule: EntryRule = EntryRule.FULL,
        ) = withContext(WholphinDispatchers.IO) {
            Timber.d("setQueue: %s items, startIndex=%s, rule=%s", items.size, startIndex, rule)
            autoplaySession.queueStarted()
            withContext(WholphinDispatchers.Main) {
                player.setMediaItems(emptyList())
                player.shuffleModeEnabled = false
            }
            start()
            addAllToQueue(items, startIndex, rule)
        }

        /**
         * Replace the queue with the given items
         */
        suspend fun setQueue(
            items: List<BaseItem>,
            rule: EntryRule = EntryRule.FULL,
        ) {
            Timber.d("setQueue: %s items, rule=%s", items.size, rule)
            autoplaySession.queueStarted()
            val mediaItems = convertSongs(items, rule)
            withContext(WholphinDispatchers.Main) {
                player.setMediaItems(mediaItems)
                player.shuffleModeEnabled = false
                updateQueueSize()
                start()
            }
        }

        /**
         * Replace the queue with the given songs and start playing at [startIndex] and [startPositionMs]
         *
         * [autoplaySource] is where autoplay takes more songs from; any other queue start means Instant Mix.
         *
         * @return the [AudioItem.key] of each queue entry created, in order
         */
        suspend fun playQueue(
            items: List<BaseItem>,
            startIndex: Int,
            startPositionMs: Long,
            rule: EntryRule = EntryRule.FULL,
            autoplaySource: AutoplaySource = AutoplaySource.InstantMix,
        ): List<Long> {
            Timber.d("playQueue: %s items, startIndex=%s, positionMs=%s, rule=%s", items.size, startIndex, startPositionMs, rule)
            dislikes.observe(items)
            val (songs, start) = items.queueableWithStart(rule, dislikes.set.value, startIndex) { it.id }
            // A start song that was left out takes its position with it
            val position = if (songs.getOrNull(start)?.id == items.getOrNull(startIndex)?.id) startPositionMs else 0L
            val mediaItems = convertSongs(songs, rule)
            if (mediaItems.isEmpty()) return emptyList()
            autoplaySession.queueStarted(autoplaySource)
            withContext(WholphinDispatchers.Main) {
                player.setMediaItems(mediaItems, start, position)
                player.shuffleModeEnabled = false
                updateQueueSize()
                start()
            }
            return mediaItems.map(::entryKey)
        }

        /**
         * Insert the given songs at [index] of the queue, or at the end if [index] is null; does not start playback
         *
         * @return the [AudioItem.key] of each queue entry created, in order
         */
        suspend fun insertIntoQueue(
            items: List<BaseItem>,
            index: Int?,
            rule: EntryRule = EntryRule.FULL,
        ): List<Long> {
            Timber.d("insertIntoQueue: %s items, index=%s", items.size, index)
            val mediaItems = convertSongs(items, rule)
            withContext(WholphinDispatchers.Main) {
                if (index != null) {
                    player.addMediaItems(index, mediaItems)
                } else {
                    player.addMediaItems(mediaItems)
                }
                updateQueueSize()
            }
            return mediaItems.map(::entryKey)
        }

        /**
         * Insert the given songs right after the queue entry with [anchorKey], or right before it if [before]
         *
         * @return the [AudioItem.key] of each queue entry created, in order, or null (nothing inserted)
         * if no entry has the key any more, because the queue was replaced, stopped, or the entry removed
         */
        suspend fun insertNextTo(
            anchorKey: Long,
            before: Boolean,
            items: List<BaseItem>,
            rule: EntryRule = EntryRule.FULL,
        ): List<Long>? {
            val mediaItems = convertSongs(items, rule)
            return withContext(WholphinDispatchers.Main) {
                val anchor =
                    (0 until player.mediaItemCount).firstOrNull {
                        (player.getMediaItemAt(it).localConfiguration?.tag as? AudioItem)?.key == anchorKey
                    }
                if (anchor == null) {
                    null
                } else {
                    player.addMediaItems(if (before) anchor else anchor + 1, mediaItems)
                    updateQueueSize()
                    mediaItems.map(::entryKey)
                }
            }
        }

        /**
         * The song ids of all queue entries in order and the index of the current entry
         */
        suspend fun queueSnapshot(): Pair<List<UUID>, Int> =
            onMain {
                List(player.mediaItemCount) {
                    (player.getMediaItemAt(it).localConfiguration?.tag as AudioItem).id
                } to player.currentMediaItemIndex
            }

        /**
         * The songs of [items] that may be queued under [rule], as media items that carry the rule
         */
        private suspend fun convertSongs(
            items: List<BaseItem>,
            rule: EntryRule,
        ): List<MediaItem> {
            val preferAc3Surround = preferAc3Surround()
            val songs = items.filter { it.type == BaseItemKind.AUDIO }
            dislikes.observe(songs)
            return songs
                .queueable(rule, dislikes.set.value) { it.id }
                .map { convert(it, preferAc3Surround, rule) }
        }

        /**
         * The rule for a queue that is started now, after fetching the disliked songs again
         *
         * @param explicitContainer an album or artist started on purpose
         * @param singleSong a song started on its own
         * @param listIds the ids of every song of the list to play, if known; a list of only excluded songs is exempt
         */
        suspend fun ruleFor(
            explicitContainer: Boolean,
            singleSong: Boolean,
            listIds: List<UUID>? = null,
        ): EntryRule {
            if (singleSong) return EntryRule.EXEMPT
            dislikes.refresh()
            return ruleForStart(explicitContainer, false, listIds, dislikes.set.value)
        }

        /**
         * The ids of all songs of a playlist in one request
         */
        suspend fun playlistSongIds(playlistId: UUID): List<UUID>? =
            try {
                api.playlistApi
                    .getPlaylistItems(
                        playlistId = playlistId,
                        userId = serverRepository.currentUser?.id,
                    ).content.items
                    .map { it.id }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Error fetching the songs of playlist %s", playlistId)
                null
            }

        /**
         * Fetches the disliked songs every few minutes and removes upcoming queue entries that they now exclude
         */
        private fun startDislikeJobs(): List<Job> =
            listOf(
                defaultScope.launch {
                    while (isActive) {
                        dislikes.refresh()
                        delay(DislikeService.REFRESH_INTERVAL_MS)
                    }
                },
                defaultScope.launch {
                    dislikes.set.collect { pruneQueue() }
                },
                api.webSocket
                    .subscribe<UserDataChangedMessage>()
                    .onEach { message -> message.data?.let { onUserDataChanged(it) } }
                    .catch { ex -> Timber.e(ex, "Error in user data websocket subscription") }
                    .launchIn(defaultScope),
            )

        /**
         * A rating or favorite changed on the server, maybe in another client: update the set at once and
         * skip the current song if it is now disliked
         */
        private suspend fun onUserDataChanged(info: UserDataChangeInfo) {
            if (info.userId != serverRepository.currentUser?.id) return
            val changes = info.userDataList.map { RatingChange(it.itemId, it.likes) }
            val marks = ratingMarks(changes, dislikes.set.value)
            if (marks.isEmpty()) return
            marks.forEach { (id, disliked) -> dislikes.mark(id, disliked) }
            dislikes.refresh()
            val current = onMain { player.currentMediaItem?.localConfiguration?.tag as? AudioItem }
            val skip =
                skipCurrentAfterRating(
                    changes,
                    current?.id,
                    current?.rule ?: EntryRule.EXEMPT,
                    dislikes.set.value,
                    dislikes.recentOwnRatings(),
                )
            marks.forEach { (id, _) ->
                Timber.i(
                    "DislikeService: rating changed elsewhere for %s, likes=%s, skipped=%s",
                    id,
                    changes.firstOrNull { it.itemId == id }?.likes,
                    skip && id == current?.id,
                )
            }
            if (skip) skipCurrent()
        }

        /**
         * Remove the upcoming entries that the disliked songs exclude, never the current entry or one before it
         */
        suspend fun pruneQueue() {
            val removed =
                onMain {
                    val entries =
                        List(player.mediaItemCount) {
                            val audio = player.getMediaItemAt(it).localConfiguration?.tag as? AudioItem
                            QueueEntry(audio?.id ?: UUID(0, 0), audio?.rule ?: EntryRule.EXEMPT)
                        }
                    val stale = staleUpcoming(entries, player.currentMediaItemIndex, dislikes.set.value)
                    stale.asReversed().forEach { player.removeMediaItem(it) }
                    stale.size
                }
            if (removed > 0) {
                Timber.i("MusicService: removed %d disliked songs from the queue", removed)
                updateQueueSize()
            }
        }

        /**
         * Put the entries after the current one in the order of [order] (song ids), see [planReorder]
         *
         * The current entry and the ones before it stay. The queue is read when this runs, so entries that
         * arrived or left since [order] was computed are handled.
         */
        suspend fun applyUpcomingOrder(order: List<UUID>) {
            val moved =
                onMain {
                    val queue =
                        List(player.mediaItemCount) {
                            (player.getMediaItemAt(it).localConfiguration?.tag as? AudioItem)?.id ?: UUID(0, 0)
                        }
                    val current = player.currentMediaItemIndex
                    val plan = planReorder(queue, current, order)
                    if (plan == null) {
                        0
                    } else {
                        val items = plan.map { player.getMediaItemAt(it) }
                        player.replaceMediaItems(current + 1, player.mediaItemCount, items)
                        items.size
                    }
                }
            if (moved > 0) {
                Timber.i("MusicService: reordered %d upcoming songs", moved)
                updateQueueSize()
            }
        }

        /**
         * Go on with the next song, or pause if the current one is the last
         *
         * With autoplay on and nothing following, it waits for a load first.
         */
        suspend fun skipCurrent() {
            if (!onMain { player.hasNextMediaItem() } && autoplayEnabled()) {
                autoplayMutex.withLock { if (!onMain { player.hasNextMediaItem() }) loadMore() }
            }
            onMain {
                if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.pause()
            }
        }

        private suspend fun autoplayEnabled() =
            userPreferencesService
                .getCurrent()
                .appPreferences.musicPreferences.autoplay

        private data class AutoplayTrigger(
            val index: Int,
            val version: Long,
            val size: Int,
            val pagerLoading: Boolean,
        )

        /**
         * Looks at the queue on every song change, queue change and end of a background load
         */
        private fun startAutoplayJob(): Job =
            defaultScope.launch {
                combine(
                    state
                        .map { AutoplayTrigger(it.currentIndex, it.queueVersion, it.queueSize, it.loadingState == LoadingState.Loading) }
                        .distinctUntilChanged(),
                    queueLoads.running,
                ) { trigger, loading -> trigger.pagerLoading || loading }.collect { busy ->
                    try {
                        checkAutoplay(busy)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Autoplay: check failed")
                    }
                }
            }

        private suspend fun checkAutoplay(loading: Boolean) {
            val (size, index, repeatOff) =
                onMain {
                    player.currentMediaItem
                        ?.mediaId
                        ?.toUUIDOrNull()
                        ?.let { autoplaySession.markPlayed(it) }
                    Triple(player.mediaItemCount, player.currentMediaItemIndex, player.repeatMode == Player.REPEAT_MODE_OFF)
                }
            val enabled = autoplayEnabled()
            if (!shouldLoadMore(enabled, repeatOff, size, index, loading || autoplayMutex.isLocked)) return
            if (autoplayMutex.tryLock()) {
                try {
                    loadMore()
                } finally {
                    autoplayMutex.unlock()
                }
            }
        }

        /**
         * Appends the next songs behind the last entry, see [AutoplaySource]. The caller holds [autoplayMutex].
         */
        private suspend fun loadMore() {
            val (queue, lastEntry) =
                onMain {
                    val count = player.mediaItemCount
                    List(count) { (player.getMediaItemAt(it).localConfiguration?.tag as? AudioItem)?.id ?: UUID(0, 0) } to
                        (if (count > 0) player.getMediaItemAt(count - 1).localConfiguration?.tag as? AudioItem else null)
                }
            if (lastEntry == null) return
            val source = autoplaySession.source
            val taken = autoplaySession.taken(queue)
            val set = dislikes.set.value
            val userId = serverRepository.currentUser?.id
            var seed: UUID? = null
            var got = 0
            var songs = emptyList<BaseItem>()
            when (source) {
                is AutoplaySource.InstantMix -> {
                    for (candidate in autoplaySeeds(queue)) {
                        val fetched =
                            api.instantMixApi
                                .getInstantMixFromItem(
                                    userId = userId,
                                    itemId = candidate,
                                    limit = AUTOPLAY_BATCH,
                                    fields = DetailItemFields,
                                ).content.items
                                .map { BaseItem(it, false) }
                        seed = candidate
                        got = fetched.size
                        val seedLibrary = libraryOf(candidate, candidate, userId)
                        val libraries = fetched.associate { it.id to libraryOf(it.data.albumId ?: it.id, it.id, userId) }
                        val sameLibrary = keepSeedLibrary(fetched, seedLibrary) { libraries[it.id] }
                        songs = filterInstantMix(sameLibrary, candidate, taken, set, lastEntry.artistKey) { it.data.toLightSong() }
                        if (songs.isNotEmpty()) break
                    }
                }

                is AutoplaySource.LibraryRandom -> {
                    val fetched =
                        api.libraryApi
                            .getItems(
                                parentId = source.libraryId,
                                includeItemTypes = listOf(BaseItemKind.AUDIO),
                                recursive = true,
                                sortBy = listOf(ItemSortBy.RANDOM),
                                limit = MusicShuffleService.LIBRARY_SAMPLE,
                                fields = DetailItemFields,
                            ).content.items
                            .map { BaseItem(it, false) }
                    seed = source.libraryId
                    got = fetched.size
                    songs =
                        filterLibraryBatch(
                            fetched,
                            taken,
                            set,
                            lastEntry.artistKey,
                            Instant.now(),
                            Random.Default,
                        ) { it.data.toLightSong() }
                }
            }
            val keys = if (songs.isEmpty()) emptyList() else insertNextTo(lastEntry.key, false, songs, EntryRule.FULL).orEmpty()
            Timber.i("Autoplay: source=%s seed=%s got=%d appended=%d", source::class.simpleName, seed, got, keys.size)
            if (keys.isNotEmpty()) trimPlayed()
        }

        /** Library (collection folder) per album or song, for the app's run */
        private val libraryByKey = ConcurrentHashMap<UUID, UUID>()

        /**
         * The library that holds [itemId], looked up once per [key] (the album, so a mix costs one request per new
         * album). Null when the server does not say.
         */
        private suspend fun libraryOf(
            key: UUID,
            itemId: UUID,
            userId: UUID?,
        ): UUID? {
            libraryByKey[key]?.let { return it }
            return try {
                api.libraryApi
                    .getAncestors(itemId = itemId, userId = userId)
                    .content
                    .firstOrNull { it.type == BaseItemKind.COLLECTION_FOLDER }
                    ?.id
                    ?.also { libraryByKey[key] = it }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Autoplay: no library for %s", itemId)
                null
            }
        }

        /**
         * Drops the entries far in front of the current one, so a queue that grows for hours stays small
         */
        private suspend fun trimPlayed() {
            val trimmed =
                onMain {
                    val count = playedToTrim(player.currentMediaItemIndex)
                    if (count > 0) player.removeMediaItems(0, count)
                    count
                }
            if (trimmed > 0) updateQueueSize()
        }

        private fun entryKey(item: MediaItem) = (item.localConfiguration?.tag as AudioItem).key

        /**
         * Add an item to the specified index of the queue. If no index specified, it will be added to the end.
         */
        suspend fun addToQueue(
            item: BaseItem,
            index: Int? = null,
        ) {
            if (item.type == BaseItemKind.AUDIO) {
                // A single song added on purpose plays even if disliked, like a song started on its own
                val mediaItem = convertSongs(listOf(item), EntryRule.EXEMPT).firstOrNull() ?: return
                withContext(WholphinDispatchers.Main) {
                    if (index != null) {
                        player.addMediaItem(index, mediaItem)
                    } else {
                        player.addMediaItem(mediaItem)
                    }
                    updateQueueSize()
                    if (player.mediaItemCount == 1) {
                        // Start playing if this was the first time added
                        start()
                    }
                }
            } else {
                Timber.w("Tried to add non-audio type %s: %s", item.type, item.id)
            }
        }

        /**
         *  Add all the items in teh list to end of the queue
         *
         *  @param startIndex The index to start from within the source list
         */
        suspend fun addAllToQueue(
            list: BlockingList<BaseItem?>,
            startIndex: Int,
            rule: EntryRule = EntryRule.FULL,
        ) = loading {
            var remaining = startIndex
            list.indices
                .chunked(25)
                .forEach {
                    val songs =
                        it.mapNotNull {
                            if (remaining == 0) {
                                list
                                    .getBlocking(it)
                                    ?.takeIf { it.type == BaseItemKind.AUDIO }
                            } else {
                                Timber.v("Skipping $remaining")
                                remaining--
                                null
                            }
                        }
                    val mediaItems = convertSongs(songs, rule)
                    onMain { player.addMediaItems(mediaItems) }
                }
            updateQueueSize()
            start()
        }

        /**
         * Converts a [BaseItem] into a [MediaItem] setting an [AudioItem] as its tag
         */
        private fun convert(
            audio: BaseItem,
            preferAc3Surround: Boolean,
            rule: EntryRule,
        ): MediaItem {
            val needsAc3Transcode =
                preferAc3Surround &&
                    audio.data.mediaSources
                        ?.firstOrNull()
                        ?.mediaStreams
                        ?.any { stream ->
                            stream.type == MediaStreamType.AUDIO &&
                                stream.channels.gt(2)
                            stream.codec != Codec.Audio.AC3
                        } == true
            val url =
                if (needsAc3Transcode) {
                    api.audioApi.getUniversalAudioStreamUrl(
                        itemId = audio.id,
                        container = listOf("mka"),
                        transcodingContainer = "mka",
                        maxAudioChannels = 6,
                        transcodingAudioChannels = 6,
                        audioCodec = Codec.Audio.AC3,
                    )
                } else {
                    api.audioApi.getUniversalAudioStreamUrl(
                        itemId = audio.id,
                        container = audioFormats,
                    )
                }
            Timber.i("url=%s", url)
            val imageUrl =
                audio.data.albumId?.let { albumId ->
                    imageUrlService.getItemImageUrl(
                        itemId = albumId,
                        imageType = ImageType.PRIMARY,
                        tag = audio.data.albumPrimaryImageTag,
                    )
                }
            return MediaItem
                .Builder()
                .setUri(url)
                .setMediaId(audio.id.toServerString())
                .setTag(AudioItem.from(audio, imageUrl, rule))
                .build()
        }

        /**
         * Updates the state for when the queue changes
         */
        private suspend fun updateQueueSize() {
//            val ids =
//                withContext(WholphinDispatchers.Default) {
//                    (0..<player.mediaItemCount).map { player.getMediaItemAt(it).mediaId.toUUID() }
//                }
            val timeline = onMain { player.currentTimeline }
            val window = Timeline.Window()
            val ids =
                (0..<timeline.windowCount)
                    .map {
                        timeline.getWindow(it, window)
                        window.mediaItem.mediaId.toUUID()
                    }.toSet()
            withContext(WholphinDispatchers.Main) {
                _state.update {
                    it.copy(
                        queueVersion = it.queueVersion + 1,
                        queueSize = player.mediaItemCount,
                        queuedIds = ids,
                    )
                }
            }
        }

        /**
         * Move an item within the queue
         */
        suspend fun moveQueue(
            index: Int,
            direction: MoveDirection,
        ) = withContext(WholphinDispatchers.Main) {
            player.moveMediaItem(index, if (direction == MoveDirection.UP) index - 1 else index + 1)
            updateQueueSize()
        }

        /**
         * Move an item within the queue
         */
        suspend fun moveQueue(
            index: Int,
            newIndex: Int,
        ) = withContext(WholphinDispatchers.Main) {
            player.moveMediaItem(index, newIndex)
            updateQueueSize()
        }

        /**
         * Start playback at the given index of the queue
         */
        suspend fun playIndex(index: Int) {
            onMain {
                player.seekTo(index, 0L)
                player.play()
            }
            // MusicPlayerListener will update state
        }

        /**
         * Play this item next after the current, ie add the item as the next index in the queue
         */
        suspend fun playNext(song: BaseItem) {
            // A single song added on purpose plays even if disliked, like a song started on its own
            val mediaItem = convertSongs(listOf(song), EntryRule.EXEMPT).firstOrNull() ?: return
            onMain {
                player.addMediaItem(state.value.currentIndex + 1, mediaItem)
                if (player.mediaItemCount == 1) {
                    start()
                }
            }
            updateQueueSize()
        }

        /**
         * From the item at the given index from the queue
         */
        suspend fun removeFromQueue(index: Int) {
            onMain { player.removeMediaItem(index) }
            updateQueueSize()
        }

        private suspend fun <T> loading(block: suspend () -> T): T {
            _state.update { it.copy(loadingState = LoadingState.Loading) }
            val result = block.invoke()
            _state.update { it.copy(loadingState = LoadingState.Success) }
            return result
        }

        /**
         * Subscribes to the server websocket to receive playback commands
         */
        private fun subscribe(): Job =
            api.webSocket
                .subscribe<PlaystateMessage>()
                .onEach { message ->
                    message.data?.let {
                        withContext(WholphinDispatchers.Main) {
                            when (it.command) {
                                PlaystateCommand.STOP -> {
                                    stop()
                                }

                                PlaystateCommand.PAUSE -> {
                                    player.pause()
                                }

                                PlaystateCommand.UNPAUSE -> {
                                    player.play()
                                }

                                PlaystateCommand.NEXT_TRACK -> {
                                    player.seekToNext()
                                }

                                PlaystateCommand.PREVIOUS_TRACK -> {
                                    player.seekToPrevious()
                                }

                                PlaystateCommand.SEEK -> {
                                    it.seekPositionTicks?.ticks?.let {
                                        player.seekTo(
                                            it.inWholeMilliseconds,
                                        )
                                    }
                                }

                                PlaystateCommand.REWIND -> {
                                    player.seekBack(10.seconds)
                                }

                                PlaystateCommand.FAST_FORWARD -> {
                                    player.seekForward(30.seconds)
                                }

                                PlaystateCommand.PLAY_PAUSE -> {
                                    if (player.isPlaying) player.pause() else player.play()
                                }
                            }
                        }
                    }
                }.catch { ex ->
                    Timber.e(ex, "Error in websocket subscription")
                }.launchIn(defaultScope)
    }

@Stable
data class MusicServiceState(
    val queueVersion: Long,
    val queueSize: Int,
    val currentIndex: Int,
    val currentItemId: UUID?,
    val status: NowPlayingStatus,
    val currentItemTitle: String?,
    val loadingState: LoadingState = LoadingState.Pending,
    val queuedIds: Set<UUID>,
) {
    companion object {
        val EMPTY =
            MusicServiceState(
                0L,
                0,
                0,
                null,
                NowPlayingStatus.IDLE,
                null,
                LoadingState.Pending,
                emptySet(),
            )
    }
}

enum class NowPlayingStatus {
    PLAYING,
    PAUSED,
    IDLE,
}

/**
 * Listens to [Player] events and updates the [StateFlow]
 */
private class MusicPlayerListener(
    private val player: Player,
    private val state: MutableStateFlow<MusicServiceState>,
) : Player.Listener {
    init {
        Timber.v("MusicPlayerListener init")
        state.update {
            it.copy(
                queueSize = player.mediaItemCount,
                currentIndex = player.currentMediaItemIndex,
                status = if (player.isPlaying) NowPlayingStatus.PLAYING else NowPlayingStatus.IDLE,
            )
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        Timber.v("MusicPlayerListener onIsPlayingChanged")
        state.update {
            it.copy(
                status = if (isPlaying) NowPlayingStatus.PLAYING else NowPlayingStatus.PAUSED,
            )
        }
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        Timber.v("MusicPlayerListener onMediaItemTransition")
        updateCurrentIndex()
    }

    override fun onTimelineChanged(
        timeline: Timeline,
        reason: Int,
    ) {
//        Timber.v("MusicPlayerListener onTimelineChanged")
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            updateCurrentIndex()
        }
    }

    private fun updateCurrentIndex() {
        state.update { state ->
            player.currentMediaItemIndex.takeIf { it >= 0 }?.let { currentMediaItemIndex ->
                if (currentMediaItemIndex in (0..<player.mediaItemCount)) {
                    val item =
                        player.getMediaItemAt(currentMediaItemIndex).localConfiguration?.tag as? AudioItem
                    state.copy(
                        currentIndex = currentMediaItemIndex,
                        currentItemId = player.getMediaItemAt(currentMediaItemIndex).mediaId.toUUIDOrNull(),
                        currentItemTitle = item?.title,
                    )
                } else {
                    state
                }
            } ?: state
        }
    }
}

/**
 * Remember the queue currently playing
 *
 * @see MusicServiceState
 */
@Composable
fun rememberQueue(
    player: Player,
    queueVersion: Long,
    queueSize: Int,
): List<AudioItem> =
    remember(queueVersion, queueSize) {
        object : AbstractList<AudioItem>() {
            override val size: Int
                get() = player.mediaItemCount

            override fun get(index: Int): AudioItem = player.getMediaItemAt(index).localConfiguration?.tag as AudioItem
        }
    }

/**
 * Maps the queue to what the server and remote controls expect in a session's NowPlayingQueue
 *
 * The playlist item id is unique per queue entry, so the same song queued twice stays distinguishable.
 * Entries without an [AudioItem] tag are skipped, the current id is null if the index is not a valid entry.
 */
internal fun queueReport(
    items: List<AudioItem?>,
    currentIndex: Int,
): Pair<List<QueueItem>, String?> =
    items.filterNotNull().map { QueueItem(id = it.id, playlistItemId = playlistItemId(it)) } to
        items.getOrNull(currentIndex)?.let { playlistItemId(it) }

/**
 * The most queue entries reported to the server
 *
 * A remote control such as jellyfin-web loads the reported queue with one request that lists every id
 * in the URL. 200 ids of 32 characters stay below the 8 KB request line limit that nginx and Kestrel
 * apply by default, and below jellyfin-web's default limit of 300 items. A longer queue would also be
 * sent again with every progress report.
 */
internal const val MAX_REPORTED_QUEUE = 200

/**
 * Entries before the current one that stay in the report, so a remote still shows the last songs
 */
internal const val REPORTED_BEFORE_CURRENT = 10

/**
 * The part of a queue of [size] entries to report, a window of at most [MAX_REPORTED_QUEUE] entries
 * that keeps [REPORTED_BEFORE_CURRENT] entries before the current one
 *
 * [currentIndex] below 0 means there is no current entry, the window then starts at the front.
 */
internal fun reportedQueueRange(
    size: Int,
    currentIndex: Int,
): IntRange {
    val start =
        if (currentIndex < 0) {
            0
        } else {
            (currentIndex - REPORTED_BEFORE_CURRENT).coerceAtMost(size - MAX_REPORTED_QUEUE).coerceAtLeast(0)
        }
    return start until minOf(size, start + MAX_REPORTED_QUEUE)
}

private fun playlistItemId(item: AudioItem) = "playlistItem" + item.key
