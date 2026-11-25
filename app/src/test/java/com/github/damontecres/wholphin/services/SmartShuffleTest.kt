package com.github.damontecres.wholphin.services

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.NameGuidPair
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours

class SmartShuffleTest {
    private val now = Instant.parse("2026-11-18T12:00:00Z")
    private val window = 72.hours

    private fun track(
        artist: String,
        playedHoursAgo: Long? = null,
    ) = ShuffleTrack(artist, playedHoursAgo?.let { now.minusSeconds(it * 3600) })

    private fun shuffle(
        tracks: List<ShuffleTrack>,
        seed: Int,
        previous: String? = null,
    ) = smartShuffle(tracks, now, window, Random(seed), previous)

    private fun assertNoRepeat(
        tracks: List<ShuffleTrack>,
        order: List<Int>,
        message: String = "",
    ) {
        for (i in 1 until order.size) {
            assertNotEquals("$message: artist repeated at position $i", tracks[order[i]].artistKey, tracks[order[i - 1]].artistKey)
        }
    }

    @Test
    fun `returns every track exactly once`() {
        val tracks = List(500) { track("a${it % 37}") }
        assertEquals((0 until 500).toList(), shuffle(tracks, 1).sorted())
    }

    @Test
    fun `empty and single`() {
        assertEquals(emptyList<Int>(), shuffle(emptyList(), 1))
        assertEquals(listOf(0), shuffle(listOf(track("a")), 1))
    }

    @Test
    fun `never repeats an artist when an order without repeats exists`() {
        val random = Random(42)
        for (round in 0 until 400) {
            val n = random.nextInt(1, 120)
            val artistCount = random.nextInt(1, 12)
            val dominant = if (round % 4 == 0) (n + 1) / 2 else 0
            val tracks = List(n) { i -> track(if (i < dominant) "dominant" else "a${random.nextInt(artistCount)}") }
            if (tracks.groupBy { it.artistKey }.maxOf { it.value.size } > (n + 1) / 2) continue
            assertNoRepeat(tracks, shuffle(tracks, round), "round $round")
        }
    }

    @Test
    fun `keeps repeats minimal when one artist dominates`() {
        // 8 of 10 tracks by one artist: repeats are unavoidable, but all tracks must still be returned
        val tracks = List(10) { track(if (it < 8) "x" else "y$it") }
        val order = shuffle(tracks, 3)
        assertEquals(10, order.toSet().size)
        val repeats = (1 until 10).count { tracks[order[it]].artistKey == tracks[order[it - 1]].artistKey }
        assertEquals(5, repeats)
    }

    @Test
    fun `recently played tracks come last`() {
        val tracks =
            List(300) { i ->
                when {
                    i < 100 -> track("a${i % 50}")
                    i < 200 -> track("a${i % 50}", 30 * 24L)
                    else -> track("a${i % 50}", (i - 199).toLong())
                }
            }
        val recent =
            tracks.indices
                .filter {
                    tracks[it].lastPlayed != null &&
                        tracks[it].lastPlayed!!.isAfter(
                            now.minusSeconds(72 * 3600),
                        )
                }.toSet()
        assertEquals(71, recent.size)
        val newerHalf = recent.sortedBy { tracks[it].lastPlayed }.drop(recent.size - recent.size / 2).toSet()
        val perArtist = 6
        for (seed in 0 until 50) {
            val order = shuffle(tracks, seed)
            // A recently played track may only move forward to separate the last few tracks of one artist
            assertTrue(order.take(300 - recent.size - perArtist).none { it in recent })
            assertTrue(order.drop(300 - (newerHalf.size - perArtist)).all { it in newerHalf })
            assertNoRepeat(tracks, order)
        }
    }

    @Test
    fun `artist rule wins over recency`() {
        // 5 tracks each of two artists; 4 of A's are recent. Strict alternation is the only order without repeats
        val tracks = (0 until 5).flatMap { listOf(track("a", if (it < 4) 1L + it else null), track("b")) }
        for (seed in 0 until 100) assertNoRepeat(tracks, shuffle(tracks, seed))
    }

    @Test
    fun `no repeat with mixed history`() {
        val random = Random(5)
        for (round in 0 until 300) {
            val n = random.nextInt(2, 80)
            val tracks = List(n) { track("a${random.nextInt(6)}", if (random.nextBoolean()) null else random.nextInt(1, 70).toLong()) }
            if (tracks.groupBy { it.artistKey }.maxOf { it.value.size } > (n + 1) / 2) continue
            val order = shuffle(tracks, round)
            assertEquals(n, order.toSet().size)
            assertNoRepeat(tracks, order)
        }
    }

    @Test
    fun `does not start with the previous artist`() {
        val tracks = List(60) { track("a${it % 5}", if (it % 3 == 0) it + 1L else null) }
        for (seed in 0 until 200) {
            val order = shuffle(tracks, seed, "a3")
            assertNotEquals("a3", tracks[order[0]].artistKey)
            assertNoRepeat(tracks, order)
        }
        // An artist that is not in the list, or the only artist, must not break anything
        assertEquals(60, shuffle(tracks, 1, "unknown").size)
        assertEquals(2, shuffle(listOf(track("x"), track("x")), 1, "x").size)
    }

    @Test
    fun `is random and the same seed gives the same order`() {
        val tracks = List(200) { track("a${it % 40}") }
        assertNotEquals(shuffle(tracks, 1), shuffle(tracks, 2))
        assertEquals(shuffle(tracks, 7), shuffle(tracks, 7))
    }

    @Test
    fun `handles large lists`() {
        val random = Random(9)
        val tracks = List(5000) { track("a${random.nextInt(900)}") }
        val start = System.currentTimeMillis()
        val order = shuffle(tracks, 1)
        assertEquals(5000, order.size)
        assertTrue("took ${System.currentTimeMillis() - start} ms", System.currentTimeMillis() - start < 2000)
    }

    // artist key and light song

    private fun song(
        artists: List<String>? = null,
        artistItems: List<String>? = null,
        albumArtist: String? = null,
        lastPlayed: LocalDateTime? = null,
        id: UUID = UUID.randomUUID(),
    ) = BaseItemDto(
        id = id,
        type = BaseItemKind.AUDIO,
        artists = artists,
        artistItems = artistItems?.map { NameGuidPair(it, UUID.randomUUID()) },
        albumArtist = albumArtist,
        userData =
            lastPlayed?.let {
                UserItemDataDto(
                    playbackPositionTicks = 0,
                    playCount = 1,
                    isFavorite = false,
                    played = true,
                    key = "k",
                    lastPlayedDate = it,
                    itemId = id,
                )
            },
    )

    @Test
    fun `artist key is first artist then album artist`() {
        assertEquals("first", artistKeyOf(song(artistItems = listOf("First", "Second"), albumArtist = "Album")))
        assertEquals("first", artistKeyOf(song(artists = listOf(" First "), albumArtist = "Album")))
        assertEquals("album", artistKeyOf(song(albumArtist = "Album")))
    }

    @Test
    fun `tracks without artist never block each other`() {
        val songs = List(6) { song() }
        val keys = songs.map { artistKeyOf(it) }
        assertEquals(6, keys.toSet().size)
        val tracks = songs.map { it.toLightSong().toTrack() }
        for (seed in 0 until 20) assertNoRepeat(tracks, shuffle(tracks, seed))
    }

    @Test
    fun `light song reads last played date`() {
        val played = LocalDateTime.of(2026, 11, 17, 8, 0)
        assertEquals(Instant.parse("2026-11-17T08:00:00Z"), song(albumArtist = "a", lastPlayed = played).toLightSong().lastPlayed)
        assertEquals(null, song(albumArtist = "a").toLightSong().lastPlayed)
    }

    // order with exclusions

    private fun light(
        id: UUID,
        artist: String,
    ) = LightSong(id, artist, null)

    @Test
    fun `smart order leaves out excluded songs before ordering`() {
        // a1 b1 a2 b2: with b1 and b2 excluded only a-songs are left; none is dropped silently
        val a1 = light(UUID.randomUUID(), "a")
        val a2 = light(UUID.randomUUID(), "a")
        val b1 = light(UUID.randomUUID(), "b")
        val b2 = light(UUID.randomUUID(), "b")
        val set = DislikeSet(setOf(b1.id), setOf(b1.id, b2.id))
        val full = smartOrder(listOf(a1, b1, a2, b2), EntryRule.FULL, set, now, Random(1)) { it }
        assertEquals(setOf(a1, a2), full.toSet())
        val itemsOnly = smartOrder(listOf(a1, b1, a2, b2), EntryRule.ITEMS_ONLY, set, now, Random(1)) { it }
        assertEquals(setOf(a1, a2, b2), itemsOnly.toSet())
        val exempt = smartOrder(listOf(a1, b1, a2, b2), EntryRule.EXEMPT, set, now, Random(1)) { it }
        assertEquals(4, exempt.size)
        assertNoRepeat(exempt.map { it.toTrack() }, exempt.indices.toList())
    }

    @Test
    fun `smart order starts with another artist than the previous one`() {
        val songs = List(20) { light(UUID.randomUUID(), "a${it % 4}") }
        for (seed in 0 until 50) {
            val ordered = smartOrder(songs, EntryRule.EXEMPT, DislikeSet.EMPTY, now, Random(seed), "a2") { it }
            assertNotEquals("a2", ordered.first().artistKey)
        }
    }
}
