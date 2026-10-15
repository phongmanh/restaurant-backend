package io.github.phongmanh.restaurant.core.http

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.testApp
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.post
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.koin.ktor.ext.get
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The core part of IT-CORE-09 (architecture §11, NFR-PRIV-01): a failed statement's values reach no
 * log line, though Exposed and pgjdbc would both print them by default. Auth adds the sign-in cases.
 */
class LogPrivacyTest {
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val email = "someone@example.com"

    @BeforeTest
    fun setUp() {
        TestDatabase.truncate()
        appender.start()
        root.addAppender(appender)
    }

    @AfterTest
    fun tearDown() {
        root.detachAppender(appender)
    }

    @Test
    fun `a failing statement's values are in no log line`() =
        testApp(
            routes = { app ->
                val transactor = app.get<Transactor>()
                post("/v1/test/duplicate") {
                    val restaurant = RestaurantId(UuidV7.next())
                    // PostgreSQL leaves the key's values out of its error detail when RLS applies to
                    // the caller, so this runs where it doesn't, as a global table will for `app`.
                    transactor.platformTransaction("test") {
                        repeat(2) {
                            TransactionManager.current().exec(
                                "INSERT INTO idempotency_keys (scope, operation, key, restaurant_id, request_hash, created_at) " +
                                    "VALUES ('s', 'op', ?, ?::uuid, '\\x00', now())",
                                listOf(TextColumnType() to email, TextColumnType() to restaurant.toString()),
                            )
                        }
                    }
                }
            },
        ) {
            assertEquals(HttpStatusCode.InternalServerError, client.post("/v1/test/duplicate").status)
            val logged =
                appender.list.joinToString("\n") { e ->
                    e.formattedMessage + (e.throwableProxy?.let { "\n" + ThrowableProxyUtil.asString(it) } ?: "")
                }
            assertTrue("duplicate key" in logged, "the failure itself is logged:\n$logged")
            assertTrue(email !in logged, logged)
        }
}
