package spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class JdbcTargetTest {
    @Test
    fun `parse - Neon URL - JDBC URL, credentials apart, channel_binding renamed`() {
        val target = JdbcTarget.parse(
            "postgresql://app:p%40ss@ep-cool-1234-pooler.ap-southeast-1.aws.neon.tech/spike?sslmode=require&channel_binding=require",
        )

        assertEquals(
            "jdbc:postgresql://ep-cool-1234-pooler.ap-southeast-1.aws.neon.tech/spike?sslmode=require&channelBinding=require",
            target.jdbcUrl,
        )
        assertEquals("app", target.user)
        assertEquals("p@ss", target.password)
    }

    @Test
    fun `parse - port and no query - kept`() {
        assertEquals("jdbc:postgresql://localhost:5432/spike", JdbcTarget.parse("postgres://u:p@localhost:5432/spike").jdbcUrl)
    }

    @Test
    fun `toString - never shows the password`() {
        assertFalse("secret" in JdbcTarget.parse("postgresql://u:secret@h/db").toString())
    }

    @Test
    fun `parse - not a postgres URL - fails`() {
        assertFailsWith<IllegalArgumentException> { JdbcTarget.parse("mysql://u:p@h/db") }
    }
}
