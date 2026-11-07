package io.github.phongmanh.restaurant.menu.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.uuid
import io.github.phongmanh.restaurant.menu.domain.ItemId
import java.time.Instant

/**
 * Menu photos in the database (menu §6): an item's `image_key`, and `menu_image_retirements`, the
 * photos that left the menu, kept in the store until `delete_after`, then deleted by the retention
 * job. Every method but [restaurantsDue] takes the restaurant and runs in its transaction;
 * [restaurantsDue] is the job's one platform-wide search.
 */
class MenuImageRepository {
    /**
     * Sets or clears the photo ([imageKey] `null`) without touching the version: like sold-out, it
     * never makes a manager's open edit stale (menu §6). Returns whether it changed.
     */
    fun setImage(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: ItemId,
        imageKey: String?,
        now: Instant,
    ): Boolean =
        connection(tx)
            .prepareStatement(
                "UPDATE menu_items SET image_key = ?::text, updated_at = ? WHERE restaurant_id = ? AND id = ? AND deleted_at IS NULL " +
                    "AND image_key IS DISTINCT FROM ?::text",
            ).use { it.bind(imageKey, now, restaurantId.value, id.value, imageKey).executeUpdate() == 1 }

    /** Schedules [imageKey]'s objects for deletion at [deleteAfter]; a key already scheduled keeps its time. */
    fun retire(
        tx: TxScope,
        restaurantId: RestaurantId,
        imageKey: String,
        deleteAfter: Instant,
    ) {
        connection(tx)
            .prepareStatement(
                "INSERT INTO menu_image_retirements (restaurant_id, image_key, delete_after) VALUES (?, ?, ?) " +
                    "ON CONFLICT (restaurant_id, image_key) DO NOTHING",
            ).use { it.bind(restaurantId.value, imageKey, deleteAfter).executeUpdate() }
    }

    /** A platform transaction: the restaurants with images due by [now]. */
    fun restaurantsDue(
        tx: TxScope,
        now: Instant,
        limit: Int,
    ): List<RestaurantId> =
        connection(tx)
            .prepareStatement("SELECT DISTINCT restaurant_id FROM menu_image_retirements WHERE delete_after <= ? LIMIT ?")
            .use { it.bind(now, limit).list { rs -> RestaurantId(rs.uuid("restaurant_id")!!) } }

    /** At most [limit] of the restaurant's keys due by [now], oldest first. */
    fun due(
        tx: TxScope,
        restaurantId: RestaurantId,
        now: Instant,
        limit: Int,
    ): List<String> =
        connection(tx)
            .prepareStatement(
                "SELECT image_key FROM menu_image_retirements WHERE restaurant_id = ? AND delete_after <= ? " +
                    "ORDER BY delete_after, image_key LIMIT ?",
            ).use { it.bind(restaurantId.value, now, limit).list { rs -> rs.getString("image_key") } }

    /** Forgets [keys] once their objects are gone; returns how many rows went. */
    fun forget(
        tx: TxScope,
        restaurantId: RestaurantId,
        keys: List<String>,
    ): Int =
        connection(tx)
            .prepareStatement("DELETE FROM menu_image_retirements WHERE restaurant_id = ? AND image_key = ANY(?::text[])")
            .use { it.bind(restaurantId.value, keys).executeUpdate() }

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)
}
