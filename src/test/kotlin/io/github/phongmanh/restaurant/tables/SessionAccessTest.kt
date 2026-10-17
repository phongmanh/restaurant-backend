package io.github.phongmanh.restaurant.tables

import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.persistence.DatabasePools
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.realtime.application.CircuitBreaker
import io.github.phongmanh.restaurant.realtime.application.OutboxEventLog
import io.github.phongmanh.restaurant.realtime.application.OutboxPublisher
import io.github.phongmanh.restaurant.realtime.persistence.EventRepository
import io.github.phongmanh.restaurant.realtime.persistence.OutboxRepository
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.FakeAbly
import io.github.phongmanh.restaurant.support.Guests
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.tables.application.DatabaseSessionAccess
import io.github.phongmanh.restaurant.tables.domain.CloseReason
import io.github.phongmanh.restaurant.tables.persistence.SessionRepository
import io.github.phongmanh.restaurant.tables.persistence.WaiterCallRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `SessionAccess` port's locks (tables §7), the tables half of IT-TBL-14: the orders feature
 * (step 7) adds the order itself. Each "order" here is what an order transaction does with the
 * session: lock it, then touch it.
 */
class SessionAccessTest {
    private lateinit var pools: DatabasePools
    private lateinit var tx: ExposedTransactor
    private lateinit var access: DatabaseSessionAccess

    @BeforeTest
    fun setUp() {
        TestDatabase.truncate()
        pools = DatabasePools(TestDatabase.settings)
        tx = ExposedTransactor(pools)
        val clock = Clock.systemUTC()
        val publisher = OutboxPublisher(tx, OutboxRepository(), FakeAbly(), CircuitBreaker(clock), clock)
        access =
            DatabaseSessionAccess(SessionRepository(), WaiterCallRepository(), OutboxEventLog(EventRepository(), publisher, clock, UuidV7))
    }

    @AfterTest
    fun tearDown() = pools.close()

    @Test
    fun `two orders into one session at once both go through, without a deadlock`() =
        runBlocking {
            val seat = Guests.seated(AuthFixtures.restaurant())
            repeat(ROUNDS) { round ->
                val results =
                    (1..2)
                        .map {
                            async(Dispatchers.IO) {
                                runCatching {
                                    tx.inTransaction(seat.restaurant) {
                                        access.requireOpenForUpdate(this, seat.restaurant, seat.session)
                                        // Both would hold a share lock here, then each wait for the other to update.
                                        delay(HOLD_MS)
                                        access.touch(this, seat.restaurant, seat.session, Instant.now())
                                    }
                                }
                            }
                        }.awaitAll()
                assertTrue(results.all { it.isSuccess }, "round $round: $results")
            }
        }

    @Test
    fun `IT-TBL-14 tables half - a close waits for an order in progress, and the next order is refused`() =
        runBlocking {
            val seat = Guests.seated(AuthFixtures.restaurant())
            val locked = CompletableDeferred<Unit>()
            val order =
                async(Dispatchers.IO) {
                    tx.inTransaction(seat.restaurant) {
                        access.requireOpenForUpdate(this, seat.restaurant, seat.session)
                        locked.complete(Unit)
                        delay(HOLD_MS * 3)
                        access.touch(this, seat.restaurant, seat.session, Instant.now())
                        Instant.now()
                    }
                }
            locked.await()
            val close =
                async(Dispatchers.IO) {
                    tx.inTransaction(seat.restaurant) {
                        access.lockForClose(this, seat.restaurant, seat.session)
                        Instant.now() to access.close(this, seat.restaurant, seat.session, CloseReason.PAID, Instant.now())
                    }
                }
            val ordered = order.await()
            val (lockedAt, closed) = close.await()
            assertTrue(closed)
            assertFalse(lockedAt.isBefore(ordered), "the close got the session only after the order committed")

            assertFailsWith<DomainError.SessionNotOpen> {
                tx.inTransaction(seat.restaurant) { access.requireOpenForUpdate(this, seat.restaurant, seat.session) }
            }
            assertFailsWith<DomainError.SessionNotOpen> {
                tx.inTransaction(seat.restaurant) { access.lockForClose(this, seat.restaurant, seat.session) }
            }
            assertFalse(
                tx.inTransaction(
                    seat.restaurant,
                ) { access.close(this, seat.restaurant, seat.session, CloseReason.PAID, Instant.now()) },
                "closing twice changes nothing",
            )
            assertEquals(1, eventsOf(seat.restaurant).count { it == "TableSessionClosed" })
        }

    @Test
    fun `another restaurant's session is 404`() =
        runBlocking {
            val seat = Guests.seated(AuthFixtures.restaurant())
            val other = AuthFixtures.restaurant("B")
            assertFailsWith<DomainError.NotFound> { tx.inTransaction(other) { access.requireOpenForUpdate(this, other, seat.session) } }
        }

    private companion object {
        const val ROUNDS = 5
        const val HOLD_MS = 100L
    }
}
