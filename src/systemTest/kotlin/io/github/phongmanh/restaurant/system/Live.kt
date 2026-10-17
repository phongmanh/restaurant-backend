package io.github.phongmanh.restaurant.system

import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.ChannelState
import io.ably.lib.rest.Auth
import io.ably.lib.types.ClientOptions
import io.ably.lib.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A screen's live updates (realtime §4, §8): an Ably subscription on the one channel the service's
 * token grants, as the client apps hold it. The token comes from [tokenPath] with [bearer], through
 * the SDK's `authCallback`, so the SDK fetches a new one when it needs to. Each notification is kept
 * with the time it arrived, for FR-005 and FR-011's 2 s.
 */
class Live(
    private val tokenPath: String,
    private val bearer: String,
    private val channelName: String,
) : AutoCloseable {
    /** One notification: realtime §4.2's `seq`, `type` and the changed resource's `id` and `version`. */
    data class Note(
        val seq: Long,
        val type: String,
        val id: String,
        val version: Int?,
        val at: Instant,
    )

    private val notes = CopyOnWriteArrayList<Note>()
    private var ably: AblyRealtime? = null

    /** Connects and waits until the channel is attached, so nothing published after this is missed. */
    fun connect(): Live {
        val options =
            ClientOptions().apply {
                authCallback =
                    Auth.TokenCallback {
                        Auth.TokenRequest.fromJson(
                            Staging.api
                                .expect(OK, "POST", tokenPath, token = bearer)
                                .body
                                .toString(),
                        )
                    }
                logLevel = Log.NONE
            }
        val client = AblyRealtime(options)
        ably = client
        val channel: Channel = client.channels.get(channelName)
        channel.subscribe { message ->
            val data = Json.parseToJsonElement(message.data.toString()).jsonObject
            notes += note(data)
        }
        val deadline = Instant.now().plus(ATTACH_TIMEOUT)
        while (channel.state != ChannelState.attached) {
            check(Instant.now().isBefore(deadline)) { "the Ably channel didn't attach within $ATTACH_TIMEOUT (state ${channel.state})" }
            Thread.sleep(POLL_MS)
        }
        return this
    }

    /** The phone goes offline: the connection closes, and what's published meanwhile isn't delivered. */
    fun disconnect() {
        ably?.close()
        ably = null
    }

    override fun close() = disconnect()

    fun all(): List<Note> = notes.toList()

    /** The first notification matching [match], waiting up to [timeout] for it; null if none came. */
    fun await(
        timeout: Duration = WAIT,
        match: (Note) -> Boolean,
    ): Note? {
        val deadline = Instant.now().plus(timeout)
        while (true) {
            notes.firstOrNull(match)?.let { return it }
            if (Instant.now().isAfter(deadline)) return null
            Thread.sleep(POLL_MS)
        }
    }

    private fun note(data: JsonObject) =
        Note(
            seq = data.str("seq").toLong(),
            type = data.str("type"),
            id = data.str("id"),
            version = data["version"]?.jsonPrimitive?.content?.toInt(),
            at = Instant.now(),
        )

    companion object {
        /** FR-005, FR-011: a change shows within 2 s (NFR-PERF-03's p95). */
        val LIMIT: Duration = Duration.ofSeconds(2)

        /** How long a test waits before it calls a notification missing; longer than [LIMIT], to report the real delay. */
        private val WAIT: Duration = Duration.ofSeconds(10)
        private val ATTACH_TIMEOUT: Duration = Duration.ofSeconds(20)
        private const val POLL_MS = 10L

        fun guest(guest: Guest) = Live("/v1/guest/realtime-token", guest.token, "session.${guest.sessionId}")

        fun staff(
            site: Site,
            token: String,
        ) = Live("/v1/staff/realtime-token", token, "restaurant.${site.id}.staff")

        /** How long after [since] (the write's response) [note] arrived; zero if it beat the response. */
        fun delay(
            since: Instant,
            note: Live.Note,
        ): Duration = Duration.between(since, note.at).coerceAtLeast(Duration.ZERO)
    }
}
