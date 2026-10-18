package io.github.phongmanh.restaurant.system

import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.ChannelState
import io.ably.lib.rest.Auth
import io.ably.lib.types.ClientOptions
import io.ably.lib.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * A screen's live updates, as the client apps hold them. With live push (`LIVE_PUSH=ably`, realtime
 * §4, §8): an Ably subscription on the one channel the service's token grants; the token comes from
 * [tokenPath] with [bearer], through the SDK's `authCallback`, so the SDK fetches a new one when it
 * needs to. Without it (release 1, CR-013): the screen reads [catchUpPath] every [pollEvery] ±20 %,
 * starting at a random point of its timer, as realtime §7 tells the apps to. Each change is kept with
 * the time it arrived, for FR-005 and FR-011's NFR-PERF-03 target ([limit]).
 */
class Live(
    private val tokenPath: String,
    private val bearer: String,
    private val channelName: String,
    private val catchUpPath: String,
    private val pollEvery: Duration,
    private val pollLimit: Duration,
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
    private var poller: Thread? = null

    /** How long a change may take to show: NFR-PERF-03's p99 for one sample (2 s with push, CR-013's without). */
    val limit: Duration = if (Staging.livePush) LIMIT else pollLimit

    /** Starts listening; nothing changed after this returns is missed. */
    fun connect(): Live = if (Staging.livePush) subscribe() else poll()

    /** Connects and waits until the channel is attached, so nothing published after this is missed. */
    private fun subscribe(): Live {
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

    /**
     * Release 1's screen (realtime §7, CR-013): the cursor first, then a catch-up every [pollEvery]
     * ±20 % from a random point of the timer, each page until `has_more` is false.
     */
    private fun poll(): Live {
        val start =
            Staging.api
                .expect(OK, "GET", catchUpPath, token = bearer)
                .json
                .str("cursor")
                .toLong()
        poller =
            Thread {
                var cursor = start
                try {
                    Thread.sleep(Random.nextLong(pollEvery.toMillis()))
                    while (true) {
                        cursor = drain(cursor)
                        Thread.sleep((pollEvery.toMillis() * Random.nextDouble(1 - JITTER, 1 + JITTER)).toLong())
                    }
                } catch (_: InterruptedException) {
                    // disconnect(): the screen stops polling.
                }
            }.apply {
                isDaemon = true
                start()
            }
        return this
    }

    private fun drain(from: Long): Long {
        var cursor = from
        do {
            val page = Staging.api.expect(OK, "GET", "$catchUpPath?after=$cursor", token = bearer).json
            val at = Instant.now()
            page.arrOf("events").forEach { e ->
                val aggregate = e.getValue("aggregate").jsonObject
                notes += Note(e.str("seq").toLong(), e.str("type"), aggregate.str("id"), aggregate.version(), at)
            }
            cursor = page.str("cursor").toLong()
        } while (page["has_more"]?.jsonPrimitive?.boolean == true)
        return cursor
    }

    /** The phone goes offline: the connection closes (or the timer stops), and what changes meanwhile isn't delivered. */
    fun disconnect() {
        ably?.close()
        ably = null
        poller?.let {
            it.interrupt()
            it.join(STOP_WAIT_MS)
        }
        poller = null
    }

    override fun close() = disconnect()

    fun all(): List<Note> = notes.toList()

    /** The first notification matching [match], waiting up to [timeout] for it; null if none came. */
    fun await(
        timeout: Duration = limit.plus(WAIT),
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
            version = data.version(),
            at = Instant.now(),
        )

    private fun JsonObject.version(): Int? =
        this["version"]
            ?.takeUnless { it is JsonNull }
            ?.jsonPrimitive
            ?.content
            ?.toInt()

    companion object {
        /** With live push (`LIVE_PUSH=ably`), FR-005 and FR-011: a change shows within 2 s (NFR-PERF-03). */
        val LIMIT: Duration = Duration.ofSeconds(2)

        /** How much longer than [limit] a test waits before it calls a change missing, to report the real delay. */
        private val WAIT: Duration = Duration.ofSeconds(10)

        /** Release 1 without live push (CR-013, realtime §7): staff poll every 15 s, guests every 60 s, ±20 %. */
        private val STAFF_POLL: Duration = Duration.ofSeconds(15)
        private val GUEST_POLL: Duration = Duration.ofSeconds(60)
        private const val JITTER = 0.2

        /** NFR-PERF-03's release-1 p99: 20 s for staff screens, 75 s for guests' (CR-013). */
        private val STAFF_LIMIT: Duration = Duration.ofSeconds(20)
        private val GUEST_LIMIT: Duration = Duration.ofSeconds(75)
        private const val STOP_WAIT_MS = 10_000L
        private val ATTACH_TIMEOUT: Duration = Duration.ofSeconds(20)
        private const val POLL_MS = 10L

        fun guest(guest: Guest) =
            Live("/v1/guest/realtime-token", guest.token, "session.${guest.sessionId}", "/v1/guest/events", GUEST_POLL, GUEST_LIMIT)

        fun staff(
            site: Site,
            token: String,
        ) = Live("/v1/staff/realtime-token", token, "restaurant.${site.id}.staff", "/v1/staff/events", STAFF_POLL, STAFF_LIMIT)

        /** How long after [since] (the write's response) [note] arrived; zero if it beat the response. */
        fun delay(
            since: Instant,
            note: Live.Note,
        ): Duration = Duration.between(since, note.at).coerceAtLeast(Duration.ZERO)
    }
}
