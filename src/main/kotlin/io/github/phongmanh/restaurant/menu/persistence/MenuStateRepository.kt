package io.github.phongmanh.restaurant.menu.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import java.time.Instant

/**
 * `menu_state` (menu §3): a restaurant's menu version, which every menu change increases. Its row
 * is also the restaurant's **menu lock**: every menu write locks it first, so a restaurant's menu
 * writes run one at a time. That makes the limit counts and the name checks safe from races, and
 * a version read under the lock is still current when the write lands.
 */
class MenuStateRepository {
    /** The row of a new restaurant, at version 0 (onboarding, §12). */
    fun create(
        tx: TxScope,
        restaurantId: RestaurantId,
        now: Instant,
    ) {
        connection(tx)
            .prepareStatement("INSERT INTO menu_state (restaurant_id, version, updated_at) VALUES (?, 0, ?)")
            .use { it.bind(restaurantId.value, now).executeUpdate() }
    }

    /** The current version, or `null` if the restaurant has no menu row. */
    fun version(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): Long? =
        connection(tx).prepareStatement("SELECT version FROM menu_state WHERE restaurant_id = ?").use {
            it.bind(restaurantId.value).firstOrNull { rs -> rs.getLong(1) }
        }

    /** Takes the menu lock until the transaction ends. The first lock of every menu write (lock order, CLAUDE.md). */
    fun lock(
        tx: TxScope,
        restaurantId: RestaurantId,
    ) {
        connection(tx).prepareStatement("SELECT version FROM menu_state WHERE restaurant_id = ? FOR UPDATE").use {
            it.bind(restaurantId.value).firstOrNull { rs -> rs.getLong(1) }
        } ?: error("menu_state row missing for a restaurant")
    }

    /** Increases the version; returns the new one. */
    fun bump(
        tx: TxScope,
        restaurantId: RestaurantId,
        now: Instant,
    ): Long =
        connection(tx)
            .prepareStatement("UPDATE menu_state SET version = version + 1, updated_at = ? WHERE restaurant_id = ? RETURNING version")
            .use { it.bind(now, restaurantId.value).firstOrNull { rs -> rs.getLong(1) } }
            ?: error("menu_state row missing for a restaurant")

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)
}
