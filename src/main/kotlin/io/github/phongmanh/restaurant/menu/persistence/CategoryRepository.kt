package io.github.phongmanh.restaurant.menu.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.uuid
import io.github.phongmanh.restaurant.menu.domain.Category
import io.github.phongmanh.restaurant.menu.domain.CategoryChanges
import io.github.phongmanh.restaurant.menu.domain.CategoryId
import io.github.phongmanh.restaurant.menu.domain.NewCategory
import java.sql.ResultSet
import java.time.Instant

/**
 * `menu_categories` (menu §3, §5.1). Every method takes the restaurant and filters by it; deleted
 * categories are never read back. Changes run under the menu lock ([MenuStateRepository.lock]).
 */
class CategoryRepository {
    fun find(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: CategoryId,
    ): Category? =
        connection(tx).prepareStatement("$SELECT AND id = ?").use {
            it.bind(restaurantId.value, id.value).firstOrNull(::category)
        }

    /** Every category, in display order. */
    fun list(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): List<Category> =
        connection(tx).prepareStatement("$SELECT ORDER BY display_order, id").use {
            it.bind(restaurantId.value).list(::category)
        }

    fun count(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): Int = countOf(tx, "SELECT count(*) FROM menu_categories WHERE restaurant_id = ? AND deleted_at IS NULL", restaurantId.value)

    /** Whether another category already has [nameVi], ignoring case. */
    fun nameTaken(
        tx: TxScope,
        restaurantId: RestaurantId,
        nameVi: String,
        except: CategoryId?,
    ): Boolean =
        connection(tx)
            .prepareStatement(
                "SELECT 1 FROM menu_categories WHERE restaurant_id = ? AND deleted_at IS NULL AND lower(name_vi) = lower(?) " +
                    "AND id IS DISTINCT FROM ?::uuid",
            ).use { it.bind(restaurantId.value, nameVi, except?.value).firstOrNull { true } } ?: false

    /** A new category, last in the order. */
    fun insert(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: CategoryId,
        category: NewCategory,
        now: Instant,
    ) {
        connection(tx)
            .prepareStatement(
                """
                INSERT INTO menu_categories (id, restaurant_id, name_vi, name_en, display_order, active, created_at, updated_at)
                VALUES (?, ?, ?, nullif(?::text, ''), (SELECT coalesce(max(display_order) + 1, 0) FROM menu_categories
                                                 WHERE restaurant_id = ? AND deleted_at IS NULL), ?, ?, ?)
                """.trimIndent(),
            ).use {
                it
                    .bind(id.value, restaurantId.value, category.nameVi, category.nameEn, restaurantId.value, category.active, now, now)
                    .executeUpdate()
            }
    }

    /** Applies [changes] and increases the version; the caller checked the version under the menu lock. */
    fun update(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: CategoryId,
        changes: CategoryChanges,
        now: Instant,
    ) {
        connection(tx)
            .prepareStatement(
                """
                UPDATE menu_categories SET name_vi = coalesce(?, name_vi),
                  name_en = CASE WHEN ?::text IS NULL THEN name_en ELSE nullif(?::text, '') END,
                  active = coalesce(?::boolean, active), updated_at = ?, version = version + 1
                WHERE restaurant_id = ? AND id = ? AND deleted_at IS NULL
                """.trimIndent(),
            ).use {
                it
                    .bind(changes.nameVi, changes.nameEn, changes.nameEn, changes.active, now, restaurantId.value, id.value)
                    .executeUpdate()
            }
    }

    fun delete(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: CategoryId,
        now: Instant,
    ) {
        connection(tx)
            .prepareStatement(
                "UPDATE menu_categories SET deleted_at = ?, updated_at = ?, version = version + 1 " +
                    "WHERE restaurant_id = ? AND id = ? AND deleted_at IS NULL",
            ).use { it.bind(now, now, restaurantId.value, id.value).executeUpdate() }
    }

    /** Sets `display_order` to 0…n−1 in the order of [ids], which name every category. Versions don't change. */
    fun reorder(
        tx: TxScope,
        restaurantId: RestaurantId,
        ids: List<CategoryId>,
    ) {
        connection(tx)
            .prepareStatement(
                "UPDATE menu_categories c SET display_order = o.ord - 1 FROM unnest(?::uuid[]) WITH ORDINALITY AS o(id, ord) " +
                    "WHERE c.restaurant_id = ? AND c.id = o.id",
            ).use { it.bind(ids.map { id -> id.toString() }, restaurantId.value).executeUpdate() }
    }

    /** How many items the category holds, hidden ones included. */
    fun itemCount(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: CategoryId,
    ): Int =
        countOf(
            tx,
            "SELECT count(*) FROM menu_items WHERE restaurant_id = ? AND category_id = ? AND deleted_at IS NULL",
            restaurantId.value,
            id.value,
        )

    private fun countOf(
        tx: TxScope,
        sql: String,
        vararg params: Any?,
    ): Int = connection(tx).prepareStatement(sql).use { it.bind(*params).firstOrNull { rs -> rs.getInt(1) } } ?: 0

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)

    private companion object {
        const val SELECT =
            "SELECT id, name_vi, name_en, active, version FROM menu_categories WHERE restaurant_id = ? AND deleted_at IS NULL"

        fun category(rs: ResultSet) =
            Category(
                id = CategoryId(rs.uuid("id")!!),
                nameVi = rs.getString("name_vi"),
                nameEn = rs.getString("name_en"),
                active = rs.getBoolean("active"),
                version = rs.getInt("version"),
            )
    }
}
