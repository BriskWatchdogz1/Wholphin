package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.OutboundWebSocketMessage
import org.jellyfin.sdk.model.api.PlayMessage
import org.jellyfin.sdk.model.api.PlaystateMessage
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal val CAPABILITY_RETRY_DELAYS: List<Duration> = listOf(2.seconds, 5.seconds, 15.seconds, 60.seconds)

/** True while the socket is connected; changes only */
internal fun Flow<SocketApiState>.isConnected(): Flow<Boolean> = map { it is SocketApiState.Connected }.distinctUntilChanged()

/** Emits once for every time the socket becomes connected, also when it already is at the start */
internal fun Flow<SocketApiState>.connections(): Flow<Unit> = isConnected().filter { it }.map { }

/** Who the session belongs to; a change means a new session. Never logged. */
internal data class SessionKey(
    val serverId: UUID,
    val userId: UUID,
    val accessToken: String?,
)

/**
 * Keeps the Jellyfin session of the signed-in user controllable while the process runs, independent of any activity:
 * it holds a websocket subscription (this keeps the socket open), posts the capabilities every time the socket
 * connects (the server forgets them when it restarts) and hands play commands to [RemotePlayService].
 */
@Singleton
class RemoteSessionService
    @Inject
    constructor(
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val remotePlayService: RemotePlayService,
        @param:DefaultCoroutineScope private val scope: CoroutineScope,
    ) {
        private val started = AtomicBoolean(false)

        /** Follows the signed-in user from now on; later calls do nothing */
        fun start() {
            if (!started.compareAndSet(false, true)) return
            scope.launch {
                serverRepository.current
                    .map { current ->
                        current?.let { SessionKey(it.server.id, it.user.id, it.user.accessToken) }
                    }.distinctUntilChanged()
                    .collectLatest { if (it != null) listen() }
            }
        }

        /** One attempt. False on any failure or without base url / access token; throws only CancellationException */
        suspend fun postCapabilities(): Boolean {
            if (api.baseUrl == null || api.accessToken == null) return false
            return try {
                sendCapabilities()
                true
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.i(ex, "RemoteSession: capabilities failed")
                false
            }
        }

        /** The HTTP call alone, stubbed in tests */
        internal suspend fun sendCapabilities() {
            api.sessionApi.postCapabilities(
                playableMediaTypes = listOf(MediaType.VIDEO, MediaType.AUDIO),
                supportedCommands =
                    listOf(
                        GeneralCommandType.DISPLAY_MESSAGE,
                        GeneralCommandType.SEND_STRING,
                    ),
                supportsMediaControl = true,
            )
        }

        /** postCapabilities, then one more attempt after each of CAPABILITY_RETRY_DELAYS until one succeeds */
        internal suspend fun postCapabilitiesWithRetry(): Boolean {
            var attempt = 1
            if (postCapabilities()) {
                Timber.i("RemoteSession: capabilities posted")
                return true
            }
            for (wait in CAPABILITY_RETRY_DELAYS) {
                Timber.i("RemoteSession: capabilities failed, attempt %d", attempt)
                delay(wait)
                attempt++
                if (postCapabilities()) {
                    Timber.i("RemoteSession: capabilities posted")
                    return true
                }
            }
            Timber.i("RemoteSession: capabilities given up after %d attempts", attempt)
            return false
        }

        /** Runs until cancelled: the work for one signed-in user */
        internal suspend fun listen() {
            Timber.i("RemoteSession: listening")
            coroutineScope {
                launch {
                    // A drop emits false and cancels a running retry; the next connect starts over
                    api.webSocket.state.isConnected().collectLatest { connected ->
                        if (connected) postCapabilitiesWithRetry()
                    }
                }
                launch {
                    api.webSocket.state.collect { state ->
                        val text =
                            if (state is SocketApiState.Connected) {
                                "connected"
                            } else if (state is SocketApiState.Disconnected) {
                                if (state.error != null) "disconnected (error)" else "disconnected (closed)"
                            } else {
                                "connecting"
                            }
                        Timber.i("RemoteSession: socket %s", text)
                    }
                }
                launch {
                    // One collector for both kinds: the server writes a play next and the next track that follows
                    // it in this order, and two collectors would not keep it
                    api.webSocket.subscribeAll().collect { dispatch(it) }
                }
            }
        }

        /** Handles one socket message and returns when its first step is done; others are ignored */
        internal suspend fun dispatch(message: OutboundWebSocketMessage) {
            try {
                when (message) {
                    is PlayMessage -> {
                        message.data?.let { remotePlayService.process(it) }
                    }

                    is PlaystateMessage -> {
                        message.data?.let { remotePlayService.processPlaystate(it) }
                    }

                    else -> {}
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.i(ex, "RemoteSession: remote command failed")
            }
        }
    }
