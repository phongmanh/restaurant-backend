package io.github.phongmanh.restaurant.menu.http

import io.github.phongmanh.restaurant.menu.domain.Category
import io.github.phongmanh.restaurant.menu.domain.Menu
import io.github.phongmanh.restaurant.menu.domain.MenuItem
import io.github.phongmanh.restaurant.menu.domain.MenuLanguages
import io.github.phongmanh.restaurant.menu.domain.MenuOption
import io.github.phongmanh.restaurant.menu.domain.MenuRules
import io.github.phongmanh.restaurant.menu.domain.OptionGroup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Request and response bodies of the menu feature, as `components.schemas` in the contract names
 * them. Request fields are all optional here so that a missing one is a `validation_failed` field
 * error (`required`) rather than an unreadable body. No response has an `image` yet: FR-022 isn't
 * built (TBD-13), and the contract makes the field optional.
 */

/** `Money`: whole đồng as a string (CLAUDE.md, wire formats). */
@Serializable
data class MoneyBody(
    val amount: String? = null,
    val currency: String? = null,
) {
    companion object {
        fun of(amount: Long) = MoneyBody(amount.toString(), MenuRules.CURRENCY)
    }
}

@Serializable
data class CategoryCreateRequest(
    @SerialName("name_vi") val nameVi: String? = null,
    @SerialName("name_en") val nameEn: String? = null,
    val active: Boolean? = null,
)

@Serializable
data class CategoryUpdateRequest(
    @SerialName("name_vi") val nameVi: String? = null,
    @SerialName("name_en") val nameEn: String? = null,
    val active: Boolean? = null,
)

@Serializable
data class CategoryOrderRequest(
    @SerialName("category_ids") val categoryIds: List<String>? = null,
)

@Serializable
data class ItemOrderRequest(
    @SerialName("item_ids") val itemIds: List<String>? = null,
)

@Serializable
data class OptionInputBody(
    val id: String? = null,
    @SerialName("name_vi") val nameVi: String? = null,
    @SerialName("name_en") val nameEn: String? = null,
    @SerialName("price_delta") val priceDelta: MoneyBody? = null,
    val active: Boolean? = null,
)

@Serializable
data class OptionGroupInputBody(
    val id: String? = null,
    @SerialName("name_vi") val nameVi: String? = null,
    @SerialName("name_en") val nameEn: String? = null,
    @SerialName("min_choices") val minChoices: Int? = null,
    @SerialName("max_choices") val maxChoices: Int? = null,
    val options: List<OptionInputBody>? = null,
)

/** `MenuItemCreate`, and as an edit `MenuItemUpdate`: the same fields, all optional there. */
@Serializable
data class ItemRequest(
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("name_vi") val nameVi: String? = null,
    @SerialName("name_en") val nameEn: String? = null,
    @SerialName("description_vi") val descriptionVi: String? = null,
    @SerialName("description_en") val descriptionEn: String? = null,
    val price: MoneyBody? = null,
    val active: Boolean? = null,
    @SerialName("option_groups") val optionGroups: List<OptionGroupInputBody>? = null,
)

@Serializable
data class SoldOutRequest(
    @SerialName("sold_out") val soldOut: Boolean? = null,
)

@Serializable
data class SoldOutState(
    @SerialName("item_id") val itemId: String,
    @SerialName("sold_out") val soldOut: Boolean,
)

@Serializable
data class StaffOption(
    val id: String,
    @SerialName("name_vi") val nameVi: String,
    @SerialName("name_en") val nameEn: String?,
    @SerialName("price_delta") val priceDelta: MoneyBody,
    val active: Boolean,
) {
    companion object {
        fun of(o: MenuOption) = StaffOption(o.id.toString(), o.nameVi, o.nameEn, MoneyBody.of(o.priceDelta), o.active)
    }
}

@Serializable
data class StaffOptionGroup(
    val id: String,
    @SerialName("name_vi") val nameVi: String,
    @SerialName("name_en") val nameEn: String?,
    @SerialName("min_choices") val minChoices: Int,
    @SerialName("max_choices") val maxChoices: Int,
    val options: List<StaffOption>,
) {
    companion object {
        fun of(g: OptionGroup) =
            StaffOptionGroup(g.id.toString(), g.nameVi, g.nameEn, g.minChoices, g.maxChoices, g.options.map(StaffOption::of))
    }
}

@Serializable
data class StaffMenuItem(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("name_vi") val nameVi: String,
    @SerialName("name_en") val nameEn: String?,
    @SerialName("description_vi") val descriptionVi: String?,
    @SerialName("description_en") val descriptionEn: String?,
    val price: MoneyBody,
    val active: Boolean,
    @SerialName("sold_out") val soldOut: Boolean,
    @SerialName("option_groups") val optionGroups: List<StaffOptionGroup>,
    val version: Int,
) {
    companion object {
        fun of(i: MenuItem) =
            StaffMenuItem(
                id = i.id.toString(),
                categoryId = i.categoryId.toString(),
                nameVi = i.nameVi,
                nameEn = i.nameEn,
                descriptionVi = i.descriptionVi,
                descriptionEn = i.descriptionEn,
                price = MoneyBody.of(i.price),
                active = i.active,
                soldOut = i.soldOut,
                optionGroups = i.optionGroups.map(StaffOptionGroup::of),
                version = i.version,
            )
    }
}

/** `StaffMenuCategory`: with its [items] in the menu read, without them when a category is created or edited. */
@Serializable
data class StaffMenuCategory(
    val id: String,
    @SerialName("name_vi") val nameVi: String,
    @SerialName("name_en") val nameEn: String?,
    val active: Boolean,
    val version: Int,
    val items: List<StaffMenuItem>? = null,
) {
    companion object {
        fun of(
            c: Category,
            items: List<MenuItem>? = null,
        ) = StaffMenuCategory(c.id.toString(), c.nameVi, c.nameEn, c.active, c.version, items?.map(StaffMenuItem::of))
    }
}

/** The staff and admin read (§4.2, §4.3): everything not deleted, hidden rows too, both languages. */
@Serializable
data class StaffMenu(
    val version: Long,
    val categories: List<StaffMenuCategory>,
) {
    companion object {
        fun of(menu: Menu) = StaffMenu(menu.version, menu.categories.map { StaffMenuCategory.of(it, menu.items[it.id].orEmpty()) })
    }
}

@Serializable
data class GuestOption(
    val id: String,
    val name: String,
    @SerialName("price_delta") val priceDelta: MoneyBody,
)

@Serializable
data class GuestOptionGroup(
    val id: String,
    val name: String,
    @SerialName("min_choices") val minChoices: Int,
    @SerialName("max_choices") val maxChoices: Int,
    val options: List<GuestOption>,
)

@Serializable
data class GuestMenuItem(
    val id: String,
    val name: String,
    val description: String?,
    val price: MoneyBody,
    @SerialName("sold_out") val soldOut: Boolean,
    @SerialName("option_groups") val optionGroups: List<GuestOptionGroup>,
)

@Serializable
data class GuestMenuCategory(
    val id: String,
    val name: String,
    val items: List<GuestMenuItem>,
)

/**
 * The guest read (§4.1): active categories, items and options only, in display order, each text in
 * [lang] with Vietnamese where the English is missing. Sold-out items are listed, flagged (FR-002).
 */
@Serializable
data class GuestMenu(
    val version: Long,
    val lang: String,
    val categories: List<GuestMenuCategory>,
) {
    companion object {
        fun of(
            menu: Menu,
            lang: String,
        ): GuestMenu {
            fun pick(
                vi: String,
                en: String?,
            ) = MenuLanguages.pick(lang, vi, en)

            fun group(g: OptionGroup) =
                GuestOptionGroup(
                    g.id.toString(),
                    pick(g.nameVi, g.nameEn),
                    g.minChoices,
                    g.maxChoices,
                    g.options.filter { it.active }.map {
                        GuestOption(
                            it.id.toString(),
                            pick(it.nameVi, it.nameEn),
                            MoneyBody.of(it.priceDelta),
                        )
                    },
                )

            fun item(i: MenuItem) =
                GuestMenuItem(
                    i.id.toString(),
                    pick(i.nameVi, i.nameEn),
                    MenuLanguages.pickOptional(lang, i.descriptionVi, i.descriptionEn),
                    MoneyBody.of(i.price),
                    i.soldOut,
                    i.optionGroups.map(::group),
                )
            val categories =
                menu.categories.filter { it.active }.map { c ->
                    GuestMenuCategory(
                        c.id.toString(),
                        pick(c.nameVi, c.nameEn),
                        menu.items[c.id]
                            .orEmpty()
                            .filter { it.active }
                            .map(::item),
                    )
                }
            return GuestMenu(menu.version, lang, categories)
        }
    }
}
