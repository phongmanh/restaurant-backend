package io.github.phongmanh.restaurant.core.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D-2 (ASVS V12.3.2): a remote database is reached only with its certificate and name checked. */
class JdbcTargetTest {
    private val factory = "sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory"

    @Test
    fun `a remote host needs verify-full, and the message keeps the password out`() {
        for (query in listOf("", "?sslmode=require", "?sslmode=verify-ca", "?sslmode=prefer", "?sslmode=disable")) {
            val e =
                assertFailsWith<IllegalArgumentException>(query) {
                    JdbcTarget.parse("postgresql://app:s3cret-value@ep-x.neon.tech/restaurant$query")
                }
            assertTrue("sslmode=verify-full" in e.message!!, e.message)
            assertFalse("s3cret-value" in e.message!!, "no password in the message")
        }
    }

    @Test
    fun `verify-full checks the certificate with the JDK's trust store`() {
        val target = JdbcTarget.parse("postgresql://migrator:pw@ep-x.neon.tech/restaurant?sslmode=verify-full&channel_binding=require")
        assertEquals(
            "jdbc:postgresql://ep-x.neon.tech/restaurant?sslmode=verify-full&channelBinding=require&$factory",
            target.jdbcUrl,
        )
        assertEquals("migrator" to "pw", target.user to target.password)
    }

    @Test
    fun `a factory the URL names is kept`() {
        val target = JdbcTarget.parse("postgresql://app:pw@db.example.test/r?sslmode=verify-full&sslfactory=x.Y")
        assertEquals("jdbc:postgresql://db.example.test/r?sslmode=verify-full&sslfactory=x.Y", target.jdbcUrl)
    }

    @Test
    fun `this machine needs no TLS`() {
        for (host in listOf("localhost:5432", "127.0.0.1", "[::1]:5432")) {
            val target = JdbcTarget.parse("postgresql://app:pw@$host/restaurant")
            assertEquals("jdbc:postgresql://$host/restaurant", target.jdbcUrl)
        }
    }
}
