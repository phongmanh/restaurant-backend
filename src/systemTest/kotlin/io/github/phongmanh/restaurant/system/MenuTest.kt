package io.github.phongmanh.restaurant.system

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** test-plan §5, the menu: AT-020 and AT-021 (FR-020, FR-021), in restaurant A. */
class MenuTest {
    private val a = Staging.a
    private val api = Staging.api

    @Test
    fun `AT-020 a manager's menu changes show on the guest's next load, and a waiter can't edit`() {
        val category =
            api
                .expect(
                    CREATED,
                    "POST",
                    "/v1/staff/menu/categories",
                    buildJsonObject {
                        put("name_vi", "AT-020 ${Staging.run}")
                        put("name_en", "AT-020 ${Staging.run}")
                    },
                    a.manager,
                ).json
        val shown = createItem(category, "AT-020 món ${Staging.run}", PRICE)
        val hidden = createItem(category, "AT-020 ẩn ${Staging.run}", PRICE)
        try {
            val repriced = updateItem(shown, buildJsonObject { put("price", vnd(NEW_PRICE)) })
            updateItem(hidden, buildJsonObject { put("active", false) })
            a.withTables(1) { (table) ->
                val menu = a.seat(table.str("id")).expect(OK, "GET", "/v1/guest/menu?lang=vi").json
                val section = menu.arrOf("categories").single { it.str("id") == category.str("id") }
                val items = section.arrOf("items")
                assertEquals(listOf(shown.str("id")), items.map { it.str("id") }, "the new item shows, the hidden one doesn't")
                assertEquals(NEW_PRICE, items.single().money("price"), "at its new price")
            }
            assertEquals(NEW_PRICE, repriced.money("price"))

            val waiter = api.call("POST", "/v1/staff/menu/items", itemBody(category, "AT-020 waiter ${Staging.run}", PRICE), a.waiter)
            assertEquals(FORBIDDEN to "forbidden", waiter.status to waiter.code, "a waiter can't edit the menu")
        } finally {
            for (item in listOf(shown, hidden)) {
                val current = api.expect(OK, "GET", "/v1/staff/menu/items/${item.str("id")}", token = a.manager).json
                api.expect(
                    NO_CONTENT,
                    "DELETE",
                    "/v1/staff/menu/items/${item.str("id")}",
                    token = a.manager,
                    headers = ifMatch(current.int("version")),
                )
            }
            val current = a.staffMenu().arrOf("categories").single { it.str("id") == category.str("id") }
            api.expect(
                NO_CONTENT,
                "DELETE",
                "/v1/staff/menu/categories/${category.str("id")}",
                token = a.manager,
                headers = ifMatch(current.int("version")),
            )
        }
    }

    @Test
    fun `AT-021 an item the kitchen marks sold out is refused until it is available again`() =
        a.withTables(1) { (table) ->
            val guest = a.seat(table.str("id"))
            val item = a.item("Fried spring rolls").str("id")
            soldOut(item, true)
            try {
                val refused = guest.call("POST", "/v1/guest/orders", orderBody(line(item)), idempotencyKey())
                assertEquals(CONFLICT to "item_unavailable", refused.status to refused.code, "a sold-out item is refused")
                assertTrue(guest.orders().isEmpty(), "nothing was ordered")
            } finally {
                soldOut(item, false)
            }
            assertEquals(
                item,
                guest
                    .order(line(item))
                    .arrOf("lines")
                    .single()
                    .str("item_id"),
                "available again: the order succeeds",
            )
        }

    private fun soldOut(
        itemId: String,
        value: Boolean,
    ) = api.expect(OK, "PUT", "/v1/staff/menu/items/$itemId/sold-out", buildJsonObject { put("sold_out", value) }, a.kitchen)

    private fun itemBody(
        category: JsonObject,
        name: String,
        price: Long,
    ) = buildJsonObject {
        put("category_id", category.str("id"))
        put("name_vi", name)
        put("name_en", name)
        put("price", vnd(price))
    }

    private fun createItem(
        category: JsonObject,
        name: String,
        price: Long,
    ) = api.expect(CREATED, "POST", "/v1/staff/menu/items", itemBody(category, name, price), a.manager).json

    private fun updateItem(
        item: JsonObject,
        change: JsonObject,
    ): JsonObject {
        val current = api.expect(OK, "GET", "/v1/staff/menu/items/${item.str("id")}", token = a.manager).json
        return api
            .expect(
                OK,
                "POST",
                "/v1/staff/menu/items/${item.str("id")}/update",
                change,
                a.manager,
                ifMatch(current.int("version")),
            ).json
    }

    private companion object {
        const val PRICE = 50_000L
        const val NEW_PRICE = 55_000L
    }
}
