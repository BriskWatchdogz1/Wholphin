package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.acra.ACRA
import org.acra.log.ACRALog
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.OutboundWebSocketMessage
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlayMessage
import org.jellyfin.sdk.model.api.PlayRequest
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.PlaystateMessage
import org.jellyfin.sdk.model.api.PlaystateRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Remote commands take effect in the order the server wrote them to the websocket, also when fetching the songs
 * of a play command is slow
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteCommandOrderTest {
    private val testDispatcher = StandardTestDispatcher()
    private val messages = MutableSharedFlow<OutboundWebSocketMessage>()
    private val events = mutableListOf<String>()
    private val musicState = MutableStateFlow(MusicServiceState.EMPTY.copy(queueSize = 5, currentIndex = 2))
    private val musicService = mockk<MusicService>(relaxed = true)
    private val navigationManager = NavigationManager()
    private val catalog = mutableMapOf<UUID, BaseItem>()
    private lateinit var acraLog: ACRALog

    @Before
    fun setUp() {
        WholphinDispatchers.configure(testDispatcher)
        every { musicService.state } returns musicState
        coEvery { musicService.ruleFor(any(), any(), any()) } returns EntryRule.FULL
        coEvery { musicService.insertIntoQueue(any(), any(), any()) } coAnswers {
            events.add("insert")
            listOf(1L)
        }
        coEvery { musicService.handlePlaystate(any()) } coAnswers {
            events.add(firstArg<PlaystateRequest>().command.toString())
        }
        navigationManager.backStack = mutableListOf(Destination.Home())
        acraLog = ACRA.log
        ACRA.log = mockk<ACRALog>(relaxed = true)
    }

    @After
    fun tearDown() {
        ACRA.log = acraLog
        WholphinDispatchers.reset()
    }

    private fun song(): BaseItem {
        val dto = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.AUDIO)
        return BaseItem(dto, false).also { catalog[it.id] = it }
    }

    private fun playNext() =
        PlayMessage(
            data = PlayRequest(itemIds = listOf(song().id), playCommand = PlayCommand.PLAY_NEXT, controllingUserId = UUID.randomUUID()),
            messageId = UUID.randomUUID(),
        )

    private fun nextTrack() =
        PlaystateMessage(
            data = PlaystateRequest(command = PlaystateCommand.NEXT_TRACK),
            messageId = UUID.randomUUID(),
        )

    /** A running session whose song fetch takes 500 ms */
    private fun TestScope.session() {
        val play = spyk(RemotePlayService(mockk<ApiClient>(relaxed = true), musicService, navigationManager, backgroundScope))
        coEvery { play.fetchItems(any()) } coAnswers {
            delay(500)
            firstArg<List<UUID>>().mapNotNull { catalog[it] }
        }
        val socket = mockk<SocketApi>()
        every { socket.state } returns MutableStateFlow<SocketApiState>(SocketApiState.Connected)
        every { socket.subscribeAll() } returns messages
        val api = mockk<ApiClient>(relaxed = true)
        every { api.webSocket } returns socket
        every { api.baseUrl } returns "http://localhost"
        every { api.accessToken } returns "token"
        val server = JellyfinServer(UUID.randomUUID(), "s", "http://localhost", null)
        val user = JellyfinUser(id = UUID.randomUUID(), name = "u", serverId = server.id, accessToken = "token")
        val repository = mockk<ServerRepository>()
        every { repository.current } returns MutableStateFlow(CurrentUser(server, user))
        val service = spyk(RemoteSessionService(api, repository, play, backgroundScope))
        coEvery { service.sendCapabilities() } returns Unit
        service.start()
    }

    @Test
    fun `a play next with a slow fetch is inserted before the next track that follows it`() =
        runTest(testDispatcher) {
            session()
            advanceTimeBy(1.seconds)
            messages.emit(playNext())
            messages.emit(nextTrack())
            advanceTimeBy(5.seconds)
            assertEquals(listOf("insert", "NextTrack"), events)
        }

    @Test
    fun `2 pairs in a row keep their order`() =
        runTest(testDispatcher) {
            session()
            advanceTimeBy(1.seconds)
            messages.emit(playNext())
            messages.emit(nextTrack())
            messages.emit(playNext())
            messages.emit(nextTrack())
            advanceTimeBy(10.seconds)
            assertEquals(listOf("insert", "NextTrack", "insert", "NextTrack"), events)
        }

    @Test
    fun `a next track alone is handled`() =
        runTest(testDispatcher) {
            session()
            advanceTimeBy(1.seconds)
            messages.emit(nextTrack())
            advanceTimeBy(1.seconds)
            assertEquals(listOf("NextTrack"), events)
        }
}
