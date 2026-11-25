package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The songs the user disliked, kept in memory for the current user and server
 *
 * One source: `GET /Dislike/Items?includeExcluded=true` of the Dislike server plugin, called as the user.
 * The set is fetched when a queue starts, periodically while music plays and after the app changes a rating;
 * a failed request keeps the last set. Without the plugin (404) the songs the app has loaded with their own
 * user data `likes == false` stand in, see [observe].
 *
 * TODO: the plugin cuts the lists silently at its maximum (5000 by default). Switch to
 *  `/Plugins/Harmonie/Exclusions` once the server offers it uncapped.
 */
@Singleton
class DislikeService
    @Inject
    constructor(
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
    ) {
        private val _set = MutableStateFlow(DislikeSet.EMPTY)
        val set: StateFlow<DislikeSet> = _set

        private var owner: Pair<UUID?, UUID?>? = null
        private var pluginMissing = false

        // Marks the app set itself; the server may not show them yet when the set is fetched
        private val marks = HashMap<UUID, Pair<Boolean, Long>>()

        private fun checkOwner() {
            val current = serverRepository.currentServer?.id to serverRepository.currentUser?.id
            if (current != owner) {
                owner = current
                pluginMissing = false
                synchronized(marks) { marks.clear() }
                _set.value = DislikeSet.EMPTY
            }
        }

        /**
         * Fetch the set again. A failed request keeps the last set.
         */
        suspend fun refresh() {
            checkOwner()
            try {
                val response =
                    api.request(
                        HttpMethod.GET,
                        "/Dislike/Items",
                        queryParameters = mapOf("includeExcluded" to true),
                    )
                if (response.status == 404) {
                    markPluginMissing()
                    return
                }
                val fetched = parseDislikedItems(String(response.body, Charsets.UTF_8))
                if (fetched == null) {
                    Timber.w("DislikeService: unreadable answer, kept %d songs", _set.value.itemIds.size)
                    return
                }
                pluginMissing = false
                _set.value = fetched.withMarks(recentMarks())
                Timber.i("DislikeService: %d disliked, %d excluded", fetched.itemIds.size, fetched.excludedIds.size)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: InvalidStatusException) {
                if (ex.status == 404) {
                    markPluginMissing()
                } else {
                    Timber.w(ex, "DislikeService: request failed, kept the last set")
                }
            } catch (ex: Exception) {
                Timber.w(ex, "DislikeService: request failed, kept the last set")
            }
        }

        private fun markPluginMissing() {
            if (!pluginMissing) Timber.i("DislikeService: no Dislike plugin, using the user data of loaded songs")
            pluginMissing = true
        }

        /**
         * Songs that were loaded for the queue: without the plugin their own `likes == false` counts
         */
        fun observe(items: List<BaseItem>) {
            if (!pluginMissing) return
            val disliked = items.filter { it.data.userData?.likes == false }.map { it.id }
            if (disliked.isNotEmpty()) _set.value = disliked.fold(_set.value) { acc, id -> acc + id }
        }

        /**
         * Put a song into or take it out of the set at once, after the app changed its rating
         */
        fun mark(
            id: UUID,
            disliked: Boolean,
        ) {
            synchronized(marks) { marks[id] = disliked to System.currentTimeMillis() }
            _set.value = if (disliked) _set.value + id else _set.value - id
        }

        // Songs the app is rating itself, noted before the request: the server's message can beat the answer
        private val ownRatings = HashMap<UUID, Long>()

        /** Note that the app is about to rate [id]; the echo of that change from the server must not skip again */
        fun noteOwnRating(id: UUID) {
            synchronized(ownRatings) { ownRatings[id] = System.currentTimeMillis() }
        }

        /** Songs the app rated itself within [OWN_RATING_MS] */
        fun recentOwnRatings(): Set<UUID> {
            val limit = System.currentTimeMillis() - OWN_RATING_MS
            return synchronized(ownRatings) {
                ownRatings.entries.removeAll { it.value < limit }
                ownRatings.keys.toSet()
            }
        }

        private fun recentMarks(): Map<UUID, Boolean> {
            val limit = System.currentTimeMillis() - MARK_KEEP_MS
            return synchronized(marks) {
                marks.entries.removeAll { it.value.second < limit }
                marks.mapValues { it.value.first }
            }
        }

        companion object {
            /** The server caches its snapshot for 300 s, local marks stay at least that long */
            const val MARK_KEEP_MS = 5 * 60 * 1000L + 30_000L

            /** How long the echo of the app's own rating is ignored */
            const val OWN_RATING_MS = 15_000L

            /** How often the set is fetched while music plays */
            const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L
        }
    }
