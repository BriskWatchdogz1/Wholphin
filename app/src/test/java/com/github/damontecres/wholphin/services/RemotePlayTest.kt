package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import org.junit.Assert.assertNull
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
        coEvery { musicService.ruleFor(any(), any(), any()) } returns EntryRule.FULL
        coEvery { musicService.playQueue(any(), any(), any()) } answers { newKeys(firstArg<List<BaseItem>>().size) }
        coEvery { musicService.insertIntoQueue(any(), any()) } answers { newKeys(firstArg<List<BaseItem>>().size) }
        coEvery { musicService.insertNextTo(any(), any(), any()) } answers {
            val call = NextTo(firstArg(), secondArg(), thirdArg<List<BaseItem>>().map { it.id })
            nextTos.add(call)
            if (nextToFails) null else newKeys(call.ids.size)
        }
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

    // reportedQueueRange

    @Test
    fun `reported queue range follows the current entry`() {
        assertEquals(0 until 50, reportedQueueRange(50, 30))
        assertEquals(490 until 690, reportedQueueRange(1000, 500))
        assertEquals(0 until 200, reportedQueueRange(1000, 3))
        assertEquals(800 until 1000, reportedQueueRange(1000, 995))
        assertEquals(0 until 200, reportedQueueRange(1000, -1))
        assertTrue(reportedQueueRange(0, 0).isEmpty())
    }

    // planRemoteLoad

    @Test
    fun `plan splits at the start index`() {
        val plan = planRemoteLoad(List(250) { UUID.randomUUID() }, 120)
        assertEquals(listOf(100, 30), plan.fromStart.map { it.size })
        assertEquals(listOf(100, 20), plan.beforeStart.map { it.size })
    }

    @Test
    fun `plan without a valid start index loads everything from the start`() {
        val idList = List(250) { UUID.randomUUID() }
        listOf(null, -1, 250, 900).forEach {
            val plan = planRemoteLoad(idList, it)
            assertEquals(listOf(100, 100, 50), plan.fromStart.map { chunk -> chunk.size })
            assertTrue(plan.beforeStart.isEmpty())
        }
    }

    // matchQueueWindow

    private fun uuids(count: Int) = List(count) { UUID.randomUUID() }

    @Test
    fun `an unchanged window is a jump`() {
        val queue = uuids(1000)
        assertEquals(WindowMatch.Jump(490), matchQueueWindow(queue, queue.subList(490, 690), 500))
    }

    @Test
    fun `a window that shifted since it was reported is still found`() {
        val queue = uuids(1000)
        assertEquals(WindowMatch.Jump(490), matchQueueWindow(queue, queue.subList(490, 690), 502))
    }

    @Test
    fun `a short queue is matched as a whole`() {
        val queue = uuids(30)
        assertEquals(WindowMatch.Jump(0), matchQueueWindow(queue, queue, 4))
    }

    @Test
    fun `one entry moved forward is a move`() {
        val queue = uuids(1000)
        val window = queue.subList(490, 690).toMutableList()
        window.add(20, window.removeAt(5))
        assertEquals(WindowMatch.Move(490, 5, 20), matchQueueWindow(queue, window, 500))
    }

    @Test
    fun `one entry moved backward is a move`() {
        val queue = uuids(1000)
        val window = queue.subList(490, 690).toMutableList()
        window.add(5, window.removeAt(20))
        assertEquals(WindowMatch.Move(490, 20, 5), matchQueueWindow(queue, window, 500))
    }

    @Test
    fun `a different list is not the window`() {
        val queue = uuids(1000)
        assertNull(matchQueueWindow(queue, uuids(200), 500))
        assertNull(matchQueueWindow(queue, queue.subList(490, 690).reversed(), 500))
    }

    @Test
    fun `a short list from a large queue is not the window`() {
        val queue = uuids(1000)
        assertNull(matchQueueWindow(queue, queue.subList(500, 512), 500))
    }

    @Test
    fun `a window found twice resolves to the one nearer the current entry`() {
        val window = uuids(200)
        val queue = window + uuids(50) + window
        assertEquals(WindowMatch.Jump(0), matchQueueWindow(queue, window, 10))
        assertEquals(WindowMatch.Jump(250), matchQueueWindow(queue, window, 270))
    }

    // RemotePlayService.execute

    private val catalog = mutableMapOf<UUID, BaseItem>()
    private val blockedIds = mutableSetOf<UUID>()
    private var cancelledWhileFetching = false
    private var keyCounter = 1L
    private var nextToFails = false
    private val nextTos = mutableListOf<NextTo>()

    private data class NextTo(
        val anchor: Long,
        val before: Boolean,
        val ids: List<UUID>,
    )

    private fun newKeys(count: Int) = List(count) { keyCounter++ }

    private fun songs(
        count: Int,
        kind: BaseItemKind = BaseItemKind.AUDIO,
    ) = List(count) { item(kind) }.onEach { catalog[it.id] = it }

    private fun request(
        command: PlayCommand,
        items: List<BaseItem>,
        startIndex: Int? = null,
        startPositionTicks: Long? = null,
    ) = PlayRequest(
        itemIds = items.map { it.id },
        playCommand = command,
        startIndex = startIndex,
        startPositionTicks = startPositionTicks,
        controllingUserId = UUID.randomUUID(),
    )

    private fun request(
        command: PlayCommand,
        vararg items: BaseItem,
    ) = request(command, items.toList())

    private fun runService(block: suspend TestScope.(RemotePlayService) -> Unit) =
        runTest(testDispatcher) {
            val service = spyk(RemotePlayService(mockk<ApiClient>(relaxed = true), musicService, navigationManager, this))
            coEvery { service.fetchItems(any()) } coAnswers {
                val ids = firstArg<List<UUID>>()
                if (ids.any { it in blockedIds }) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelledWhileFetching = true
                    }
                }
                ids.mapNotNull { catalog[it] }
            }
            block(service)
        }

    private fun runCommand(
        request: PlayRequest,
        block: suspend TestScope.(RemotePlayService) -> Unit = {},
    ) = runService { service ->
        service.execute(request)
        advanceUntilIdle()
        block(service)
    }

    private fun idsOf(
        items: List<BaseItem>,
        range: IntRange,
    ) = items.slice(range).map { it.id }

    @Test
    fun `play now replaces the queue and opens now playing`() {
        val (a1, a2) = songs(2)
        runCommand(request(PlayCommand.PLAY_NOW, a1, a2)) {
            coVerify { musicService.playQueue(listOf(a1, a2), 0, 0) }
            assertTrue(navigationManager.backStack.last() is Destination.NowPlaying)
        }
    }

    @Test
    fun `play now does not stack a second now playing page`() {
        navigationManager.backStack = mutableListOf(Destination.Home(), Destination.NowPlaying)
        val a1 = songs(1).single()
        runCommand(request(PlayCommand.PLAY_NOW, a1)) {
            assertEquals(2, navigationManager.backStack.size)
        }
    }

    @Test
    fun `play next inserts after the current song`() {
        musicState.value = MusicServiceState.EMPTY.copy(queueSize = 5, currentIndex = 2)
        val a1 = songs(1).single()
        runCommand(request(PlayCommand.PLAY_NEXT, a1)) {
            coVerify { musicService.insertIntoQueue(listOf(a1), 3) }
            coVerify(exactly = 0) { musicService.playQueue(any(), any(), any()) }
            assertEquals(1, navigationManager.backStack.size)
        }
    }

    @Test
    fun `play last appends`() {
        musicState.value = MusicServiceState.EMPTY.copy(queueSize = 5, currentIndex = 2)
        val a1 = songs(1).single()
        runCommand(request(PlayCommand.PLAY_LAST, a1)) {
            coVerify { musicService.insertIntoQueue(listOf(a1), null) }
            coVerify(exactly = 0) { musicService.playQueue(any(), any(), any()) }
        }
    }

    @Test
    fun `play next on an empty queue starts playback`() {
        val a1 = songs(1).single()
        runCommand(request(PlayCommand.PLAY_NEXT, a1)) {
            coVerify { musicService.playQueue(listOf(a1), 0, 0) }
            coVerify(exactly = 0) { musicService.insertIntoQueue(any(), any()) }
        }
    }

    @Test
    fun `a request without songs changes nothing`() {
        val v = songs(1, BaseItemKind.MOVIE).single()
        runCommand(request(PlayCommand.PLAY_NOW, v)) {
            coVerify(exactly = 0) { musicService.playQueue(any(), any(), any()) }
            coVerify(exactly = 0) { musicService.insertIntoQueue(any(), any()) }
            assertEquals(1, navigationManager.backStack.size)
        }
    }

    @Test
    fun `a large play now starts with the start index and loads the rest in chunks`() {
        val all = songs(250)
        runCommand(request(PlayCommand.PLAY_NOW, all, startIndex = 120, startPositionTicks = 1_200_000_000)) {
            coVerify(exactly = 1) { musicService.playQueue(all.slice(120..219), 0, 120_000) }
            // playQueue created keys 1..100
            assertEquals(
                listOf(
                    NextTo(100, false, idsOf(all, 220..249)),
                    NextTo(1, true, idsOf(all, 0..99)),
                    NextTo(1, true, idsOf(all, 100..119)),
                ),
                nextTos,
            )
        }
    }

    @Test
    fun `loading stops when the queue was replaced`() {
        val all = songs(250)
        nextToFails = true
        runCommand(request(PlayCommand.PLAY_NOW, all)) { service ->
            assertEquals(1, nextTos.size)
            // the first chunk and the one that could not be inserted
            coVerify(exactly = 2) { service.fetchItems(any()) }
        }
    }

    @Test
    fun `playback starts with the first chunk that has songs`() {
        val videos = songs(100, BaseItemKind.MOVIE)
        val audio = songs(50)
        runCommand(request(PlayCommand.PLAY_NOW, videos + audio)) {
            coVerify(exactly = 1) { musicService.playQueue(audio, 0, 0) }
            assertTrue(nextTos.isEmpty())
        }
    }

    @Test
    fun `a large play last appends the first chunk and loads the rest behind it`() {
        musicState.value = MusicServiceState.EMPTY.copy(queueSize = 5, currentIndex = 2)
        val all = songs(250)
        runCommand(request(PlayCommand.PLAY_LAST, all)) {
            coVerify(exactly = 1) { musicService.insertIntoQueue(all.slice(0..99), null) }
            // the keys of the first chunk are 1..100, those of the second 101..200
            assertEquals(
                listOf(NextTo(100, false, idsOf(all, 100..199)), NextTo(200, false, idsOf(all, 200..249))),
                nextTos,
            )
        }
    }

    @Test
    fun `a large play next inserts the first chunk after the current song and loads the rest behind it`() {
        musicState.value = MusicServiceState.EMPTY.copy(queueSize = 5, currentIndex = 2)
        val all = songs(250)
        runCommand(request(PlayCommand.PLAY_NEXT, all, startIndex = 200)) {
            coVerify(exactly = 1) { musicService.insertIntoQueue(all.slice(0..99), 3) }
            assertEquals(
                listOf(NextTo(100, false, idsOf(all, 100..199)), NextTo(200, false, idsOf(all, 200..249))),
                nextTos,
            )
        }
    }

    @Test
    fun `a new play now cancels the loading of the previous one`() {
        val first = songs(250)
        val second = songs(1)
        blockedIds.add(first[100].id)
        runService { service ->
            service.execute(request(PlayCommand.PLAY_NOW, first))
            advanceUntilIdle()
            assertTrue(!cancelledWhileFetching)
            service.execute(request(PlayCommand.PLAY_NOW, second))
            advanceUntilIdle()
            assertTrue(cancelledWhileFetching)
            assertTrue(nextTos.isEmpty())
            coVerify(exactly = 1) { musicService.playQueue(second, 0, 0) }
        }
    }

    // a queue of 1000 songs of which the remote knows the window 490 until 690

    private fun largeQueue(): List<UUID> {
        val queue = uuids(1000)
        musicState.value = MusicServiceState.EMPTY.copy(queueSize = 1000, currentIndex = 500)
        coEvery { musicService.queueSnapshot() } returns (queue to 500)
        return queue
    }

    private fun idsRequest(
        ids: List<UUID>,
        startIndex: Int?,
    ) = PlayRequest(
        itemIds = ids,
        playCommand = PlayCommand.PLAY_NOW,
        startIndex = startIndex,
        controllingUserId = UUID.randomUUID(),
    )

    @Test
    fun `play now with the reported window jumps within the queue`() {
        val queue = largeQueue()
        runCommand(idsRequest(queue.subList(490, 690), 15)) {
            coVerify(exactly = 1) { musicService.playIndex(505) }
            coVerify(exactly = 0) { musicService.playQueue(any(), any(), any()) }
            coVerify(exactly = 0) { musicService.moveQueue(any<Int>(), any<Int>()) }
        }
    }

    @Test
    fun `play now with a moved window moves the entry in the queue`() {
        val queue = largeQueue()
        val window = queue.subList(490, 690).toMutableList()
        window.add(20, window.removeAt(5))
        runCommand(idsRequest(window, 0)) {
            coVerify(exactly = 1) { musicService.moveQueue(495, 510) }
            coVerify(exactly = 0) { musicService.playQueue(any(), any(), any()) }
            coVerify(exactly = 0) { musicService.playIndex(any()) }
        }
    }

    @Test
    fun `play now with other songs replaces a large queue`() {
        largeQueue()
        val album = songs(12)
        runCommand(request(PlayCommand.PLAY_NOW, album)) {
            coVerify(exactly = 1) { musicService.playQueue(album, 0, 0) }
            coVerify(exactly = 0) { musicService.playIndex(any()) }
        }
    }
}
