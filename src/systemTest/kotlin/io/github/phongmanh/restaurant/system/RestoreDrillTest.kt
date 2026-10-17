package io.github.phongmanh.restaurant.system

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * test-plan §4 ST-08 (NFR-REL-01), the parts Claude runs in deploy/README.md's restore drill. Each
 * runs only when asked, as `-PrestoreDrill=mark`, `check` or `clean`, so a plain `systemTest` run
 * skips them:
 * - `mark` adds a table to restaurant A named for the time it's made (`st08-<yyMMddHHmmss>`, UTC);
 * - `check`, against the deployment on the restored branch, with `-PrestorePoint=<RFC 3339 time>`:
 *   every marker made before the point is there, and none made after it;
 * - `clean`, against the deployment back on staging's own branch: deletes the markers there.
 */
class RestoreDrillTest {
    private val a = Staging.a
    private val api = Staging.api
    private val drill = System.getProperty("restore.drill").orEmpty()

    @Test
    fun `ST-08 mark the database with a table named for the time`() {
        assumeTrue(drill == "mark")
        val number = "$PREFIX${STAMP.format(Instant.now())}"
        api.expect(CREATED, "POST", "/v1/staff/tables", buildJsonObject { put("number", number) }, a.manager, idempotencyKey())
        println("[ST-08] marker $number made at ${Instant.now()}")
    }

    @Test
    fun `ST-08 the restored database holds what was written before the point, and nothing after`() {
        assumeTrue(drill == "check")
        val point = Instant.parse(requireNotNull(System.getProperty("restore.point")?.takeIf { it.isNotBlank() }) { "-PrestorePoint=…" })
        val found = markers().map { it.str("number") }.toSet()
        println("[ST-08] restored to $point; markers found: ${found.sorted()}")
        assertTrue(found.isNotEmpty(), "a marker made before the point is there")
        val pointStamp = STAMP.format(point)
        assertTrue(found.all { it.removePrefix(PREFIX) <= pointStamp }, "no marker made after $pointStamp: $found")
    }

    @Test
    fun `ST-08 clean up the markers`() {
        assumeTrue(drill == "clean")
        for (table in markers()) {
            api.expect(
                NO_CONTENT,
                "DELETE",
                "/v1/staff/tables/${table.str("id")}",
                token = a.manager,
                headers = ifMatch(table.int("version")),
            )
        }
    }

    private fun markers() = a.tables().filter { it.str("number").startsWith(PREFIX) }

    private companion object {
        const val PREFIX = "st08-"

        /** Sorts as the time does; with the prefix it fits a table number's 20 characters. */
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMddHHmmss").withZone(ZoneOffset.UTC)
    }
}
