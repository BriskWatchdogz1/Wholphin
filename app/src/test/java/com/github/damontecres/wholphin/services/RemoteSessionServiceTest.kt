package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.api.OutboundWebSocketMessage
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlayMessage
import org.jellyfin.sdk.model.api.PlayRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSessionServiceTest {
    private val testDispatcher = StandardTestDispatcher()
    private val socketState = MutableStateFlow<SocketApiState>(SocketApiState.Connected)
    private val playMessages = MutableSharedFlow<OutboundWebSocketMessage>()
    private val current = MutableStateFlow<CurrentUser?>(null)
    private val remotePlay = mockk<RemotePlayService>(relaxed = true)

    @Before
    fun setUp() {
        WholphinDispatchers.configure(testDispatcher)
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    private fun user(): CurrentUser {
        val server = JellyfinServer(UUID.randomUUID(), "s", "http://localhost", null)
        val user = JellyfinUser(id = UUID.randomUUID(), name = "u", serverId = server.id, accessToken = "token")
        return CurrentUser(server, user)
    }

    private fun playRequest() =
        PlayRequest(
            itemIds = listOf(UUID.randomUUID()),
            playCommand = PlayCommand.PLAY_NOW,
            controllingUserId = UUID.randomUUID(),
        )

    private fun message(data: PlayRequest?) = PlayMessage(data = data, messageId = UUID.randomUUID())

    private fun TestScope.service(): RemoteSessionService {
        val socket = mockk<SocketApi>()
        every { socket.state } returns socketState
        every { socket.subscribeAll() } returns playMessages
        val api = mockk<ApiClient>(relaxed = true)
        every { api.webSocket } returns socket
        every { api.baseUrl } returns "http://localhost"
        every { api.accessToken } returns "token"
        val repository = mockk<ServerRepository>()
        every { repository.current } returns current
        val service = spyk(RemoteSessionService(api, repository, remotePlay, backgroundScope))
        coEvery { service.sendCapabilities() } returns Unit
        return service
    }

    @Test
    fun `capabilities are posted when the socket is already connected`() =
        runTest(testDispatcher) {
            val service = service()
            current.value = user()
            service.start()
            advanceTimeBy(1.seconds)
            coVerify(exactly = 1) { service.sendCapabilities() }
        }

    @Test
    fun `capabilities wait for the connection`() =
        runTest(testDispatcher) {
            socketState.value = SocketApiState.Connecting
            val service = service()
            current.value = user()
            service.start()
            advanceTimeBy(1.seconds)
            coVerify(exactly = 0) { service.sendCapabilities() }
            socketState.value = SocketApiState.Connected
            advanceTimeBy(1.seconds)
            coVerify(exactly = 1) { service.sendCapabilities() }
        }

    @Test
    fun `capabilities are posted again after every reconnect`() =
        runTest(testDispatcher) {
            val service = service()
            current.value = user()
            service.start()
            advanceTimeBy(1.seconds)
            socketState.value = SocketApiState.Disconnected(IOException())
            advanceTimeBy(1.seconds)
            socketState.value = SocketApiState.Connecting
            advanceTimeBy(1.seconds)
            socketState.value = SocketApiState.Connected
            advanceTimeBy(1.seconds)
            coVerify(exactly = 2) { service.sendCapabilities() }
        }

    @Test
    fun `a failed post is retried while the socket stays connected`() =
        runTest(testDispatcher) {
            val service = service()
            var calls = 0
            coEvery { service.sendCapabilities() } answers {
                calls++
                if (calls < 3) throw IOException("down")
            }
            current.value = user()
            service.start()
            advanceTimeBy(1.seconds)
            assertEquals(1, calls)
            advanceTimeBy(2.seconds + 5.seconds)
            assertEquals(3, calls)
            advanceTimeBy(1.hours)
            assertEquals(3, calls)
        }

    @Test
    fun `retries end after the last delay`() =
        runTest(testDispatcher) {
            val service = service()
            var calls = 0
            coEvery { service.sendCapabilities() } answers {
                calls++
                throw IOException("down")
            }
            current.value = user()
            service.start()
            advanceTimeBy(1.hours)
            assertEquals(5, calls)
            advanceTimeBy(1.hours)
            assertEquals(5, calls)
        }

    @Test
    fun `a retry stops when the socket drops`() =
        runTest(testDispatcher) {
            val service = service()
            var calls = 0
            coEvery { service.sendCapabilities() } answers {
                calls++
                throw IOException("down")
            }
            current.value = user()
            service.start()
            advanceTimeBy(1.seconds)
            assertEquals(1, calls)
            socketState.value = SocketApiState.Disconnected(IOException())
            advanceTimeBy(1.hours)
            assertEquals(1, calls)
            socketState.value = SocketApiState.Connected
            advanceTimeBy(1.seconds)
            assertEquals(2, calls)
        }

    @Test
    fun `a play command reaches the remote play service without an activity`() =
        runTest(testDispatcher) {
            val service = service()
            current.value = user()
            service.start()
            advanceTimeBy(1.seconds)
            val request = playRequest()
            playMessages.emit(message(request))
            advanceTimeBy(1.seconds)
            coVerify(exactly = 1) { remotePlay.process(request) }
        }

    @Test
    fun `a message without data or a failing command does not end the subscription`() =
        runTest(testDispatcher) {
            val service = service()
            current.value = user()
            service.start()
            advanceTimeBy(1.seconds)
            val bad = playRequest()
            val good = playRequest()
            coEvery { remotePlay.process(bad) } throws IllegalStateException("boom")
            playMessages.emit(message(null))
            playMessages.emit(message(bad))
            playMessages.emit(message(good))
            advanceTimeBy(1.seconds)
            coVerify(exactly = 1) { remotePlay.process(good) }
            assertEquals(1, playMessages.subscriptionCount.value)
        }

    @Test
    fun `signing out stops listening and the next user listens again`() =
        runTest(testDispatcher) {
            val service = service()
            service.start()
            current.value = user()
            advanceTimeBy(1.seconds)
            assertEquals(1, playMessages.subscriptionCount.value)
            current.value = null
            advanceTimeBy(1.seconds)
            assertEquals(0, playMessages.subscriptionCount.value)
            current.value = user()
            advanceTimeBy(1.seconds)
            assertEquals(1, playMessages.subscriptionCount.value)
            coVerify(exactly = 2) { service.sendCapabilities() }
        }

    @Test
    fun `start twice listens once`() =
        runTest(testDispatcher) {
            val service = service()
            current.value = user()
            service.start()
            service.start()
            advanceTimeBy(1.seconds)
            assertEquals(1, playMessages.subscriptionCount.value)
            coVerify(exactly = 1) { service.sendCapabilities() }
        }

    @Test
    fun `connections emits once per connection`() =
        runTest(testDispatcher) {
            val states =
                flowOf(
                    SocketApiState.Connecting,
                    SocketApiState.Connected,
                    SocketApiState.Connected,
                    SocketApiState.Disconnected(),
                    SocketApiState.Connected,
                )
            assertEquals(2, states.connections().toList().size)
        }
}
