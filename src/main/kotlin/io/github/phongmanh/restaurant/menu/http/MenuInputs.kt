package io.github.phongmanh.restaurant.menu.http

import io.github.phongmanh.restaurant.core.auth.http.Rules
import io.github.phongmanh.restaurant.core.auth.http.Rules.Companion.validate
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.menu.domain.CategoryChanges
import io.github.phongmanh.restaurant.menu.domain.CategoryId
import io.github.phongmanh.restaurant.menu.domain.ItemChanges
import io.github.phongmanh.restaurant.menu.domain.ItemId
import io.github.phongmanh.restaurant.menu.domain.MenuRules
import io.github.phongmanh.restaurant.menu.domain.NewCategory
import io.github.phongmanh.restaurant.menu.domain.NewItem
import io.github.phongmanh.restaurant.menu.domain.OptionGroupInput
import io.github.phongmanh.restaurant.menu.domain.OptionInput
import io.ktor.server.application.ApplicationCall
import java.util.Locale
import java.util.UUID

/*
 * The field rules of menu §5.4, applied to request bodies. Texts are trimmed first; an English
 * name or a description sent as "" removes it. Every broken rule is collected, then one
 * `400 validation_failed` lists them all. The size limits (§3) are `409 menu_limit_reached`, in
 * the service, as they depend on what the menu holds.
 */

fun CategoryCreateRequest.toNewCategory(): NewCategory {
    validate {
        name(nameVi, "/name_vi", required = true)
        optionalName(nameEn, "/name_en")
    }
    return NewCategory(nameVi!!.trim(), nameEn?.trim()?.ifEmpty { null }, active ?: true)
}

fun CategoryUpdateRequest.toChanges(): CategoryChanges {
    validate {
        rule(listOf(nameVi, nameEn, active).any { it != null }, "", "empty")
        name(nameVi, "/name_vi", required = false)
        optionalName(nameEn, "/name_en")
    }
    return CategoryChanges(nameVi?.trim(), nameEn?.trim(), active)
}

fun CategoryOrderRequest.toOrder(): List<CategoryId> = ids(categoryIds, "/category_ids").map(::CategoryId)

fun ItemOrderRequest.toOrder(): List<ItemId> = ids(itemIds, "/item_ids").map(::ItemId)

fun ItemRequest.toNewItem(): NewItem {
    var groups: List<OptionGroupInput> = emptyList()
    var price: Long? = null
    validate {
        rule(categoryId != null && uuid(categoryId) != null, "/category_id", if (categoryId == null) "required" else "invalid_id")
        name(nameVi, "/name_vi", required = true)
        texts(this@toNewItem)
        price = money(this@toNewItem.price, "/price", required = true)
        groups = optionGroups?.let { optionGroups(it, this) }.orEmpty()
    }
    return NewItem(
        categoryId = CategoryId(uuid(categoryId!!)!!),
        nameVi = nameVi!!.trim(),
        nameEn = nameEn?.trim()?.ifEmpty { null },
        descriptionVi = descriptionVi?.trim()?.ifEmpty { null },
        descriptionEn = descriptionEn?.trim()?.ifEmpty { null },
        price = price!!,
        active = active ?: true,
        optionGroups = groups,
    )
}

fun ItemRequest.toChanges(): ItemChanges {
    var groups: List<OptionGroupInput>? = null
    var price: Long? = null
    validate {
        val anything = listOf(categoryId, nameVi, nameEn, descriptionVi, descriptionEn, this@toChanges.price, active, optionGroups)
        rule(anything.any { it != null }, "", "empty")
        categoryId?.let { rule(uuid(it) != null, "/category_id", "invalid_id") }
        name(nameVi, "/name_vi", required = false)
        texts(this@toChanges)
        price = money(this@toChanges.price, "/price", required = false)
        groups = optionGroups?.let { optionGroups(it, this) }
    }
    return ItemChanges(
        categoryId = categoryId?.let { CategoryId(uuid(it)!!) },
        nameVi = nameVi?.trim(),
        nameEn = nameEn?.trim(),
        descriptionVi = descriptionVi?.trim(),
        descriptionEn = descriptionEn?.trim(),
        price = price,
        active = active,
        optionGroups = groups,
    )
}

fun SoldOutRequest.toSoldOut(): Boolean {
    validate { rule(soldOut != null, "/sold_out", "required") }
    return soldOut!!
}

/** A path parameter that names a category; one that isn't a UUID can't name anything → `404`. */
fun ApplicationCall.categoryIdParam(): CategoryId = CategoryId(parameters["categoryId"]?.let(::uuid) ?: throw DomainError.NotFound())

fun ApplicationCall.itemIdParam(): ItemId = ItemId(parameters["itemId"]?.let(::uuid) ?: throw DomainError.NotFound())

private fun Rules.name(
    value: String?,
    field: String,
    required: Boolean,
) {
    if (value == null) {
        rule(!required, field, "required")
    } else {
        rule(MenuRules.name(value.trim()), field, "invalid_length")
    }
}

private fun Rules.optionalName(
    value: String?,
    field: String,
) {
    value?.let { rule(MenuRules.optionalName(it.trim()), field, "invalid_length") }
}

/** The English name and the two descriptions of an item. */
private fun Rules.texts(body: ItemRequest) {
    optionalName(body.nameEn, "/name_en")
    body.descriptionVi?.let { rule(MenuRules.description(it.trim()), "/description_vi", "invalid_length") }
    body.descriptionEn?.let { rule(MenuRules.description(it.trim()), "/description_en", "invalid_length") }
}

/** A Money amount: VND, whole đồng, 0 – 100,000,000. */
private fun Rules.money(
    body: MoneyBody?,
    field: String,
    required: Boolean,
): Long? {
    if (body == null) {
        rule(!required, field, "required")
        return null
    }
    rule(body.currency == MenuRules.CURRENCY, "$field/currency", "invalid_currency")
    val amount = body.amount?.let(MenuRules::amount)
    rule(amount != null, "$field/amount", "invalid_amount")
    return amount
}

private fun optionGroups(
    bodies: List<OptionGroupInputBody>,
    rules: Rules,
): List<OptionGroupInput> {
    val groups = bodies.mapIndexed { i, body -> optionGroup(body, "/option_groups/$i", rules) }
    val groupIds = groups.mapNotNull { it.id }
    val optionIds = groups.flatMap { g -> g.options.mapNotNull { it.id } }
    rules.rule(groupIds.size == groupIds.toSet().size && optionIds.size == optionIds.toSet().size, "/option_groups", "duplicate_id")
    return groups
}

private fun optionGroup(
    body: OptionGroupInputBody,
    field: String,
    rules: Rules,
): OptionGroupInput {
    val id = body.id?.let { raw -> uuid(raw).also { rules.rule(it != null, "$field/id", "invalid_id") } }
    rules.name(body.nameVi, "$field/name_vi", required = true)
    rules.optionalName(body.nameEn, "$field/name_en")
    val min = body.minChoices
    val max = body.maxChoices
    val options = body.options.orEmpty()
    rules.rule(min != null && min >= 0, "$field/min_choices", if (min == null) "required" else "out_of_range")
    rules.rule(max != null && max >= 1, "$field/max_choices", if (max == null) "required" else "out_of_range")
    if (min != null && max != null) rules.rule(min <= max, "$field/min_choices", "above_max_choices")
    rules.rule(options.isNotEmpty(), "$field/options", "required")
    if (max != null && options.isNotEmpty()) rules.rule(max <= options.size, "$field/max_choices", "above_option_count")
    val parsed = options.mapIndexed { j, option -> option(option, "$field/options/$j", rules) }
    return OptionGroupInput(id, body.nameVi?.trim().orEmpty(), body.nameEn?.trim(), min ?: 0, max ?: 1, parsed)
}

/** One option of a group; `price_delta` defaults to 0 and `active` to true. */
private fun option(
    body: OptionInputBody,
    field: String,
    rules: Rules,
): OptionInput {
    val id = body.id?.let { raw -> uuid(raw).also { rules.rule(it != null, "$field/id", "invalid_id") } }
    rules.name(body.nameVi, "$field/name_vi", required = true)
    rules.optionalName(body.nameEn, "$field/name_en")
    val delta = rules.money(body.priceDelta, "$field/price_delta", required = false)
    return OptionInput(id, body.nameVi?.trim().orEmpty(), body.nameEn?.trim(), delta ?: 0, body.active ?: true)
}

/** A list of IDs that names each one once: anything else → `400`. */
private fun ids(
    values: List<String>?,
    field: String,
): List<UUID> {
    val parsed = values?.map(::uuid)
    validate {
        rule(values != null, field, "required")
        rule(parsed == null || parsed.all { it != null }, field, "invalid_id")
        rule(parsed == null || parsed.size == parsed.toSet().size, field, "duplicate_id")
    }
    return parsed!!.map { it!! }
}

private fun uuid(value: String): UUID? =
    runCatching { UUID.fromString(value) }.getOrNull()?.takeIf { it.toString() == value.lowercase(Locale.ROOT) }
