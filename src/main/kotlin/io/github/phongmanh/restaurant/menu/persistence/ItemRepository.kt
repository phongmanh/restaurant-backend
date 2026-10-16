package io.github.phongmanh.restaurant.menu.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.uuid
import io.github.phongmanh.restaurant.menu.domain.CategoryId
import io.github.phongmanh.restaurant.menu.domain.ItemChanges
import io.github.phongmanh.restaurant.menu.domain.ItemId
import io.github.phongmanh.restaurant.menu.domain.MenuItem
import io.github.phongmanh.restaurant.menu.domain.NewItem
import java.sql.ResultSet
import java.time.Instant

/**
 * `menu_items` (menu §3, §5.2, §5.3). Every method takes the restaurant and filters by it; deleted
 * items are never read back. Changes run under the menu lock ([MenuStateRepository.lock]).
 */
class ItemRepository(
    private val options: OptionRepository,
) {
    /** The item with its option groups. */
    fun find(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: ItemId,
    ): MenuItem? = withGroups(tx, restaurantId, "AND id = ?", id.value).firstOrNull()

    /** Every item, hidden ones too, with its option groups; in display order within each category. */
    fun list(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): List<MenuItem> = withGroups(tx, restaurantId, "")

    fun count(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): Int =
        connection(tx).prepareStatement("SELECT count(*) FROM menu_items WHERE restaurant_id = ? AND deleted_at IS NULL").use {
            it.bind(restaurantId.value).firstOrNull { rs -> rs.getInt(1) }
        } ?: 0

    /** Whether another item of [categoryId] already has [nameVi], ignoring case. */
    fun nameTaken(
        tx: TxScope,
        restaurantId: RestaurantId,
        categoryId: CategoryId,
        nameVi: String,
        except: ItemId?,
    ): Boolean =
        connection(tx)
            .prepareStatement(
                "SELECT 1 FROM menu_items WHERE restaurant_id = ? AND category_id = ? AND deleted_at IS NULL " +
                    "AND lower(name_vi) = lower(?) AND id IS DISTINCT FROM ?::uuid",
            ).use { it.bind(restaurantId.value, categoryId.value, nameVi, except?.value).firstOrNull { true } } ?: false

    /** The IDs of the category's items, in display order. */
    fun idsIn(
        tx: TxScope,
        restaurantId: RestaurantId,
        categoryId: CategoryId,
    ): List<ItemId> =
        connection(tx)
            .prepareStatement(
                "SELECT id FROM menu_items WHERE restaurant_id = ? AND category_id = ? AND deleted_at IS NULL ORDER BY display_order, id",
            ).use { it.bind(restaurantId.value, categoryId.value).list { rs -> ItemId(rs.uuid("id")!!) } }

    /** A new item, last in its category, without option groups ([OptionRepository.replaceGroups] adds them). */
    fun insert(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: ItemId,
        item: NewItem,
        now: Instant,
    ) {
        connection(tx)
            .prepareStatement(
                """
                INSERT INTO menu_items (id, restaurant_id, category_id, name_vi, name_en, description_vi, description_en, price,
                    display_order, active, created_at, updated_at)
                VALUES (?, ?, ?, ?, nullif(?::text, ''), nullif(?::text, ''), nullif(?::text, ''), ?,
                    (SELECT coalesce(max(display_order) + 1, 0) FROM menu_items WHERE restaurant_id = ? AND category_id = ?
                     AND deleted_at IS NULL), ?, ?, ?)
                """.trimIndent(),
            ).use {
                it
                    .bind(
                        id.value,
                        restaurantId.value,
                        item.categoryId.value,
                        item.nameVi,
                        item.nameEn,
                        item.descriptionVi,
                        item.descriptionEn,
                        item.price,
                        restaurantId.value,
                        item.categoryId.value,
                        item.active,
                        now,
                        now,
                    ).executeUpdate()
            }
    }

    /**
     * Applies [changes] (all but the option groups) and increases the version; the caller checked
     * the version under the menu lock. A new category moves the item to the end of that category.
     */
    fun update(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: ItemId,
        changes: ItemChanges,
        now: Instant,
    ) {
        val category = changes.categoryId?.value
        connection(tx)
            .prepareStatement(
                """
                UPDATE menu_items SET
                  display_order = CASE WHEN ?::uuid IS NOT NULL AND ?::uuid <> category_id
                    THEN (SELECT coalesce(max(display_order) + 1, 0) FROM menu_items
                          WHERE restaurant_id = ? AND category_id = ?::uuid AND deleted_at IS NULL)
                    ELSE display_order END,
                  category_id = coalesce(?::uuid, category_id),
                  name_vi = coalesce(?, name_vi),
                  name_en = CASE WHEN ?::text IS NULL THEN name_en ELSE nullif(?::text, '') END,
                  description_vi = CASE WHEN ?::text IS NULL THEN description_vi ELSE nullif(?::text, '') END,
                  description_en = CASE WHEN ?::text IS NULL THEN description_en ELSE nullif(?::text, '') END,
                  price = coalesce(?::bigint, price),
                  active = coalesce(?::boolean, active),
                  updated_at = ?, version = version + 1
                WHERE restaurant_id = ? AND id = ? AND deleted_at IS NULL
                """.trimIndent(),
            ).use {
                it
                    .bind(
                        category,
                        category,
                        restaurantId.value,
                        category,
                        category,
                        changes.nameVi,
                        changes.nameEn,
                        changes.nameEn,
                        changes.descriptionVi,
                        changes.descriptionVi,
                        changes.descriptionEn,
                        changes.descriptionEn,
                        changes.price,
                        changes.active,
                        now,
                        restaurantId.value,
                        id.value,
                    ).executeUpdate()
            }
    }

    /** Soft delete (FR-020): order lines keep their snapshot, and the name is free again. */
    fun delete(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: ItemId,
        now: Instant,
    ) {
        connection(tx)
            .prepareStatement(
                "UPDATE menu_items SET deleted_at = ?, updated_at = ?, version = version + 1 " +
                    "WHERE restaurant_id = ? AND id = ? AND deleted_at IS NULL",
            ).use { it.bind(now, now, restaurantId.value, id.value).executeUpdate() }
    }

    /** Sets the flag without touching the version (§5.3); returns whether it changed. */
    fun setSoldOut(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: ItemId,
        soldOut: Boolean,
        now: Instant,
    ): Boolean =
        connection(tx)
            .prepareStatement(
                "UPDATE menu_items SET sold_out = ?, updated_at = ? WHERE restaurant_id = ? AND id = ? AND deleted_at IS NULL " +
                    "AND sold_out <> ?",
            ).use { it.bind(soldOut, now, restaurantId.value, id.value, soldOut).executeUpdate() == 1 }

    /** Sets `display_order` to 0…n−1 in the order of [ids], which name every item of the category. Versions don't change. */
    fun reorder(
        tx: TxScope,
        restaurantId: RestaurantId,
        categoryId: CategoryId,
        ids: List<ItemId>,
    ) {
        connection(tx)
            .prepareStatement(
                "UPDATE menu_items m SET display_order = o.ord - 1 FROM unnest(?::uuid[]) WITH ORDINALITY AS o(id, ord) " +
                    "WHERE m.restaurant_id = ? AND m.category_id = ? AND m.id = o.id",
            ).use { it.bind(ids.map(ItemId::toString), restaurantId.value, categoryId.value).executeUpdate() }
    }

    private fun withGroups(
        tx: TxScope,
        restaurantId: RestaurantId,
        condition: String,
        vararg params: Any?,
    ): List<MenuItem> {
        val rows =
            connection(tx)
                .prepareStatement(
                    "SELECT id, category_id, name_vi, name_en, description_vi, description_en, price, active, sold_out, image_key, " +
                        "version FROM menu_items WHERE restaurant_id = ? AND deleted_at IS NULL $condition ORDER BY display_order, id",
                ).use { it.bind(listOf(restaurantId.value) + params).list(::item) }
        val groups = options.groupsOf(tx, restaurantId, rows.map { it.id })
        return rows.map { it.copy(optionGroups = groups[it.id].orEmpty()) }
    }

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)

    private companion object {
        fun item(rs: ResultSet) =
            MenuItem(
                id = ItemId(rs.uuid("id")!!),
                categoryId = CategoryId(rs.uuid("category_id")!!),
                nameVi = rs.getString("name_vi"),
                nameEn = rs.getString("name_en"),
                descriptionVi = rs.getString("description_vi"),
                descriptionEn = rs.getString("description_en"),
                price = rs.getLong("price"),
                active = rs.getBoolean("active"),
                soldOut = rs.getBoolean("sold_out"),
                imageKey = rs.getString("image_key"),
                version = rs.getInt("version"),
                optionGroups = emptyList(),
            )
    }
}
