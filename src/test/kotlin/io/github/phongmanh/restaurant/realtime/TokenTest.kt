package io.github.phongmanh.restaurant.realtime

import io.github.phongmanh.restaurant.core.application.RateLimiter
import io.github.phongmanh.restaurant.core.config.LivePush
import io.github.phongmanh.restaurant.core.config.RealtimeSettings
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.orders.floor
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Guests
import io.github.phongmanh.restaurant.support.MutableClock
import io.github.phongmanh.restaurant.support.NoLimits
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.TestKeys
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.github.phongmanh.restaurant.support.testApp
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.koin.dsl.module
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** IT-RT-09 (realtime §8; NFR-SEC-02, FR-043). */
class TokenTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val e = Endpoints.Realtime

    @Test
    fun `IT-RT-09 a guest may only subscribe to their session, staff only to their restaurant, with no clientId`() {
        val clock = MutableClock()
        realtimeApp(clock = clock) {
            val f = client.floor()
            val guest = client.api(e.GUEST_REALTIME_TOKEN, f.guest).also { assertEquals(HttpStatusCode.OK, it.status) }.json()
            assertEquals(setOf("keyName", "ttl", "capability", "timestamp", "nonce", "mac"), guest.keys, "no clientId, Ably's own names")
            assertEquals("testapp.testkey", guest.str("keyName"))
            assertEquals("""{"session.${f.session}":["subscribe"]}""", guest.str("capability"))
            assertEquals(30 * 60 * 1000L, guest.getValue("ttl").jsonPrimitive.long)
            assertEquals(clock.instant().toEpochMilli(), guest.getValue("timestamp").jsonPrimitive.long)
            assertSignedWithTheKey(guest)

            listOf(f.kitchen, f.waiter, f.manager).forEach { token ->
                val staff = client.api(e.STAFF_REALTIME_TOKEN, token).json()
                assertEquals("""{"restaurant.${f.id}.staff":["subscribe"]}""", staff.str("capability"), "every role")
                assertEquals(60 * 60 * 1000L, staff.getValue("ttl").jsonPrimitive.long)
                assertSignedWithTheKey(staff)
            }
            val nonces = List(3) { client.api(e.STAFF_REALTIME_TOKEN, f.waiter).json().str("nonce") }
            assertEquals(3, nonces.distinct().size, "a fresh request each call")

            val b = Tenants.create("B", "manager@b.example")
            assertEquals(
                """{"restaurant.${b.id}.staff":["subscribe"]}""",
                client.api(e.STAFF_REALTIME_TOKEN, b.managerToken).json().str("capability"),
            )
        }
    }

    @Test
    fun `IT-RT-09 a closed session gets 401, a suspended restaurant 403`() =
        realtimeApp {
            val f = client.floor()
            val other = Guests.seated(f.id)
            Guests.closeSession(other.session)
            assertEquals("table_session_closed", client.api(e.GUEST_REALTIME_TOKEN, other.token).problem())
            AuthFixtures.suspend(f.id)
            assertEquals("restaurant_suspended", client.api(e.GUEST_REALTIME_TOKEN, f.guest).problem())
            assertEquals("restaurant_suspended", client.api(e.STAFF_REALTIME_TOKEN, f.waiter).problem())
        }

    @Test
    fun `IT-RT-14 with live push off the token routes answer 503`() =
        testApp(
            config = TestDatabase.config.copy(realtime = RealtimeSettings(null, LivePush.OFF)),
            overrides = listOf(module { single<RateLimiter> { NoLimits } }),
        ) {
            val tenant = Tenants.create("A", "manager@a.example")
            val response = client.api(e.STAFF_REALTIME_TOKEN, tenant.managerToken)
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("service_unavailable", response.problem())
            val guest = client.api(e.GUEST_REALTIME_TOKEN, Guests.seated(tenant.id).token)
            assertEquals(HttpStatusCode.ServiceUnavailable, guest.status)
        }

    /**
     * Ably's TokenRequest signature, recomputed from the documented format: HMAC-SHA256 with the key's
     * secret over keyName, ttl, capability, clientId (empty), timestamp and nonce, each followed by a newline.
     */
    private fun assertSignedWithTheKey(request: JsonObject) {
        val text =
            listOf("keyName", "ttl", "capability", null, "timestamp", "nonce").joinToString("") { field ->
                (field?.let { request.getValue(it).jsonPrimitive.content } ?: "") + "\n"
            }
        val secret = TestKeys.ABLY_KEY.substringAfter(':')
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        assertEquals(Base64.getEncoder().encodeToString(mac.doFinal(text.toByteArray())), request.str("mac"))
        assertTrue(request.str("nonce").length >= 16)
    }
}
