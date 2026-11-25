package com.github.damontecres.wholphin.services

import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/**
 * What the shuffle needs to know about a song
 *
 * @param artistKey the primary artist, normalized; a song without an artist has a key of its own
 * @param lastPlayed when the user last played the song, if ever
 */
internal data class ShuffleTrack(
    val artistKey: String,
    val lastPlayed: Instant?,
)

/** A song played within this time counts as recently played */
internal val RECENT_PLAY_WINDOW: Duration = 72.hours

private const val TIER_COUNT = 3

/**
 * Produces a play order that keeps the same artist from playing twice in a row and moves recently played songs to the back
 *
 * Port of the Playlist Toolkit's `SmartShuffler`. With R songs left and artist L played last, an order without
 * repeats exists exactly when no other artist has more than ceil(R/2) songs and L has at most floor(R/2). Each step
 * only considers artists that keep this true, so the order never paints itself into a corner. Among those, songs
 * not played within [recentWindow] come first, then the older half of the recently played ones, then the newer
 * half; the artist rule always wins over recency.
 *
 * @param previousArtistKey the artist of the song that plays right before this order, which must not open it
 * @return indices into [tracks] in play order
 */
internal fun smartShuffle(
    tracks: List<ShuffleTrack>,
    now: Instant,
    recentWindow: Duration = RECENT_PLAY_WINDOW,
    random: Random,
    previousArtistKey: String? = null,
): List<Int> {
    // Tier 0: not played recently. Tiers 1 and 2: recently played, the older half and the newer half
    val tiers = IntArray(tracks.size)
    val threshold = now.minus(recentWindow.toJavaDuration())
    val recent =
        tracks.indices
            .filter { i -> tracks[i].lastPlayed?.let { it.isAfter(threshold) } == true }
            .sortedBy { tracks[it].lastPlayed }
    val olderCount = recent.size - recent.size / 2
    recent.forEachIndexed { i, track -> tiers[track] = if (i < olderCount) 1 else 2 }

    // Per artist: songs in random order, lowest tier taken first (from the end of the list)
    val groups = LinkedHashMap<String, MutableList<Int>>()
    tracks.forEachIndexed { i, track -> groups.getOrPut(track.artistKey) { mutableListOf() }.add(i) }
    val artists = groups.keys.toList()
    val queues = ArrayList<MutableList<Int>>(artists.size)
    val counts = IntArray(artists.size)
    val tierCounts = Array(artists.size) { IntArray(TIER_COUNT) }
    artists.forEachIndexed { a, artist ->
        val list = groups.getValue(artist)
        list.shuffle(random)
        queues.add(list.sortedByDescending { tiers[it] }.toMutableList())
        counts[a] = list.size
        list.forEach { tierCounts[a][tiers[it]]++ }
    }

    val result = ArrayList<Int>(tracks.size)
    var remaining = tracks.size
    var last = previousArtistKey?.let { artists.indexOf(it) } ?: -1
    val safe = ArrayList<Int>(artists.size)
    while (remaining > 0) {
        var max1 = -1
        var max2 = -1
        var max1Index = -1
        for (a in counts.indices) {
            if (counts[a] > max1) {
                max2 = max1
                max1 = counts[a]
                max1Index = a
            } else if (counts[a] > max2) {
                max2 = counts[a]
            }
        }
        val after = remaining - 1
        val ceilHalf = (after + 1) / 2
        val floorHalf = after / 2

        safe.clear()
        var fallback = -1
        var lowestTier = TIER_COUNT
        for (a in counts.indices) {
            if (counts[a] == 0 || a == last) continue
            if (fallback < 0 || counts[a] > counts[fallback]) fallback = a
            val maxOther = if (a == max1Index) max2 else max1
            if (maxOther <= ceilHalf && counts[a] - 1 <= floorHalf) {
                safe.add(a)
                lowestTier = minOf(lowestTier, tiers[queues[a].last()])
            }
        }

        val chosen =
            if (safe.isNotEmpty()) {
                // Weighted by the songs in the tier: every eligible song is equally likely
                val weight = safe.sumOf { tierCounts[it][lowestTier] }
                var ticket = random.nextInt(weight)
                var pick = safe.last()
                for (a in safe) {
                    ticket -= tierCounts[a][lowestTier]
                    if (ticket < 0) {
                        pick = a
                        break
                    }
                }
                pick
            } else if (fallback >= 0) {
                // No repeat-free order exists any more: take the biggest other artist to limit the damage
                fallback
            } else {
                // Only the artist played last is left
                last
            }

        val queue = queues[chosen]
        val track = queue.removeAt(queue.size - 1)
        result.add(track)
        counts[chosen]--
        tierCounts[chosen][tiers[track]]--
        remaining--
        last = chosen
    }
    return result
}

/**
 * A song as the shuffle sees it: loaded without the heavy fields
 */
internal data class LightSong(
    val id: UUID,
    val artistKey: String,
    val lastPlayed: Instant?,
) {
    fun toTrack() = ShuffleTrack(artistKey, lastPlayed)
}

/**
 * The artist of a song: the first track artist, else the album artist. A song without one gets a key of its
 * own, so songs without an artist never block each other.
 */
internal fun artistKeyOf(item: BaseItemDto): String {
    val name =
        item.artistItems
            ?.firstNotNullOfOrNull { it.name }
            ?: item.artists?.firstOrNull()
            ?: item.albumArtists?.firstNotNullOfOrNull { it.name }
            ?: item.albumArtist
    val normalized = name?.trim()?.lowercase()
    return if (normalized.isNullOrEmpty()) "\u0000${item.id}" else normalized
}

internal fun BaseItemDto.toLightSong() =
    LightSong(
        id = id,
        artistKey = artistKeyOf(this),
        lastPlayed = userData?.lastPlayedDate?.toInstant(ZoneOffset.UTC),
    )

/**
 * The songs that may be queued under [rule], in smart shuffle order
 *
 * Excluded songs are left out before ordering, so that songs of one artist do not end up next to each other when
 * the ones between them are gone.
 */
internal fun <T> smartOrder(
    songs: List<T>,
    rule: EntryRule,
    set: DislikeSet,
    now: Instant,
    random: Random,
    previousArtistKey: String? = null,
    track: (T) -> LightSong,
): List<T> {
    val kept = songs.queueable(rule, set) { track(it).id }
    val order = smartShuffle(kept.map { track(it).toTrack() }, now, RECENT_PLAY_WINDOW, random, previousArtistKey)
    return order.map { kept[it] }
}

/**
 * How to reorder the entries after the current one: the source index of each of them in the new order
 *
 * [order] lists the song ids in the wanted order. Entries that are not in it (added since it was computed)
 * keep their order behind the rest, ids that are no longer queued are skipped, and a song queued twice keeps
 * both entries. Returns null if fewer than 2 entries follow the current one.
 */
internal fun planReorder(
    queue: List<UUID>,
    currentIndex: Int,
    order: List<UUID>,
): List<Int>? {
    val first = currentIndex + 1
    if (queue.size - first < 2) return null
    val byId = HashMap<UUID, ArrayDeque<Int>>()
    for (i in first until queue.size) byId.getOrPut(queue[i]) { ArrayDeque() }.addLast(i)
    val plan = ArrayList<Int>(queue.size - first)
    for (id in order) byId[id]?.removeFirstOrNull()?.let { plan.add(it) }
    val used = plan.toHashSet()
    for (i in first until queue.size) if (i !in used) plan.add(i)
    return plan
}
