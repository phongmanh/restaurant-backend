package io.github.phongmanh.restaurant.menu.domain

import java.util.UUID

/*
 * The menu (menu §3): categories hold items, an item may have option groups (FR-023), and every
 * text is Vietnamese, with English optional (FR-024). Prices are whole đồng, VAT included. A deleted
 * row is never read back here: the repository leaves deleted rows out of every menu view.
 */

@JvmInline
value class CategoryId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class ItemId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

data class Category(
    val id: CategoryId,
    val nameVi: String,
    val nameEn: String?,
    /** `false`: hidden from guests, with all its items. */
    val active: Boolean,
    val version: Int,
)

data class MenuItem(
    val id: ItemId,
    val categoryId: CategoryId,
    val nameVi: String,
    val nameEn: String?,
    val descriptionVi: String?,
    val descriptionEn: String?,
    val price: Long,
    val active: Boolean,
    val soldOut: Boolean,
    /** The storage key of the item's photo (menu §6); always `null` until FR-022 is built. */
    val imageKey: String?,
    val version: Int,
    val optionGroups: List<OptionGroup>,
)

data class OptionGroup(
    val id: UUID,
    val nameVi: String,
    val nameEn: String?,
    /** 0 = optional, ≥ 1 = required. */
    val minChoices: Int,
    /** 1 = single choice. */
    val maxChoices: Int,
    val options: List<MenuOption>,
)

data class MenuOption(
    val id: UUID,
    val nameVi: String,
    val nameEn: String?,
    /** Added to the item's price; never negative (§14 Q5). */
    val priceDelta: Long,
    val active: Boolean,
)

/** A restaurant's whole menu at [version], every category and item in display order. */
data class Menu(
    val version: Long,
    val categories: List<Category>,
    /** The items of each category, in display order; a category without items has an empty list. */
    val items: Map<CategoryId, List<MenuItem>>,
)

/*
 * Changes, already checked against the field rules (§5.4). An optional English text or description
 * is `null` to leave it as it is, and `""` to remove it.
 */

data class NewCategory(
    val nameVi: String,
    val nameEn: String?,
    val active: Boolean,
)

data class CategoryChanges(
    val nameVi: String?,
    val nameEn: String?,
    val active: Boolean?,
) {
    /** The contract's field names of what this edit sets, for the audit entry. */
    val fields: List<String>
        get() = listOfNotNull(nameVi?.let { "name_vi" }, nameEn?.let { "name_en" }, active?.let { "active" })
}

data class OptionInput(
    /** Keep and update this option of the group; `null` creates one. */
    val id: UUID?,
    val nameVi: String,
    val nameEn: String?,
    val priceDelta: Long,
    val active: Boolean,
)

data class OptionGroupInput(
    /** Keep and update this group of the item; `null` creates one. */
    val id: UUID?,
    val nameVi: String,
    val nameEn: String?,
    val minChoices: Int,
    val maxChoices: Int,
    val options: List<OptionInput>,
)

data class NewItem(
    val categoryId: CategoryId,
    val nameVi: String,
    val nameEn: String?,
    val descriptionVi: String?,
    val descriptionEn: String?,
    val price: Long,
    val active: Boolean,
    val optionGroups: List<OptionGroupInput>,
)

data class ItemChanges(
    val categoryId: CategoryId?,
    val nameVi: String?,
    val nameEn: String?,
    val descriptionVi: String?,
    val descriptionEn: String?,
    val price: Long?,
    val active: Boolean?,
    /** When set, replaces the whole set (§5.2). */
    val optionGroups: List<OptionGroupInput>?,
) {
    val fields: List<String>
        get() =
            listOfNotNull(
                categoryId?.let { "category_id" },
                nameVi?.let { "name_vi" },
                nameEn?.let { "name_en" },
                descriptionVi?.let { "description_vi" },
                descriptionEn?.let { "description_en" },
                price?.let { "price" },
                active?.let { "active" },
                optionGroups?.let { "option_groups" },
            )
}
