package io.github.phongmanh.restaurant.menu.persistence

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.IdGenerator
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.firstOrNull
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.uuid
import io.github.phongmanh.restaurant.menu.domain.ItemId
import io.github.phongmanh.restaurant.menu.domain.MenuOption
import io.github.phongmanh.restaurant.menu.domain.OptionGroup
import io.github.phongmanh.restaurant.menu.domain.OptionGroupInput
import io.github.phongmanh.restaurant.menu.domain.OptionInput
import java.time.Instant
import java.util.UUID

/**
 * `menu_option_groups` and `menu_options` (menu §3, FR-023). Every method takes the restaurant and
 * filters by it. Groups and options are soft-deleted, as order lines keep their IDs.
 */
class OptionRepository(
    private val ids: IdGenerator,
) {
    /** The groups of [itemIds], each with its options (inactive ones too), both in display order. Two queries. */
    fun groupsOf(
        tx: TxScope,
        restaurantId: RestaurantId,
        itemIds: List<ItemId>,
    ): Map<ItemId, List<OptionGroup>> {
        val groups = if (itemIds.isEmpty()) emptyList() else groupRows(tx, restaurantId, itemIds)
        val options = if (groups.isEmpty()) emptyMap() else optionsOf(tx, restaurantId, groups.map { it.id })
        return groups.groupBy({ it.itemId }) { g ->
            OptionGroup(g.id, g.nameVi, g.nameEn, g.minChoices, g.maxChoices, options[g.id].orEmpty())
        }
    }

    /**
     * Replaces the item's whole set with [groups] (menu §5.2): a group or option with an `id` is kept
     * and updated, one without is created, and every other one is soft-deleted. The caller checked
     * that each `id` is one of this item's groups, or of that group's options.
     */
    fun replaceGroups(
        tx: TxScope,
        restaurantId: RestaurantId,
        itemId: ItemId,
        groups: List<OptionGroupInput>,
        now: Instant,
    ) {
        connection(tx)
            .prepareStatement(
                "UPDATE menu_option_groups SET deleted_at = ? WHERE restaurant_id = ? AND item_id = ? AND deleted_at IS NULL " +
                    "AND NOT (id = ANY(?::uuid[]))",
            ).use { it.bind(now, restaurantId.value, itemId.value, groups.mapNotNull { g -> g.id?.toString() }).executeUpdate() }
        groups.forEachIndexed { order, group ->
            val groupId =
                group.id?.also { updateGroup(tx, restaurantId, it, group, order) } ?: insertGroup(tx, restaurantId, itemId, group, order)
            replaceOptions(tx, restaurantId, groupId, group.options, now)
        }
    }

    /** Undeleted options of undeleted groups of undeleted items: what the 5,000 limit counts (§3). */
    fun countOptions(
        tx: TxScope,
        restaurantId: RestaurantId,
    ): Int =
        connection(tx)
            .prepareStatement(
                """
                SELECT count(*) FROM menu_options o
                  JOIN menu_option_groups g ON g.restaurant_id = o.restaurant_id AND g.id = o.group_id AND g.deleted_at IS NULL
                  JOIN menu_items i ON i.restaurant_id = g.restaurant_id AND i.id = g.item_id AND i.deleted_at IS NULL
                WHERE o.restaurant_id = ? AND o.deleted_at IS NULL
                """.trimIndent(),
            ).use { it.bind(restaurantId.value).firstOrNull { rs -> rs.getInt(1) } } ?: 0

    private fun groupRows(
        tx: TxScope,
        restaurantId: RestaurantId,
        itemIds: List<ItemId>,
    ): List<GroupRow> =
        connection(tx)
            .prepareStatement(
                "SELECT id, item_id, name_vi, name_en, min_choices, max_choices FROM menu_option_groups " +
                    "WHERE restaurant_id = ? AND deleted_at IS NULL AND item_id = ANY(?::uuid[]) ORDER BY display_order, id",
            ).use {
                it.bind(restaurantId.value, itemIds.map(ItemId::toString)).list { rs ->
                    GroupRow(
                        rs.uuid("id")!!,
                        ItemId(rs.uuid("item_id")!!),
                        rs.getString("name_vi"),
                        rs.getString("name_en"),
                        rs.getInt("min_choices"),
                        rs.getInt("max_choices"),
                    )
                }
            }

    /** The options of [groupIds], by group, in display order. */
    private fun optionsOf(
        tx: TxScope,
        restaurantId: RestaurantId,
        groupIds: List<UUID>,
    ): Map<UUID, List<MenuOption>> =
        connection(tx)
            .prepareStatement(
                "SELECT id, group_id, name_vi, name_en, price_delta, active FROM menu_options " +
                    "WHERE restaurant_id = ? AND deleted_at IS NULL AND group_id = ANY(?::uuid[]) ORDER BY display_order, id",
            ).use {
                it.bind(restaurantId.value, groupIds.map(UUID::toString)).list { rs ->
                    rs.uuid("group_id")!! to
                        MenuOption(
                            rs.uuid("id")!!,
                            rs.getString("name_vi"),
                            rs.getString("name_en"),
                            rs.getLong("price_delta"),
                            rs.getBoolean("active"),
                        )
                }
            }.groupBy({ it.first }, { it.second })

    private fun insertGroup(
        tx: TxScope,
        restaurantId: RestaurantId,
        itemId: ItemId,
        group: OptionGroupInput,
        order: Int,
    ): UUID {
        val id = ids.next()
        connection(tx)
            .prepareStatement(
                "INSERT INTO menu_option_groups (id, restaurant_id, item_id, name_vi, name_en, min_choices, max_choices, display_order) " +
                    "VALUES (?, ?, ?, ?, nullif(?::text, ''), ?, ?, ?)",
            ).use {
                it
                    .bind(id, restaurantId.value, itemId.value, group.nameVi, group.nameEn, group.minChoices, group.maxChoices, order)
                    .executeUpdate()
            }
        return id
    }

    private fun updateGroup(
        tx: TxScope,
        restaurantId: RestaurantId,
        id: UUID,
        group: OptionGroupInput,
        order: Int,
    ) {
        connection(tx)
            .prepareStatement(
                "UPDATE menu_option_groups SET name_vi = ?, name_en = nullif(?::text, ''), min_choices = ?, max_choices = ?, " +
                    "display_order = ? WHERE restaurant_id = ? AND id = ?",
            ).use {
                it
                    .bind(group.nameVi, group.nameEn, group.minChoices, group.maxChoices, order, restaurantId.value, id)
                    .executeUpdate()
            }
    }

    private fun replaceOptions(
        tx: TxScope,
        restaurantId: RestaurantId,
        groupId: UUID,
        options: List<OptionInput>,
        now: Instant,
    ) {
        connection(tx)
            .prepareStatement(
                "UPDATE menu_options SET deleted_at = ? WHERE restaurant_id = ? AND group_id = ? AND deleted_at IS NULL " +
                    "AND NOT (id = ANY(?::uuid[]))",
            ).use { it.bind(now, restaurantId.value, groupId, options.mapNotNull { o -> o.id?.toString() }).executeUpdate() }
        options.forEachIndexed { order, option ->
            val existing = option.id
            if (existing != null) {
                connection(tx)
                    .prepareStatement(
                        "UPDATE menu_options SET name_vi = ?, name_en = nullif(?::text, ''), price_delta = ?, active = ?, " +
                            "display_order = ? WHERE restaurant_id = ? AND id = ?",
                    ).use {
                        it
                            .bind(option.nameVi, option.nameEn, option.priceDelta, option.active, order, restaurantId.value, existing)
                            .executeUpdate()
                    }
            } else {
                connection(tx)
                    .prepareStatement(
                        "INSERT INTO menu_options (id, restaurant_id, group_id, name_vi, name_en, price_delta, display_order, active) " +
                            "VALUES (?, ?, ?, ?, nullif(?::text, ''), ?, ?, ?)",
                    ).use {
                        it
                            .bind(
                                ids.next(),
                                restaurantId.value,
                                groupId,
                                option.nameVi,
                                option.nameEn,
                                option.priceDelta,
                                order,
                                option.active,
                            ).executeUpdate()
                    }
            }
        }
    }

    private fun connection(tx: TxScope) = ExposedTransactor.connection(tx)

    private class GroupRow(
        val id: UUID,
        val itemId: ItemId,
        val nameVi: String,
        val nameEn: String?,
        val minChoices: Int,
        val maxChoices: Int,
    )
}
