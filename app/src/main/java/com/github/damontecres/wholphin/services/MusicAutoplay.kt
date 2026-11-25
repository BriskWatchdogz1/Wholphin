package com.github.damontecres.wholphin.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Autoplay loads more when this many entries or fewer follow the current one */
internal const val AUTOPLAY_REMAINING = 5

/** How many songs one Instant Mix request asks for */
internal const val AUTOPLAY_BATCH = 25

/** Entries that stay in front of the current one; older ones are dropped from the queue after a load */
internal const val AUTOPLAY_KEEP_PLAYED = 200

/**
 * Where autoplay takes more songs from
 */
sealed interface AutoplaySource {
    /** Instant Mix of the last entry of the queue */
    data object InstantMix : AutoplaySource

    /** Another random batch of the music library a library-wide shuffle came from */
    data class LibraryRandom(
        val libraryId: UUID,
    ) : AutoplaySource
}

/**
 * What autoplay remembers for one music session: where the queue came from and which songs played (ids only)
 */
class AutoplaySession {
    private val played = HashSet<UUID>()

    @Volatile
    var source: AutoplaySource = AutoplaySource.InstantMix
        private set

    /** A queue starts: the source is the given one, every other start means Instant Mix */
    fun queueStarted(source: AutoplaySource = AutoplaySource.InstantMix) {
        this.source = source
    }

    fun markPlayed(id: UUID) {
        synchronized(played) { played.add(id) }
    }

    /** The songs autoplay must not add again: those in [queue] and those played in this session */
    fun taken(queue: Collection<UUID>): Set<UUID> = synchronized(played) { HashSet<UUID>(played) }.also { it.addAll(queue) }

    /** The music session ended */
    fun clear() {
        synchronized(played) { played.clear() }
        source = AutoplaySource.InstantMix
    }
}

/**
 * Whether autoplay loads more songs now
 *
 * [busy] is true while a load of autoplay or a chunk loader of remote play or shuffle runs, or a pager fills the queue.
 */
internal fun shouldLoadMore(
    enabled: Boolean,
    repeatOff: Boolean,
    queueSize: Int,
    currentIndex: Int,
    busy: Boolean,
): Boolean =
    enabled && repeatOff && !busy && currentIndex in 0 until queueSize &&
        queueSize - currentIndex - 1 <= AUTOPLAY_REMAINING

/**
 * The songs Instant Mix is asked for, in order: the last entry of [queue], then the one before it
 */
internal fun autoplaySeeds(queue: List<UUID>): List<UUID> = queue.takeLast(2).reversed().distinct()

/**
 * The part of an Instant Mix answer that is appended, in the order of the answer
 *
 * In this order: the [seedId] is removed; songs in [taken] (queue and played) are removed; excluded songs are removed
 * (rule [EntryRule.FULL]); then a song whose artist equals the one before it is dropped, not moved. The first song is
 * compared with [previousArtistKey], the artist of the queue's last entry. A song that appears twice stays once.
 */
internal fun <T> filterInstantMix(
    candidates: List<T>,
    seedId: UUID,
    taken: Set<UUID>,
    set: DislikeSet,
    previousArtistKey: String?,
    song: (T) -> LightSong,
): List<T> {
    val seen = HashSet<UUID>()
    val usable =
        candidates
            .filter { song(it).id != seedId }
            .filter { song(it).id !in taken }
            .queueable(EntryRule.FULL, set) { song(it).id }
            .filter { seen.add(song(it).id) }
    var previous = previousArtistKey
    val result = ArrayList<T>(usable.size)
    for (item in usable) {
        val key = song(item).artistKey
        if (key == previous) continue
        result.add(item)
        previous = key
    }
    return result
}

/**
 * The part of a random library batch that is appended, in smart shuffle order
 *
 * Songs in [taken] and excluded songs are removed, then the rest is ordered so that the first song does not
 * share the artist of [previousArtistKey].
 */
internal fun <T> filterLibraryBatch(
    candidates: List<T>,
    taken: Set<UUID>,
    set: DislikeSet,
    previousArtistKey: String?,
    now: Instant,
    random: Random,
    song: (T) -> LightSong,
): List<T> {
    val seen = HashSet<UUID>()
    val fresh = candidates.filter { song(it).id !in taken && seen.add(song(it).id) }
    return smartOrder(fresh, EntryRule.FULL, set, now, random, previousArtistKey, song)
}

/**
 * How many entries in front of the current one to drop so that [AUTOPLAY_KEEP_PLAYED] stay
 */
internal fun playedToTrim(currentIndex: Int): Int = (currentIndex - AUTOPLAY_KEEP_PLAYED).coerceAtLeast(0)

/**
 * Counts the loaders that fill the queue in the background (remote play, shuffle), so autoplay waits for them
 */
@Singleton
class QueueLoadState
    @Inject
    constructor() {
        private val count = MutableStateFlow(0)

        /** True while at least one loader runs */
        val running: StateFlow<Boolean> get() = _running
        private val _running = MutableStateFlow(false)

        fun begin() {
            _running.value = count.updateAndGet { it + 1 } > 0
        }

        fun end() {
            _running.value = count.updateAndGet { (it - 1).coerceAtLeast(0) } > 0
        }
    }
