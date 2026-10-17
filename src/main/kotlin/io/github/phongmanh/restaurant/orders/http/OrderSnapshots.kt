package io.github.phongmanh.restaurant.orders.http

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.http.ApiJson
import io.github.phongmanh.restaurant.orders.application.OrderEvents
import io.github.phongmanh.restaurant.orders.application.OrderQueryService
import io.github.phongmanh.restaurant.orders.domain.OrderId
import io.github.phongmanh.restaurant.orders.persistence.OrderRepository
import io.github.phongmanh.restaurant.realtime.application.Snapshots
import io.github.phongmanh.restaurant.realtime.domain.Viewer
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/**
 * Orders' answer to realtime's `Snapshots` port (realtime §6.3, orders §9): the orders an event
 * page names, each as `GET` of that order would send it to the viewer, in three queries (orders,
 * lines, tables). A guest gets only their session's.
 */
class OrderSnapshots(
    private val orders: OrderRepository,
    private val queries: OrderQueryService,
) : Snapshots {
    override val aggregateType: String = OrderEvents.ORDER_AGGREGATE

    override fun load(
        tx: TxScope,
        restaurantId: RestaurantId,
        ids: List<UUID>,
        viewer: Viewer,
    ): List<JsonElement> {
        val rows =
            orders.find(tx, restaurantId, ids.map(::OrderId)).filter {
                viewer !is Viewer.Guest ||
                    it.sessionId == viewer.sessionId
            }
        return queries.assemble(tx, restaurantId, rows).map {
            ApiJson.encodeToJsonElement(OrderBody.serializer(), if (viewer is Viewer.Guest) OrderBody.guest(it) else OrderBody.staff(it))
        }
    }
}
