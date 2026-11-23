package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.acra.ACRA
import org.acra.log.ACRALog
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlayRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RemotePlayTest {
    private val testDispatcher = StandardTestDispatcher()
    private val musicState = MutableStateFlow(MusicServiceState.EMPTY)
    private val musicService = mockk<MusicService>(relaxed = true)
    private val navigationManager = NavigationManager()
    private lateinit var acraLog: ACRALog

    @Before
    fun setUp() {
        WholphinDispatchers.configure(testDispatcher)
        every { musicService.state } returns musicState
        navigationManager.backStack = mutableListOf(Destination.Home())
        // NavigationManager reports the destination to ACRA, which logs a warning through android.util.Log when it is not initialized, as in unit tests
        acraLog = ACRA.log
        ACRA.log = mockk<ACRALog>(relaxed = true)
    }

    @After
    fun tearDown() {
        ACRA.log = acraLog
        WholphinDispatchers.reset()
    }

    private fun item(
        kind: BaseItemKind = BaseItemKind.AUDIO,
        id: UUID = UUID.randomUUID(),
    ) = BaseItem(BaseItemDto(id = id, type = kind), false)

    private fun ids(queue: RemoteQueue) = queue.items.map { it.id }

    // buildRemoteQueue

    @Test
    fun `queue follows the server order, not the fetch order`() {
        val (a1, a2, a3) = List(3) { item() }
        val queue = buildRemoteQueue(listOf(a1.id, a2.id, a3.id), listOf(a3, a1, a2), null, null)
        assertEquals(listOf(a1.id, a2.id, a3.id), ids(queue))
        assertEquals(0, queue.startIndex)
    }

    @Test
    fun `non-audio and missing items are dropped`() {
        val v = item(BaseItemKind.MOVIE)
        val (a1, a2) = List(2) { item() }
        val x = UUID.randomUUID()
        val queue = buildRemoteQueue(listOf(v.id, a1.id, x, a2.id), listOf(v, a1, a2), null, null)
        assertEquals(listOf(a1.id, a2.id), ids(queue))
    }

    @Test
    fun `start index maps to the same song after dropping`() {
        val v = item(BaseItemKind.MOVIE)
        val (a1, a2, a3) = List(3) { item() }
        val queue = buildRemoteQueue(listOf(v.id, a1.id, a2.id, a3.id), listOf(v, a1, a2, a3), 2, null)
        assertEquals(1, queue.startIndex)
    }

    @Test
    fun `start index on a dropped item moves to the next song and drops the position`() {
        val v = item(BaseItemKind.MOVIE)
        val (a1, a2) = List(2) { item() }
        val queue = buildRemoteQueue(listOf(a1.id, v.id, a2.id), listOf(a1, v, a2), 1, 1_200_000_000)
        assertEquals(1, queue.startIndex)
        assertEquals(0, queue.startPositionMs)
    }

    @Test
    fun `start index out of range starts at the first song`() {
        val (a1, a2) = List(2) { item() }
        val idList = listOf(a1.id, a2.id)
        listOf(7, -1, null).forEach {
            assertEquals(0, buildRemoteQueue(idList, listOf(a1, a2), it, null).startIndex)
        }
    }

    @Test
    fun `start position applies to the requested song`() {
        val (a1, a2) = List(2) { item() }
        val queue = buildRemoteQueue(listOf(a1.id, a2.id), listOf(a1, a2), 1, 1_200_000_000)
        assertEquals(1, queue.startIndex)
        assertEquals(120_000, queue.startPositionMs)
    }

    @Test
    fun `no songs gives an empty queue`() {
        val v = item(BaseItemKind.MOVIE)
        val queue = buildRemoteQueue(listOf(v.id), listOf(v), null, null)
        assertTrue(queue.items.isEmpty())
        assertEquals(0, queue.startIndex)
        assertEquals(0, queue.startPositionMs)
    }

    // RemotePlayService.execute

    private fun request(
        command: PlayCommand,
        vararg items: BaseItem,
    ) = PlayRequest(itemIds = items.map { it.id }, playCommand = command, controllingUserId = UUID.randomUUID())

    private fun runService(
        request: PlayRequest,
        fetched: List<BaseItem>,
        block: suspend () -> Unit = {},
    ) = runTest(testDispatcher) {
        RemotePlayService(mockk<ApiClient>(relaxed = true), musicService, navigationManager, this)
            .execute(request, fetched)
        advanceUntilIdle()
        block()
    }

    @Test
    fun `play now replaces the queue and opens now playing`() {
        val (a1, a2) = List(2) { item() }
        runService(request(PlayCommand.PLAY_NOW, a1, a2), listOf(a1, a2)) {
            coVerify { musicService.playQueue(listOf(a1, a2), 0, 0) }
            assertTrue(navigationManager.backStack.last() is Destination.NowPlaying)
        }
    }

    @Test
    fun `play now does not stack a second now playing page`() {
        navigationManager.backStack = mutableListOf(Destination.Home(), Destination.NowPlaying)
        val a1 = item()
        runService(request(PlayCommand.PLAY_NOW, a1), listOf(a1)) {
            assertEquals(2, navigationManager.backStack.size)
        }
    }

    @Test
    fun `play next inserts after the current song`() {
        musicState.value = MusicServiceState.EMPTY.copy(queueSize = 5, currentIndex = 2)
        val a1 = item()
        runService(request(PlayCommand.PLAY_NEXT, a1), listOf(a1)) {
            coVerify { musicService.insertIntoQueue(listOf(a1), 3) }
            coVerify(exactly = 0) { musicService.playQueue(any(), any(), any()) }
            assertEquals(1, navigationManager.backStack.size)
        }
    }

    @Test
    fun `play last appends`() {
        musicState.value = MusicServiceState.EMPTY.copy(queueSize = 5, currentIndex = 2)
        val a1 = item()
        runService(request(PlayCommand.PLAY_LAST, a1), listOf(a1)) {
            coVerify { musicService.insertIntoQueue(listOf(a1), null) }
            coVerify(exactly = 0) { musicService.playQueue(any(), any(), any()) }
        }
    }

    @Test
    fun `play next on an empty queue starts playback`() {
        val a1 = item()
        runService(request(PlayCommand.PLAY_NEXT, a1), listOf(a1)) {
            coVerify { musicService.playQueue(listOf(a1), 0, 0) }
            coVerify(exactly = 0) { musicService.insertIntoQueue(any(), any()) }
        }
    }

    @Test
    fun `a request without songs changes nothing`() {
        val v = item(BaseItemKind.MOVIE)
        runService(request(PlayCommand.PLAY_NOW, v), listOf(v)) {
            coVerify(exactly = 0) { musicService.playQueue(any(), any(), any()) }
            coVerify(exactly = 0) { musicService.insertIntoQueue(any(), any()) }
            assertEquals(1, navigationManager.backStack.size)
        }
    }
}
