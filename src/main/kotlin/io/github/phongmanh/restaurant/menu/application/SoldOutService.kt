package io.github.phongmanh.restaurant.menu.application

import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.menu.domain.ItemId
import io.github.phongmanh.restaurant.menu.persistence.ItemRepository
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sold out (menu §5.3, FR-021). Kitchen staff press it in the middle of service, so it takes no
 * `If-Match` and never fails because a manager is editing the item: it doesn't change the item's
 * version. Setting the current value again changes nothing (no new menu version, no event, no
 * audit entry), so a retry is harmless (§14 Q6).
 */
class SoldOutService(
    private val writer: MenuWriter,
    private val items: ItemRepository,
) {
    /** The item's flag after the call. */
    suspend fun set(
        editor: SoldOutEditor,
        id: ItemId,
        soldOut: Boolean,
        request: RequestMeta,
    ): Boolean =
        writer.write(editor.restaurantId, editor.actor, request) {
            items.find(tx, restaurantId, id) ?: throw DomainError.NotFound()
            if (items.setSoldOut(tx, restaurantId, id, soldOut, now)) {
                changed("menu.item.sold_out_changed", MenuEditService.ITEM_TARGET, id.value, mapOf("sold_out" to JsonPrimitive(soldOut)))
            }
            soldOut
        }
}
