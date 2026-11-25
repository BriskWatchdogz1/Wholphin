package com.github.damontecres.wholphin.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MusicDislikesTest {
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val c = UUID(0, 3)
    private val d = UUID(0, 4)

    // a is disliked itself, b only through a disliked album
    private val set = DislikeSet(itemIds = setOf(a), excludedIds = setOf(a, b))

    @Test
    fun `full rule leaves out excluded ids`() {
        assertTrue(set.isExcluded(a, EntryRule.FULL))
        assertTrue(set.isExcluded(b, EntryRule.FULL))
        assertFalse(set.isExcluded(c, EntryRule.FULL))
    }

    @Test
    fun `items-only rule leaves out only item ids`() {
        assertTrue(set.isExcluded(a, EntryRule.ITEMS_ONLY))
        assertFalse(set.isExcluded(b, EntryRule.ITEMS_ONLY))
    }

    @Test
    fun `exempt rule leaves out nothing`() {
        assertFalse(set.isExcluded(a, EntryRule.EXEMPT))
        assertFalse(set.isExcluded(b, EntryRule.EXEMPT))
    }

    @Test
    fun `plus adds to both sets and minus removes from both`() {
        val added = DislikeSet.EMPTY.plus(c)
        assertTrue(added.isExcluded(c, EntryRule.ITEMS_ONLY))
        assertTrue(added.isExcluded(c, EntryRule.FULL))
        val removed = added.minus(c)
        assertFalse(removed.isExcluded(c, EntryRule.ITEMS_ONLY))
        assertFalse(removed.isExcluded(c, EntryRule.FULL))
    }

    @Test
    fun `parser reads item and excluded ids`() {
        val json = """{"ItemIds":["$a"],"AlbumIds":[],"ExcludedIds":["$a","$b"]}"""
        assertEquals(set, parseDislikedItems(json))
        val camel = """{"itemIds":["$a"],"excludedIds":["$a","$b"]}"""
        assertEquals(set, parseDislikedItems(camel))
    }

    @Test
    fun `parser reads ids the way the server writes them, without dashes`() {
        fun plain(id: java.util.UUID) = id.toString().replace("-", "")
        val json = """{"ItemIds":["${plain(a)}"],"ExcludedIds":["${plain(a)}","${plain(b)}"]}"""
        assertEquals(set, parseDislikedItems(json))
    }

    @Test
    fun `parser without excluded ids falls back to the item ids`() {
        val parsed = parseDislikedItems("""{"itemIds":["$a"],"excludedIds":null}""")
        assertEquals(DislikeSet(setOf(a), setOf(a)), parsed)
    }

    @Test
    fun `parser returns null for something that is not an object`() {
        assertNull(parseDislikedItems("not json"))
        assertNull(parseDislikedItems("[1,2]"))
    }

    @Test
    fun `likes-false fallback builds both sets from the ids`() {
        assertEquals(DislikeSet(setOf(a, c), setOf(a, c)), DislikeSet.fromLikes(listOf(a, c)))
    }

    @Test
    fun `single song start is exempt`() {
        assertEquals(EntryRule.EXEMPT, ruleForStart(false, true, null, set))
    }

    @Test
    fun `album or artist start uses items-only`() {
        assertEquals(EntryRule.ITEMS_ONLY, ruleForStart(true, false, listOf(a, c), set))
    }

    @Test
    fun `other start uses full`() {
        assertEquals(EntryRule.FULL, ruleForStart(false, false, listOf(a, c), set))
        assertEquals(EntryRule.FULL, ruleForStart(false, false, null, set))
    }

    @Test
    fun `list with every song excluded is exempt`() {
        assertEquals(EntryRule.EXEMPT, ruleForStart(false, false, listOf(a, b), set))
    }

    @Test
    fun `list with some songs excluded is not exempt`() {
        assertEquals(EntryRule.FULL, ruleForStart(false, false, listOf(a, c), set))
    }

    @Test
    fun `empty list is not exempt`() {
        assertEquals(EntryRule.FULL, ruleForStart(false, false, emptyList(), set))
    }

    @Test
    fun `queueable drops excluded entries and keeps the order`() {
        val result = listOf(c, a, d, b).queueable(EntryRule.FULL, set) { it }
        assertEquals(listOf(c, d), result)
        assertEquals(listOf(c, b, d), listOf(c, a, b, d).queueable(EntryRule.ITEMS_ONLY, set) { it })
        assertEquals(listOf(a, b), listOf(a, b).queueable(EntryRule.EXEMPT, set) { it })
    }

    @Test
    fun `start index follows the kept entries`() {
        // a and b are excluded, the start was d (index 3)
        val (kept, start) = listOf(a, b, c, d).queueableWithStart(EntryRule.FULL, set, 3) { it }
        assertEquals(listOf(c, d), kept)
        assertEquals(1, start)
    }

    @Test
    fun `a left-out start entry hands the start to the next kept one`() {
        val (kept, start) = listOf(c, a, d).queueableWithStart(EntryRule.FULL, set, 1) { it }
        assertEquals(listOf(c, d), kept)
        assertEquals(1, start)
        val (keptLast, startLast) = listOf(c, a).queueableWithStart(EntryRule.FULL, set, 1) { it }
        assertEquals(listOf(c), keptLast)
        assertEquals(0, startLast)
    }

    @Test
    fun `marks put songs in and take them out`() {
        val withMarks = set.withMarks(mapOf(c to true, a to false))
        assertTrue(withMarks.isExcluded(c, EntryRule.ITEMS_ONLY))
        assertFalse(withMarks.isExcluded(a, EntryRule.FULL))
        assertTrue(withMarks.isExcluded(b, EntryRule.FULL))
    }

    private fun entries(vararg pairs: Pair<UUID, EntryRule>) = pairs.map { QueueEntry(it.first, it.second) }

    @Test
    fun `stale upcoming returns only indices after the current one`() {
        val queue = entries(c to EntryRule.FULL, a to EntryRule.FULL, d to EntryRule.FULL, b to EntryRule.FULL)
        assertEquals(listOf(1, 3), staleUpcoming(queue, 0, set))
        assertEquals(listOf(3), staleUpcoming(queue, 1, set))
    }

    @Test
    fun `the current entry and earlier ones are never returned`() {
        val queue = entries(a to EntryRule.FULL, b to EntryRule.FULL, c to EntryRule.FULL)
        assertEquals(emptyList<Int>(), staleUpcoming(queue, 2, set))
        assertEquals(listOf(1), staleUpcoming(queue, 0, set))
    }

    @Test
    fun `exempt entries are kept`() {
        val queue = entries(c to EntryRule.FULL, a to EntryRule.EXEMPT, b to EntryRule.FULL)
        assertEquals(listOf(2), staleUpcoming(queue, 0, set))
    }

    @Test
    fun `items-only entries use the item ids`() {
        val queue = entries(c to EntryRule.ITEMS_ONLY, a to EntryRule.ITEMS_ONLY, b to EntryRule.ITEMS_ONLY)
        assertEquals(listOf(1), staleUpcoming(queue, 0, set))
    }

    @Test
    fun `nothing excluded returns an empty list`() {
        val queue = entries(c to EntryRule.FULL, d to EntryRule.FULL)
        assertEquals(emptyList<Int>(), staleUpcoming(queue, 0, set))
        assertEquals(emptyList<Int>(), staleUpcoming(emptyList(), 0, set))
    }
}

class RatingChangeTest {
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val c = UUID(0, 3)
    private val set = DislikeSet(itemIds = setOf(a), excludedIds = setOf(a, b))

    @Test
    fun `a dislike becomes a mark and a taken back dislike removes the song`() {
        val marks = ratingMarks(listOf(RatingChange(b, false), RatingChange(a, null), RatingChange(c, true)), set)
        assertEquals(mapOf(b to true, a to false), marks)
    }

    @Test
    fun `a rating of a song the set does not hold gives no mark`() {
        assertTrue(ratingMarks(listOf(RatingChange(c, true), RatingChange(c, null)), set).isEmpty())
    }

    private fun skip(
        changes: List<RatingChange>,
        current: UUID?,
        rule: EntryRule,
        refreshed: DislikeSet,
        own: Set<UUID> = emptySet(),
    ) = skipCurrentAfterRating(changes, current, rule, refreshed, own)

    @Test
    fun `current song disliked elsewhere is skipped`() {
        assertTrue(skip(listOf(RatingChange(a, false)), a, EntryRule.FULL, set))
        assertTrue(skip(listOf(RatingChange(a, false)), a, EntryRule.ITEMS_ONLY, set))
    }

    @Test
    fun `exempt current song is never skipped`() {
        assertFalse(skip(listOf(RatingChange(a, false)), a, EntryRule.EXEMPT, set))
    }

    @Test
    fun `the echo of the app's own rating does not skip again`() {
        assertFalse(skip(listOf(RatingChange(a, false)), a, EntryRule.FULL, set, own = setOf(a)))
    }

    @Test
    fun `message without a dislike does not skip`() {
        assertFalse(skip(listOf(RatingChange(a, true)), a, EntryRule.FULL, set))
        assertFalse(skip(listOf(RatingChange(c, null)), c, EntryRule.FULL, set))
    }

    @Test
    fun `current song that the set does not exclude stays`() {
        assertFalse(skip(listOf(RatingChange(a, false)), c, EntryRule.FULL, set))
        assertFalse(skip(listOf(RatingChange(a, false)), null, EntryRule.FULL, set))
        // disliked album excludes b under full but not under items-only
        assertFalse(skip(listOf(RatingChange(c, false)), b, EntryRule.ITEMS_ONLY, set))
    }
}
