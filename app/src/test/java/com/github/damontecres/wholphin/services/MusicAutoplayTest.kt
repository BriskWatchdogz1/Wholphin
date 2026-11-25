package com.github.damontecres.wholphin.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

class MusicAutoplayTest {
    private val now = Instant.parse("2026-11-25T12:00:00Z")

    @Test
    fun `a mix keeps only songs from the seed's library`() {
        val music = UUID(1, 0)
        val comedy = UUID(2, 0)
        val libraries = mapOf("song1" to music, "sketch" to comedy, "song2" to music, "unknown" to null)
        val kept = keepSeedLibrary(listOf("song1", "sketch", "song2", "unknown"), music) { libraries[it] }
        assertEquals(listOf("song1", "song2"), kept)
    }

    @Test
    fun `an unknown seed library keeps the whole mix`() {
        val all = listOf("song1", "sketch")
        assertEquals(all, keepSeedLibrary(all, null) { UUID(2, 0) })
    }

    private fun song(
        artist: String,
        id: UUID = UUID.randomUUID(),
    ) = LightSong(id, artist, null)

    private fun ids(songs: List<LightSong>) = songs.map { it.id }

    private fun mix(
        candidates: List<LightSong>,
        seed: UUID = UUID.randomUUID(),
        taken: Set<UUID> = emptySet(),
        set: DislikeSet = DislikeSet.EMPTY,
        previous: String? = null,
    ) = filterInstantMix(candidates, seed, taken, set, previous) { it }

    // trigger

    private fun load(
        enabled: Boolean = true,
        repeatOff: Boolean = true,
        size: Int = 10,
        index: Int = 4,
        busy: Boolean = false,
    ) = shouldLoadMore(enabled, repeatOff, size, index, busy)

    @Test
    fun `loads at 5 left and not at 6`() {
        assertTrue(load(size = 10, index = 4))
        assertFalse(load(size = 10, index = 3))
    }

    @Test
    fun `loads when the current song is the last`() {
        assertTrue(load(size = 10, index = 9))
        assertTrue(load(size = 1, index = 0))
    }

    @Test
    fun `no load when off, repeating or busy`() {
        assertFalse(load(enabled = false))
        assertFalse(load(repeatOff = false))
        assertFalse(load(busy = true))
    }

    @Test
    fun `no load without a current entry`() {
        assertFalse(load(size = 0, index = 0))
        assertFalse(load(size = 10, index = -1))
        assertFalse(load(size = 10, index = 10))
    }

    // seeds

    @Test
    fun `seeds are the last entry then the one before`() {
        val queue = List(4) { UUID.randomUUID() }
        assertEquals(listOf(queue[3], queue[2]), autoplaySeeds(queue))
    }

    @Test
    fun `a queue of one entry has one seed`() {
        val queue = listOf(UUID.randomUUID())
        assertEquals(queue, autoplaySeeds(queue))
        assertEquals(emptyList<UUID>(), autoplaySeeds(emptyList()))
    }

    @Test
    fun `the same song twice at the end is one seed`() {
        val id = UUID.randomUUID()
        assertEquals(listOf(id), autoplaySeeds(listOf(UUID.randomUUID(), id, id)))
    }

    // instant mix filter

    @Test
    fun `seed is removed by id`() {
        val seed = song("a")
        val other = song("b")
        assertEquals(listOf(other), mix(listOf(seed, other), seed = seed.id))
    }

    @Test
    fun `songs in the queue or played are removed`() {
        val inQueue = song("a")
        val played = song("b")
        val fresh = song("c")
        assertEquals(listOf(fresh), mix(listOf(inQueue, played, fresh), taken = setOf(inQueue.id, played.id)))
    }

    @Test
    fun `excluded songs are removed under the full rule`() {
        val bad = song("a")
        val albumBad = song("b")
        val fine = song("c")
        val set = DislikeSet(itemIds = setOf(bad.id), excludedIds = setOf(bad.id, albumBad.id))
        assertEquals(listOf(fine), mix(listOf(bad, albumBad, fine), set = set))
    }

    @Test
    fun `a song with the artist of the one before is dropped, the rest keeps its order`() {
        val a1 = song("a")
        val a2 = song("a")
        val b = song("b")
        val a3 = song("a")
        val a4 = song("a")
        val c = song("c")
        assertEquals(listOf(a1, b, a3, c), mix(listOf(a1, a2, b, a3, a4, c)))
    }

    @Test
    fun `the first song is compared with the last queue entry`() {
        val a = song("a")
        val b = song("b")
        assertEquals(listOf(b), mix(listOf(a, b), previous = "a"))
    }

    @Test
    fun `the artist walk runs after the removals`() {
        // b is excluded, so the two a songs become neighbours and the second one goes
        val a1 = song("a")
        val b = song("b")
        val a2 = song("a")
        val set = DislikeSet(setOf(b.id), setOf(b.id))
        assertEquals(listOf(a1), mix(listOf(a1, b, a2), set = set))
    }

    @Test
    fun `nothing left gives an empty list`() {
        val seed = song("a")
        assertEquals(emptyList<LightSong>(), mix(listOf(seed), seed = seed.id))
        assertEquals(emptyList<LightSong>(), mix(emptyList()))
    }

    @Test
    fun `a song twice in the answer stays once`() {
        val a = song("a")
        val b = song("b")
        assertEquals(listOf(a, b), mix(listOf(a, a, b)))
    }

    // library batch

    private fun library(
        candidates: List<LightSong>,
        taken: Set<UUID> = emptySet(),
        set: DislikeSet = DislikeSet.EMPTY,
        previous: String? = null,
        seed: Int = 1,
    ) = filterLibraryBatch(candidates, taken, set, previous, now, Random(seed)) { it }

    @Test
    fun `library batch drops taken, excluded and duplicates`() {
        val taken = song("a")
        val bad = song("b")
        val fine = song("c")
        val set = DislikeSet(setOf(bad.id), setOf(bad.id))
        val result = library(listOf(taken, bad, fine, fine), taken = setOf(taken.id), set = set)
        assertEquals(listOf(fine), result)
    }

    @Test
    fun `library batch has no artist repeat and does not start with the previous artist`() {
        val songs = List(60) { song("artist${it % 12}") }
        for (seed in 1..20) {
            val result = library(songs, previous = "artist3", seed = seed)
            assertEquals(60, result.size)
            assertTrue(result.first().artistKey != "artist3")
            result.zipWithNext().forEach { (x, y) -> assertTrue(x.artistKey != y.artistKey) }
        }
    }

    @Test
    fun `library batch with everything taken is empty`() {
        val songs = List(5) { song("a$it") }
        assertEquals(emptyList<LightSong>(), library(songs, taken = ids(songs).toSet()))
    }

    // session

    @Test
    fun `a new queue resets the source to instant mix`() {
        val session = AutoplaySession()
        val library = AutoplaySource.LibraryRandom(UUID.randomUUID())
        session.queueStarted(library)
        assertEquals(library, session.source)
        session.queueStarted()
        assertEquals(AutoplaySource.InstantMix, session.source)
    }

    @Test
    fun `taken holds the queue and the played ids`() {
        val session = AutoplaySession()
        val played = UUID.randomUUID()
        val queued = UUID.randomUUID()
        session.markPlayed(played)
        assertEquals(setOf(played, queued), session.taken(listOf(queued)))
    }

    @Test
    fun `played ids survive a new queue and go with the session`() {
        val session = AutoplaySession()
        val id = UUID.randomUUID()
        session.markPlayed(id)
        session.queueStarted()
        assertTrue(id in session.taken(emptyList()))
        session.queueStarted(AutoplaySource.LibraryRandom(UUID.randomUUID()))
        session.clear()
        assertTrue(session.taken(emptyList()).isEmpty())
        assertEquals(AutoplaySource.InstantMix, session.source)
    }

    // trim

    @Test
    fun `only entries beyond the kept ones are trimmed`() {
        assertEquals(0, playedToTrim(0))
        assertEquals(0, playedToTrim(AUTOPLAY_KEEP_PLAYED))
        assertEquals(30, playedToTrim(AUTOPLAY_KEEP_PLAYED + 30))
    }

    // loaders

    @Test
    fun `load state is running while a loader runs`() {
        val state = QueueLoadState()
        assertFalse(state.running.value)
        state.begin()
        state.begin()
        state.end()
        assertTrue(state.running.value)
        state.end()
        assertFalse(state.running.value)
        state.end()
        assertFalse(state.running.value)
    }
}
