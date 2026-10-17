package io.github.phongmanh.restaurant.tables.http

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.http.ApiJson
import io.github.phongmanh.restaurant.realtime.application.Snapshots
import io.github.phongmanh.restaurant.realtime.domain.Viewer
import io.github.phongmanh.restaurant.tables.application.TableEvents
import io.github.phongmanh.restaurant.tables.domain.WaiterCallId
import io.github.phongmanh.restaurant.tables.persistence.WaiterCallRepository
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/**
 * Tables' answer to realtime's `Snapshots` port (realtime §6.3): the waiter calls an event page
 * names, in one query, as `WaiterCall` bodies. A guest gets only their session's.
 */
class WaiterCallSnapshots(
    private val calls: WaiterCallRepository,
) : Snapshots {
    override val aggregateType: String = TableEvents.CALL_AGGREGATE

    override fun load(
        tx: TxScope,
        restaurantId: RestaurantId,
        ids: List<UUID>,
        viewer: Viewer,
    ): List<JsonElement> =
        calls
            .findMany(tx, restaurantId, ids.map(::WaiterCallId))
            .filter { viewer !is Viewer.Guest || it.sessionId == viewer.sessionId }
            .map { ApiJson.encodeToJsonElement(WaiterCallBody.serializer(), WaiterCallBody.of(it)) }
}
