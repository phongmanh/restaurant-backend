package io.github.phongmanh.restaurant.system

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** test-plan §5, the guest's side: AT-001 to AT-007 (FR-001 to FR-007), in restaurant A. */
class GuestOrderingTest {
    private val a = Staging.a
    private val api = Staging.api

    @Test
    fun `AT-001 two phones join one session, and a rotated code is refused`() =
        a.withTables(1) { (table) ->
            val oldCode = a.qrToken(table.str("id"))
            val guest1 = Guest.scan(oldCode)
            val guest2 = Guest.scan(oldCode)
            assertEquals(guest1.sessionId, guest2.sessionId, "both guests are in one session")

            api.expect(OK, "POST", "/v1/staff/tables/${table.str("id")}/qr-code/rotate", token = a.manager)
            val guest3 =
                api.call(
                    "POST",
                    "/v1/guest/sessions",
                    buildJsonObject {
                        put("qr_token", oldCode)
                        put("device_id", UUID.randomUUID().toString())
                    },
                )
            assertEquals(400 to "qr_token_invalid", guest3.status to guest3.code, "the old printout is refused")

            val item = a.item("Fresh spring rolls").str("id")
            guest1.order(line(item))
            guest2.order(line(item))
            assertEquals(guest1.sessionId, Guest.scan(a.qrToken(table.str("id"))).sessionId, "the new code joins the same session")
        }

    @Test
    fun `AT-002 the guest menu hides hidden items and marks sold-out ones, with nothing of B`() =
        a.withTables(1) { (table) ->
            val menu = a.seat(table.str("id")).expect(OK, "GET", "/v1/guest/menu?lang=en").json
            val items = menu.arrOf("categories").flatMap { it.arrOf("items") }
            val names = items.map { it.str("name") }
            assertTrue("Test dish (hidden)" !in names, "a hidden item is absent")
            assertTrue("Goby fish hotpot" !in names, "an item of a hidden category is absent")
            assertTrue(menu.arrOf("categories").none { it.str("name") == "Seasonal" }, "a hidden category is absent")
            assertEquals(true, items.single { it.str("name") == "Lotus stem salad" }.bool("sold_out"), "the sold-out item is marked")
            assertEquals(false, items.single { it.str("name") == "Fresh spring rolls" }.bool("sold_out"))
            val bItems =
                Staging.b.seed.itemIds
                    .toSet()
            assertTrue(items.none { it.str("id") in bItems }, "no item of restaurant B")
        }

    @Test
    fun `AT-003 a retried order is placed once at the menu price, reaches the kitchen, and sold-out is refused`() =
        a.withTables(1) { (table) ->
            val guest = a.seat(table.str("id"))
            val menuItem = guestItem(guest, "Fresh spring rolls")
            val key = UUID.randomUUID().toString()
            val order = guest.order(line(menuItem.str("id"), 2, "ít rau"), key = key)
            val retry = guest.order(line(menuItem.str("id"), 2, "ít rau"), key = key)
            assertEquals(order.str("id"), retry.str("id"), "the retry with the same key answers the same order")
            assertEquals(listOf(order.str("id")), guest.orders().map { it.str("id") }, "exactly one order exists")
            val orderLine = order.arrOf("lines").single()
            assertEquals(menuItem.money("price"), orderLine.money("unit_price"), "priced at the menu price")
            assertEquals(2 * menuItem.money("price"), order.money("total"))

            val kitchen = api.expect(OK, "GET", "/v1/staff/orders?session_id=${guest.sessionId}", token = a.kitchen).arrOf("items")
            val seen = kitchen.single { it.str("id") == order.str("id") }
            assertEquals("ít rau", seen.arrOf("lines").single().str("note"), "the kitchen sees the note")
            assertEquals(table.str("number"), seen.obj("table").str("number"))

            val soldOut = guest.call("POST", "/v1/guest/orders", orderBody(line(a.item("Lotus stem salad").str("id"))), idempotencyKey())
            assertEquals(CONFLICT to "item_unavailable", soldOut.status to soldOut.code, "a sold-out item is refused")
            assertEquals(1, guest.orders().size, "nothing more was ordered")
        }

    @Test
    fun `AT-004 a second guest's round goes on the same session and bill`() =
        a.withTables(1) { (table) ->
            val code = a.qrToken(table.str("id"))
            val guest1 = Guest.scan(code)
            val guest2 = Guest.scan(code)
            val first = guest1.order(line(a.item("Iced tea").str("id")))
            val second = guest2.order(line(a.item("Saigon beer").str("id"), 2))
            assertEquals(guest1.sessionId, first.str("session_id"))
            assertEquals(guest1.sessionId, second.str("session_id"))
            val bill = guest1.bill()
            assertEquals(2, bill.int("order_count"))
            assertEquals(first.money("total") + second.money("total"), bill.money("total"))
            assertEquals(setOf(first.str("id"), second.str("id")), bill.arrOf("lines").map { it.str("order_id") }.toSet())
            val staffBill = api.expect(OK, "GET", "/v1/staff/table-sessions/${guest1.sessionId}/bill", token = a.waiter).json
            assertEquals(bill.money("total"), staffBill.money("total"), "staff see the same bill")
        }

    @Test
    fun `AT-005 both guests see the kitchen's change within 2 s, and an offline phone catches up`() =
        a.withTables(1) { (table) ->
            val code = a.qrToken(table.str("id"))
            val guest1 = Guest.scan(code)
            val guest2 = Guest.scan(code)
            Live.guest(guest1).connect().use { screen1 ->
                Live.guest(guest2).connect().use { screen2 ->
                    var order = guest1.order(line(a.item("Beef pho").str("id"), options = listOf(smallPho())))
                    order = a.step(order, "accepted")
                    order = a.step(order, "preparing")
                    val changedAt = Instant.now()
                    for ((name, screen) in listOf("guest 1" to screen1, "guest 2" to screen2)) {
                        val note =
                            screen.await {
                                it.type == "OrderStatusChanged" && it.id == order.str("id") &&
                                    it.version == order.int("version")
                            }
                        assertNotNull(note, "$name's screen got `preparing`")
                        val delay = Live.delay(changedAt, note)
                        println("[AT-005] `preparing` reached $name ${delay.toMillis()} ms after the change's response")
                        assertTrue(delay <= Live.LIMIT, "$name saw `preparing` within ${Live.LIMIT}: took $delay")
                    }
                    val cursor = guest1.expect(OK, "GET", "/v1/guest/events").json.str("cursor")
                    screen1.disconnect()
                    Thread.sleep(OFFLINE.toMillis())
                    order = a.step(order, "ready")
                    order = a.step(order, "served", a.waiter)
                    assertCaughtUp(guest1, cursor, order)
                }
            }
        }

    @Test
    fun `AT-006 a placed order can be cancelled by the guest, an accepted one only by staff`() =
        a.withTables(1) { (table) ->
            val guest = a.seat(table.str("id"))
            val placed = guest.order(line(a.item("Coconut water").str("id")))
            val cancelled = guest.expect(OK, "POST", "/v1/guest/orders/${placed.str("id")}/cancel").json
            assertEquals("cancelled", cancelled.str("status"))
            val bill = guest.bill()
            assertEquals(0, bill.int("order_count"), "the cancelled order left the bill")
            assertEquals(0L, bill.money("total"))
            val kitchenView = api.expect(OK, "GET", "/v1/staff/orders/${placed.str("id")}", token = a.kitchen).json
            assertEquals("cancelled", kitchenView.str("status"), "the kitchen sees it cancelled")

            val accepted = a.step(guest.order(line(a.item("Coconut water").str("id"))), "accepted")
            val refused = guest.call("POST", "/v1/guest/orders/${accepted.str("id")}/cancel")
            assertEquals(CONFLICT to "order_not_cancellable", refused.status to refused.code, "an accepted order: ask staff")
        }

    @Test
    fun `AT-007 a price change keeps the price of lines already ordered`() =
        a.withTables(1) { (table) ->
            val guest = a.seat(table.str("id"))
            val item = a.item("Iced tea")
            val oldPrice = item.money("price")
            val before = guest.order(line(item.str("id"), 2))
            try {
                setPrice(item.str("id"), oldPrice + RAISE)
                val after = guest.order(line(item.str("id")))
                assertEquals(oldPrice + RAISE, after.arrOf("lines").single().money("unit_price"), "a new order takes the new price")
                val bill = guest.bill()
                val oldLine = bill.arrOf("lines").single { it.str("order_id") == before.str("id") }
                assertEquals(oldPrice, oldLine.money("unit_total"), "the earlier line keeps the old price")
                assertEquals(2 * oldPrice + oldPrice + RAISE, bill.money("total"), "the total adds both")
            } finally {
                setPrice(item.str("id"), oldPrice)
            }
        }

    private fun guestItem(
        guest: Guest,
        name: String,
    ): JsonObject =
        guest
            .expect(OK, "GET", "/v1/guest/menu?lang=en")
            .json
            .arrOf("categories")
            .flatMap { it.arrOf("items") }
            .single { it.str("name") == name }

    /** Beef pho's required size choice: small. */
    private fun smallPho(): String =
        a
            .item("Beef pho")
            .arrOf("option_groups")
            .single { it.str("name_en") == "Size" }
            .arrOf("options")
            .single { it.str("name_en") == "Small" }
            .str("id")

    /** Sets the price against the item's current version, so the restore in `finally` works after any failure. */
    private fun setPrice(
        itemId: String,
        price: Long,
    ): JsonObject {
        val current = api.expect(OK, "GET", "/v1/staff/menu/items/$itemId", token = a.manager).json
        return api
            .expect(
                OK,
                "PATCH",
                "/v1/staff/menu/items/$itemId",
                buildJsonObject {
                    put("price", vnd(price))
                },
                a.manager,
                ifMatch(current.int("version")),
            ).json
    }

    /** The phone back online: the catch-up after its last cursor holds both changes it missed (realtime §6). */
    private fun assertCaughtUp(
        guest: Guest,
        cursor: String,
        order: JsonObject,
    ) {
        val page = guest.expect(OK, "GET", "/v1/guest/events?after=$cursor").json
        val missed =
            page.arrOf("events").filter {
                it.str("type") == "OrderStatusChanged" &&
                    it.obj("aggregate").str("id") == order.str("id")
            }
        assertEquals(2, missed.size, "both changes made while offline (ready, served)")
        val caughtUp = page.arrOf("orders").single { it.str("id") == order.str("id") }
        assertEquals("served", caughtUp.str("status"), "the order shows its latest status")
        assertTrue(page["reset"]?.toString() != "true", "no reload needed")
    }

    private companion object {
        const val RAISE = 2_000L

        /** FR-005: the phone is offline for a minute. */
        val OFFLINE: Duration = Duration.ofMinutes(1)
    }
}
