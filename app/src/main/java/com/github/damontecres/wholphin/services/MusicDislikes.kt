package com.github.damontecres.wholphin.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID

/**
 * How a queue entry is judged against the set of disliked songs
 *
 * The server applies the same distinction: a song started on purpose is never left out, and an album or
 * artist started on purpose leaves out only the songs disliked one by one.
 */
enum class EntryRule {
    /** Left out if the server's exclusion list has the song (songs, albums and artists disliked) */
    FULL,

    /** Left out only if the song itself is disliked */
    ITEMS_ONLY,

    /** Never left out */
    EXEMPT,
}

/**
 * What the user disliked: [itemIds] are the songs disliked one by one, [excludedIds] every song a
 * non-explicit start leaves out (includes the songs of disliked albums and artists)
 */
data class DislikeSet(
    val itemIds: Set<UUID>,
    val excludedIds: Set<UUID>,
) {
    fun isExcluded(
        id: UUID,
        rule: EntryRule,
    ): Boolean =
        when (rule) {
            EntryRule.EXEMPT -> false
            EntryRule.ITEMS_ONLY -> id in itemIds
            EntryRule.FULL -> id in excludedIds || id in itemIds
        }

    operator fun plus(id: UUID) = DislikeSet(itemIds + id, excludedIds + id)

    operator fun minus(id: UUID) = DislikeSet(itemIds - id, excludedIds - id)

    /** The set with the marks the app set itself applied: true puts a song in, false takes it out */
    fun withMarks(marks: Map<UUID, Boolean>): DislikeSet =
        marks.entries.fold(this) { set, (id, disliked) ->
            if (disliked) set + id else set - id
        }

    companion object {
        val EMPTY = DislikeSet(emptySet(), emptySet())

        /** The set built from the songs whose own user data says `likes == false` (no plugin on the server) */
        fun fromLikes(ids: Collection<UUID>) = DislikeSet(ids.toSet(), ids.toSet())
    }
}

/**
 * Reads the answer of `GET /Dislike/Items?includeExcluded=true`
 *
 * Keys are matched in either case. Without `ExcludedIds` the item ids stand in for it. Returns null if
 * [json] is not an object.
 */
internal fun parseDislikedItems(json: String): DislikeSet? {
    val root =
        try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (ex: Exception) {
            null
        } ?: return null

    fun ids(name: String): Set<UUID> =
        (root.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value as? JsonArray)
            // Jellyfin writes ids without dashes; toUUIDOrNull reads both forms
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.toUUIDOrNull() }
            ?.toSet()
            .orEmpty()
    val items = ids("ItemIds")
    val excluded = ids("ExcludedIds")
    return DislikeSet(items, if (excluded.isEmpty()) items else excluded)
}

/**
 * The rule for the entries of a queue that is started now
 *
 * - [singleSong]: a song started on its own is never left out
 * - [explicitContainer]: an album or artist started on purpose leaves out only [DislikeSet.itemIds]
 * - a list in which every song is excluded ([listIds], such as a playlist of dislikes) is exempt as a whole
 */
internal fun ruleForStart(
    explicitContainer: Boolean,
    singleSong: Boolean,
    listIds: List<UUID>?,
    set: DislikeSet,
): EntryRule {
    if (singleSong) return EntryRule.EXEMPT
    val base = if (explicitContainer) EntryRule.ITEMS_ONLY else EntryRule.FULL
    return if (!listIds.isNullOrEmpty() && listIds.all { set.isExcluded(it, base) }) EntryRule.EXEMPT else base
}

/** The entries that may be queued under [rule], in order */
internal fun <T> List<T>.queueable(
    rule: EntryRule,
    set: DislikeSet,
    id: (T) -> UUID,
): List<T> = filterNot { set.isExcluded(id(it), rule) }

/**
 * The entries that may be queued under [rule] and where playback starts in that list
 *
 * [startIndex] refers to this list. If the start entry is left out, the next kept entry becomes the start,
 * and the last one if there is none after it.
 */
internal fun <T> List<T>.queueableWithStart(
    rule: EntryRule,
    set: DislikeSet,
    startIndex: Int,
    id: (T) -> UUID,
): Pair<List<T>, Int> {
    val kept = queueable(rule, set, id)
    val start = take(startIndex).count { !set.isExcluded(id(it), rule) }
    return kept to start.coerceAtMost((kept.size - 1).coerceAtLeast(0))
}

/** One entry of the player's queue as far as the exclusion is concerned */
internal data class QueueEntry(
    val id: UUID,
    val rule: EntryRule,
)

/**
 * The indices of upcoming entries (behind [currentIndex]) that the set now excludes, ascending
 *
 * The current entry and everything before it stay, as do exempt entries.
 */
internal fun staleUpcoming(
    entries: List<QueueEntry>,
    currentIndex: Int,
    set: DislikeSet,
): List<Int> = entries.indices.filter { it > currentIndex && set.isExcluded(entries[it].id, entries[it].rule) }

/** One rating entry of a user-data message: [likes] is true, false or null (no rating) */
internal data class RatingChange(
    val itemId: UUID,
    val likes: Boolean?,
)

/**
 * What a rating change from elsewhere tells the app about the set: a dislike puts the song in, any other
 * rating of a song that the set holds as disliked takes it out. Needed because the server answers the
 * list of disliked songs from a snapshot that can be minutes old.
 */
internal fun ratingMarks(
    changes: List<RatingChange>,
    set: DislikeSet,
): Map<UUID, Boolean> =
    changes
        .mapNotNull { change ->
            when {
                change.likes == false -> change.itemId to true
                change.itemId in set.itemIds -> change.itemId to false
                else -> null
            }
        }.toMap()

/**
 * Whether the current entry is skipped after a rating changed elsewhere
 *
 * Only when the message holds a dislike, the entry is not exempt, the refreshed [set] excludes it under its
 * [currentRule] and the app did not rate it itself a moment ago ([ownRated]: the thumb-down skips on its own).
 */
internal fun skipCurrentAfterRating(
    changes: List<RatingChange>,
    currentId: UUID?,
    currentRule: EntryRule,
    set: DislikeSet,
    ownRated: Set<UUID>,
): Boolean =
    currentId != null &&
        currentRule != EntryRule.EXEMPT &&
        currentId !in ownRated &&
        changes.any { it.likes == false } &&
        set.isExcluded(currentId, currentRule)
