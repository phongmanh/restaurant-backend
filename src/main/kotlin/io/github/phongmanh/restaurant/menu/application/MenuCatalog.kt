package io.github.phongmanh.restaurant.menu.application

import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.persistence.ExposedTransactor
import io.github.phongmanh.restaurant.core.persistence.bind
import io.github.phongmanh.restaurant.core.persistence.list
import io.github.phongmanh.restaurant.core.persistence.uuid
import io.github.phongmanh.restaurant.menu.domain.ItemId
import io.github.phongmanh.restaurant.menu.domain.OptionGroup
import io.github.phongmanh.restaurant.menu.persistence.OptionRepository
import java.util.UUID

/** One order line to price: the item, how many, and the chosen options (orders §3). */
data class LineRequest(
    val itemId: UUID,
    val quantity: Int,
    val optionIds: List<UUID>,
)

/** A chosen option as the order stores it: names and price at the time of the order. */
data class PricedOption(
    val optionId: UUID,
    val groupNameVi: String,
    val groupNameEn: String?,
    val nameVi: String,
    val nameEn: String?,
    val priceDelta: Long,
)

/** A line's snapshot (menu §7): what the order keeps even if the menu changes later. */
data class PricedLine(
    val itemId: UUID,
    val nameVi: String,
    val nameEn: String?,
    val quantity: Int,
    val price: Long,
    val options: List<PricedOption>,
) {
    /** The item's price plus its options' deltas, for one unit. */
    val unitTotal: Long get() = price + options.sumOf { it.priceDelta }
}

/** Why a line can't be ordered (menu §7). `orders` turns these into its response codes. */
enum class LineProblemCode(
    val value: String,
) {
    UNKNOWN_ITEM("unknown_item"),
    ITEM_UNAVAILABLE("item_unavailable"),
    UNKNOWN_OPTION("unknown_option"),
    OPTION_REQUIRED("option_required"),
    TOO_MANY_OPTIONS("too_many_options"),
}

data class LineProblem(
    /** The line's index in the request. */
    val line: Int,
    val code: LineProblemCode,
    val itemId: UUID,
)

/** Either every line priced, or every problem found: all are collected, not just the first. */
data class PricedOrder(
    val lines: List<PricedLine>,
    val problems: List<LineProblem>,
) {
    val ok: Boolean get() = problems.isEmpty()
}

/**
 * The `MenuCatalog` port (menu §7) that `orders` validates and prices order lines with. It runs in
 * the order's own transaction and reads the current rows, so a sold-out or hidden item is caught at
 * order time, not from a cache. Prices always come from the database, never from the client.
 */
fun interface MenuCatalog {
    fun price(
        tx: TxScope,
        restaurantId: RestaurantId,
        lines: List<LineRequest>,
    ): PricedOrder
}

/** [MenuCatalog] in three queries: the items (with their category's `active`), their groups, their options. */
class DatabaseMenuCatalog(
    private val options: OptionRepository,
) : MenuCatalog {
    override fun price(
        tx: TxScope,
        restaurantId: RestaurantId,
        lines: List<LineRequest>,
    ): PricedOrder {
        val items = items(tx, restaurantId, lines.map { it.itemId }.distinct()).associateBy { it.id }
        val groups = options.groupsOf(tx, restaurantId, items.keys.map(::ItemId))
        val priced = mutableListOf<PricedLine>()
        val problems = mutableListOf<LineProblem>()
        lines.forEachIndexed { index, line ->
            val item = items[line.itemId]
            val found =
                when {
                    item == null -> listOf(LineProblemCode.UNKNOWN_ITEM)
                    !item.available -> listOf(LineProblemCode.ITEM_UNAVAILABLE)
                    else -> null
                }
            val (chosen, optionProblems) = choose(groups[ItemId(line.itemId)].orEmpty(), line.optionIds)
            val all = found ?: optionProblems
            if (all.isEmpty() && item != null) {
                priced += PricedLine(item.id, item.nameVi, item.nameEn, line.quantity, item.price, chosen)
            }
            all.forEach { problems += LineProblem(index, it, line.itemId) }
        }
        return PricedOrder(if (problems.isEmpty()) priced else emptyList(), problems)
    }

    /** The chosen options, checked against the item's groups: each one active and of this item, each group within its choices. */
    private fun choose(
        groups: List<OptionGroup>,
        optionIds: List<UUID>,
    ): Pair<List<PricedOption>, List<LineProblemCode>> {
        val wanted = optionIds.toSet()
        val chosen =
            groups.flatMap { group ->
                group.options.filter { it.active && it.id in wanted }.map { group to it }
            }
        val problems = mutableListOf<LineProblemCode>()
        if (chosen.size != wanted.size) problems += LineProblemCode.UNKNOWN_OPTION
        val perGroup = chosen.groupingBy { it.first.id }.eachCount()
        if (groups.any { (perGroup[it.id] ?: 0) < it.minChoices }) problems += LineProblemCode.OPTION_REQUIRED
        if (groups.any { (perGroup[it.id] ?: 0) > it.maxChoices }) problems += LineProblemCode.TOO_MANY_OPTIONS
        val snapshot = chosen.map { (g, o) -> PricedOption(o.id, g.nameVi, g.nameEn, o.nameVi, o.nameEn, o.priceDelta) }
        return snapshot to problems
    }

    private fun items(
        tx: TxScope,
        restaurantId: RestaurantId,
        ids: List<UUID>,
    ): List<CatalogItem> =
        ExposedTransactor
            .connection(tx)
            .prepareStatement(
                """
                SELECT i.id, i.name_vi, i.name_en, i.price, i.active AND c.active AND NOT i.sold_out AS available
                FROM menu_items i JOIN menu_categories c ON c.restaurant_id = i.restaurant_id AND c.id = i.category_id
                WHERE i.restaurant_id = ? AND i.deleted_at IS NULL AND i.id = ANY(?::uuid[])
                """.trimIndent(),
            ).use {
                it.bind(restaurantId.value, ids.map(UUID::toString)).list { rs ->
                    CatalogItem(
                        rs.uuid("id")!!,
                        rs.getString("name_vi"),
                        rs.getString("name_en"),
                        rs.getLong("price"),
                        rs.getBoolean("available"),
                    )
                }
            }

    private class CatalogItem(
        val id: UUID,
        val nameVi: String,
        val nameEn: String?,
        val price: Long,
        /** Active, in an active category, and not sold out (FR-003). */
        val available: Boolean,
    )
}
