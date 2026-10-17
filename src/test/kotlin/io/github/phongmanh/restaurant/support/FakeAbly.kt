package io.github.phongmanh.restaurant.support

import io.github.phongmanh.restaurant.realtime.application.Notification
import io.github.phongmanh.restaurant.realtime.application.PublishResult
import io.github.phongmanh.restaurant.realtime.application.RealtimePublisher
import java.util.UUID

/**
 * Ably for tests (realtime §13): up or [down], refusing the next [rateLimitNext] requests with
 * `42918`. Like Ably, it keeps one copy of a message ID per channel, and it counts how often each ID
 * was sent, so a test can tell a repeat that Ably would drop from a missing publish.
 */
class FakeAbly : RealtimePublisher {
    @Volatile var down = false

    @Volatile var rateLimitNext = 0

    /** Called with each request before it's answered, e.g. to look at the database at that moment. */
    @Volatile var onPublish: (Notification) -> Unit = {}

    private val sent = mutableListOf<Notification>()
    private val tries = mutableListOf<UUID>()

    override suspend fun publish(notification: Notification): PublishResult =
        synchronized(this) {
            onPublish(notification)
            tries += notification.id
            when {
                rateLimitNext > 0 -> PublishResult.Failed("42918", rateLimited = true).also { rateLimitNext-- }
                down -> PublishResult.Failed("timeout")
                else -> PublishResult.Sent.also { sent += notification }
            }
        }

    /** Every accepted notification, in order. */
    @Synchronized
    fun received(): List<Notification> = sent.toList()

    /** Every request, accepted or not. */
    @Synchronized
    fun tries(): Int = tries.size

    /** How many requests carried each event ID, accepted or not. */
    @Synchronized
    fun triesOf(id: UUID): Int = tries.count { it == id }

    /** The accepted notifications on [channel]. */
    @Synchronized
    fun on(channel: String): List<Notification> = sent.filter { channel in it.channels }
}
