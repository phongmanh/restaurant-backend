package io.github.phongmanh.restaurant.tables

import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.TableId
import io.github.phongmanh.restaurant.core.domain.TableSessionId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.support.AuthFixtures
import io.github.phongmanh.restaurant.support.Guests
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.TestKeys
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.errorFields
import io.github.phongmanh.restaurant.support.items
import io.github.phongmanh.restaurant.support.json
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.str
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.Signature
import java.util.Base64
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** IT-TBL-03, IT-TBL-04 and IT-TBL-10 (tables §2, §6; FR-030, FR-031). */
class TableTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private val e = Endpoints.Tables

    @Test
    fun `IT-TBL-10 managers create, edit, disable and delete tables, numbers are unique, other roles can't`() =
        tablesApp {
            val a = Tenants.create("A", "manager@a.example")
            val (_, waiter) = a.member("waiter@a.example", "waiter")
            val (_, kitchen) = a.member("kitchen@a.example", "kitchen")
            val t = a.managerToken

            val created = client.api(e.CREATE_TABLE, t, body = """{"number": " A-5 ", "area": "Garden", "seats": 4}""")
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals("\"0\"", created.headers[HttpHeaders.ETag])
            val table = created.json()
            assertEquals(e.GET_TABLE.pathOf("tableId" to table.str("id")), created.headers[HttpHeaders.Location])
            assertEquals(listOf("A-5", "Garden", "4", "true", "0"), listOf("number", "area", "seats", "enabled", "version").map(table::str))
            val id = table.str("id")

            assertEquals("duplicate_name", client.api(e.CREATE_TABLE, t, body = """{"number": "a-5"}""").problem(), "case-insensitive")
            assertEquals(
                listOf("/number", "/seats"),
                client.api(e.CREATE_TABLE, t, body = """{"number": "${"x".repeat(21)}", "seats": 51}""").errorFields(),
            )
            assertEquals("forbidden", client.api(e.CREATE_TABLE, waiter, body = """{"number": "9"}""").problem())
            assertEquals("forbidden", client.api(e.CREATE_TABLE, kitchen, body = """{"number": "9"}""").problem())
            assertEquals("forbidden", client.api(e.GET_TABLE, kitchen, "tableId" to id).problem(), "kitchen has no floor view")
            assertEquals(HttpStatusCode.OK, client.api(e.GET_TABLE, waiter, "tableId" to id).status)

            assertEquals("precondition_required", client.api(e.UPDATE_TABLE, t, "tableId" to id, body = """{"seats": 6}""").problem())
            assertEquals(
                "precondition_failed",
                client.api(e.UPDATE_TABLE, t, "tableId" to id, body = """{"seats": 6}""", configure = ifMatch(3)).problem(),
            )
            val edited = client.api(e.UPDATE_TABLE, t, "tableId" to id, body = """{"seats": 6, "area": ""}""", configure = ifMatch(0))
            assertEquals("\"1\"", edited.headers[HttpHeaders.ETag])
            val editedBody = edited.json()
            assertFalse("area" in editedBody, "an empty area removes it")
            assertEquals("6", editedBody.str("seats"))

            // Guests at the table: neither disabling nor deleting it is allowed.
            val (_, sessionId) = client.seat(client.qrToken(t, id))
            assertEquals(
                "table_has_open_session",
                client.api(e.UPDATE_TABLE, t, "tableId" to id, body = """{"enabled": false}""", configure = ifMatch(1)).problem(),
            )
            assertEquals("table_has_open_session", client.api(e.DELETE_TABLE, t, "tableId" to id, configure = ifMatch(1)).problem())
            Guests.closeSession(TableSessionId(UUID.fromString(sessionId)))
            assertEquals(
                HttpStatusCode.OK,
                client.api(e.UPDATE_TABLE, t, "tableId" to id, body = """{"enabled": false}""", configure = ifMatch(1)).status,
            )
            assertEquals(HttpStatusCode.NoContent, client.api(e.DELETE_TABLE, t, "tableId" to id, configure = ifMatch(2)).status)
            assertEquals("not_found", client.api(e.GET_TABLE, t, "tableId" to id).problem())
            assertEquals(
                HttpStatusCode.Created,
                client.api(e.CREATE_TABLE, t, body = """{"number": "A-5"}""").status,
                "the number is free again",
            )

            assertEquals(
                listOf("table.created", "table.updated", "table.updated", "table.deleted", "table.created"),
                auditOf(a.id, "table."),
            )
        }

    @Test
    fun `IT-TBL-10 another restaurant's table is 404, and the 301st table is 409`() =
        tablesApp {
            val a = Tenants.create("A", "manager@a.example")
            val b = Tenants.create("B", "manager@b.example")
            val bTable = client.table(b.managerToken, "1").str("id")
            assertEquals("not_found", client.api(e.GET_TABLE, a.managerToken, "tableId" to bTable).problem())
            assertEquals(
                "not_found",
                client
                    .api(
                        e.UPDATE_TABLE,
                        a.managerToken,
                        "tableId" to bTable,
                        body = """{"seats": 2}""",
                        configure = ifMatch(0),
                    ).problem(),
            )
            assertEquals("not_found", client.api(e.GET_QR_CODE, a.managerToken, "tableId" to bTable).problem())
            assertEquals("not_found", client.api(e.ROTATE_QR_CODE, a.managerToken, "tableId" to bTable).problem())

            AuthFixtures.sql(
                "INSERT INTO dining_tables (id, restaurant_id, number, created_at, updated_at) " +
                    "SELECT gen_random_uuid(), ?, 'S' || n, now(), now() FROM generate_series(1, 299) n",
                a.id.value,
            )
            assertEquals(HttpStatusCode.Created, client.api(e.CREATE_TABLE, a.managerToken, body = """{"number": "300th"}""").status)
            assertEquals("table_limit_reached", client.api(e.CREATE_TABLE, a.managerToken, body = """{"number": "301st"}""").problem())
        }

    @Test
    fun `two creates at once can't both take the 300th slot or the same number`() =
        tablesApp {
            val a = Tenants.create("A", "manager@a.example")
            AuthFixtures.sql(
                "INSERT INTO dining_tables (id, restaurant_id, number, created_at, updated_at) " +
                    "SELECT gen_random_uuid(), ?, 'S' || n, now(), now() FROM generate_series(1, 299) n",
                a.id.value,
            )
            repeat(ROUNDS) { round ->
                val statuses =
                    coroutineScope {
                        (1..2)
                            .map { n ->
                                async { client.api(e.CREATE_TABLE, a.managerToken, body = """{"number": "R$round-$n"}""").status.value }
                            }.map { it.await() }
                    }
                assertEquals(listOf(201, 409), statuses.sorted(), "round $round")
                AuthFixtures.sql("UPDATE dining_tables SET deleted_at = now() WHERE number LIKE 'R%' AND deleted_at IS NULL")
            }
            val same =
                coroutineScope {
                    (1..2).map { async { client.api(e.CREATE_TABLE, a.managerToken, body = """{"number": "Same"}""") } }.map { it.await() }
                }
            assertEquals(listOf(201, 409), same.map { it.status.value }.sorted())
        }

    @Test
    fun `IT-TBL-03 a forged, unknown-key, truncated, rotated or deleted-table code is one identical 400, and nothing is created`() =
        tablesApp {
            val a = Tenants.create("A", "manager@a.example")
            val t = a.managerToken
            val table = client.table(t, "1").str("id")
            val deleted = client.table(t, "2").str("id")
            val good = client.qrToken(t, table)
            val deletedCode = client.qrToken(t, deleted)
            assertEquals(HttpStatusCode.NoContent, client.api(e.DELETE_TABLE, t, "tableId" to deleted, configure = ifMatch(0)).status)

            val (payload, signature) = good.split('.')
            val bytes = Base64.getUrlDecoder().decode(payload)

            fun encode(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
            val other = UuidV7.next()
            val otherTable =
                bytes.copyOf().also {
                    ByteBuffer
                        .wrap(
                            it,
                            18,
                            16,
                        ).putLong(other.mostSignificantBits)
                        .putLong(other.leastSignificantBits)
                }
            val unknownKid = bytes.copyOf().also { it[1] = 9 }
            val forgedKey = QrSigner(TestKeys.ecKeyPair()).sign(bytes)
            val rotatedCode = client.qrToken(t, table)
            client.api(e.ROTATE_QR_CODE, t, "tableId" to table)

            val bad =
                mapOf(
                    "another table, same signature" to "${encode(otherTable)}.$signature",
                    "unknown kid" to "${encode(unknownKid)}.$signature",
                    "signed by another key" to "$payload.${encode(forgedKey)}",
                    "truncated" to good.dropLast(5),
                    "no signature" to payload,
                    "not base64" to "%%%.***",
                    "rotated" to rotatedCode,
                    "deleted table" to deletedCode,
                )
            val bodies =
                bad.map { (case, token) ->
                    val response = client.scan(token)
                    assertEquals(HttpStatusCode.BadRequest, response.status, case)
                    assertEquals("qr_token_invalid", response.problem(), case)
                    response.json().filterKeys { it != "request_id" }.toString()
                }
            assertEquals(1, bodies.toSet().size, "every bad code gets the same body")
            assertEquals(0, count("SELECT count(*) FROM table_sessions"), "nothing created")
        }

    @Test
    fun `IT-TBL-04 rotation refuses the old code at once, seated guests keep ordering, and rotate-all refuses every old code`() =
        tablesApp {
            val a = Tenants.create("A", "manager@a.example")
            val t = a.managerToken
            val one = client.table(t, "1").str("id")
            val two = client.table(t, "2").str("id")
            val oldOne = client.qrToken(t, one)
            val oldTwo = client.qrToken(t, two)
            val (guest, _) = client.seat(oldOne)

            val rotated = client.api(e.ROTATE_QR_CODE, t, "tableId" to one).json()
            assertEquals(2, rotated.str("key_version").toInt())
            assertEquals("qr_token_invalid", client.scan(oldOne).problem(), "the old code is refused at once")
            assertEquals(HttpStatusCode.OK, client.api(e.GET_GUEST_SESSION, guest).status, "the seated guest keeps the session")
            assertEquals(HttpStatusCode.OK, client.scan(rotated.str("url").substringAfter("/t/")).status)

            val all = client.api(e.ROTATE_ALL_QR_CODES, t).items().map { it.jsonObject }
            assertEquals(listOf("1", "2"), all.map { it.str("number") })
            assertEquals(listOf("3", "2"), all.map { it.str("key_version") })
            assertEquals("qr_token_invalid", client.scan(rotated.str("url").substringAfter("/t/")).problem())
            assertEquals("qr_token_invalid", client.scan(oldTwo).problem())
            all.forEach { assertEquals(HttpStatusCode.OK, client.scan(it.str("url").substringAfter("/t/")).status) }

            val printed = client.api(e.LIST_QR_CODES, t).items().map { it.jsonObject }
            assertTrue(printed.all { it.str("url").startsWith("${TestKeys.CUSTOMER_APP}/t/") })
            assertEquals("forbidden", client.api(e.LIST_QR_CODES, a.member("w@a.example", "waiter").second).problem())
            assertEquals(listOf("table.qr_rotated", "table.qr_rotated_all"), auditOf(a.id, "table.qr"))
        }

    @Test
    fun `a code made with the previous QR key still works, and codes differ each time but all verify`() =
        tablesApp {
            val a = Tenants.create("A", "manager@a.example")
            val table = client.table(a.managerToken, "1").str("id")
            val first = client.qrToken(a.managerToken, table)
            val second = client.qrToken(a.managerToken, table)
            assertNotEquals(first, second, "ECDSA signatures differ")
            assertEquals(HttpStatusCode.OK, client.scan(first).status)
            assertEquals(HttpStatusCode.OK, client.scan(second).status)

            val payload = Base64.getUrlDecoder().decode(first.substringBefore('.')).also { it[1] = 1 }
            val signature = QrSigner(TestKeys.qrPrevious).sign(payload)

            fun encode(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
            assertEquals(HttpStatusCode.OK, client.scan("${encode(payload)}.${encode(signature)}").status, "key 1 still verifies (§13 Q5)")
            assertEquals(RestaurantId(a.id.value), Guests.codes.verify(first)!!.restaurantId)
            assertEquals(TableId(UUID.fromString(table)), Guests.codes.verify(first)!!.tableId)
        }

    private companion object {
        const val ROUNDS = 5
    }
}

/** Signs a QR payload the way `QrCodes` does, with any key: for codes the server didn't make. */
class QrSigner(
    private val pair: KeyPair,
) {
    fun sign(payload: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(pair.private)
            update(payload)
            sign()
        }
}
