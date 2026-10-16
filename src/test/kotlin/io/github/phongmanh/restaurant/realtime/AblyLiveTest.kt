package io.github.phongmanh.restaurant.realtime

import io.ably.lib.rest.AblyRest
import io.ably.lib.rest.Auth
import io.ably.lib.types.ClientOptions
import io.ably.lib.util.Log
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.ApiJson
import io.github.phongmanh.restaurant.realtime.ably.AblyClient
import io.github.phongmanh.restaurant.realtime.ably.AblyPublisher
import io.github.phongmanh.restaurant.realtime.ably.AblyTokenIssuer
import io.github.phongmanh.restaurant.realtime.application.Notification
import io.github.phongmanh.restaurant.realtime.application.PublishResult
import io.github.phongmanh.restaurant.realtime.domain.Channels
import io.github.phongmanh.restaurant.realtime.domain.RealtimeRules
import io.github.phongmanh.restaurant.realtime.http.RealtimeTokenBody
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTimedValue

/**
 * The real Ably adapter against real Ably (owner's choice at step 8's start): skipped unless
 * `ABLY_API_KEY` is set in the shell, so CI never runs it. Run it by hand with a test app's key:
 *
 *     ABLY_API_KEY=… ./gradlew test --tests '*AblyLiveTest'
 *
 * It checks that one batch request publishes to both channels and a repeat of the ID is accepted
 * (idempotent publishing), and that Ably accepts a token request the server signed and grants
 * exactly the one channel. The key is never printed.
 */
class AblyLiveTest {
    private val key: String? = System.getenv("ABLY_API_KEY")?.takeIf { it.isNotBlank() }

    @Test
    fun `publishes to both channels, and a repeat of the same ID is accepted`() =
        runBlocking {
            assumeTrue(key != null, "ABLY_API_KEY not set: live Ably check skipped")
            AblyClient(key!!).use { client ->
                val publisher = AblyPublisher(client)
                val notification =
                    Notification(
                        id = UuidV7.next(),
                        name = "LiveCheck",
                        channels = listOf(Channels.staff(RestaurantId(UuidV7.next())), Channels.session(TableSessionId(UuidV7.next()))),
                        data = mapOf("seq" to 1L, "type" to "LiveCheck", "id" to UuidV7.next().toString(), "version" to 0),
                    )
                val (first, took) = measureTimedValue { publisher.publish(notification) }
                assertEquals(PublishResult.Sent, first)
                assertEquals(PublishResult.Sent, publisher.publish(notification), "the same ID again: Ably keeps one")
                // From this machine, not from sin1: realtime §4.2 expects ≈ 5–15 ms there.
                println("live Ably publish took $took (limit ${RealtimeRules.PUBLISH_TIMEOUT})")
            }
        }

    @Test
    fun `Ably accepts the signed token request and grants only the one channel`() {
        assumeTrue(key != null, "ABLY_API_KEY not set: live Ably check skipped")
        AblyClient(key!!).use { client ->
            val channel = Channels.session(TableSessionId(UuidV7.next()))
            val request = AblyTokenIssuer(client).issue(channel, RealtimeRules.GUEST_TOKEN_TTL, Instant.now())
            // The client's side: the body the API sends, handed to the SDK's authCallback.
            val body = ApiJson.encodeToString(RealtimeTokenBody.serializer(), RealtimeTokenBody.of(request))
            val device =
                AblyRest(
                    ClientOptions().apply {
                        authCallback = Auth.TokenCallback { Auth.TokenRequest.fromJson(body) }
                        logLevel = Log.NONE
                    },
                )
            val token = device.auth.authorize(null, null)
            assertEquals("""{"$channel":["subscribe"]}""", token.capability)
            assertEquals(null, token.clientId)
            device.close()
        }
    }
}
