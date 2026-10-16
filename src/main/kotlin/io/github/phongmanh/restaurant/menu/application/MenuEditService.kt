package io.github.phongmanh.restaurant.menu.application

import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import io.github.phongmanh.restaurant.core.domain.IdGenerator
import io.github.phongmanh.restaurant.menu.domain.Category
import io.github.phongmanh.restaurant.menu.domain.CategoryChanges
import io.github.phongmanh.restaurant.menu.domain.CategoryId
import io.github.phongmanh.restaurant.menu.domain.ItemChanges
import io.github.phongmanh.restaurant.menu.domain.ItemId
import io.github.phongmanh.restaurant.menu.domain.MenuItem
import io.github.phongmanh.restaurant.menu.domain.MenuLimits
import io.github.phongmanh.restaurant.menu.domain.NewCategory
import io.github.phongmanh.restaurant.menu.domain.NewItem
import io.github.phongmanh.restaurant.menu.domain.OptionGroupInput
import io.github.phongmanh.restaurant.menu.persistence.CategoryRepository
import io.github.phongmanh.restaurant.menu.persistence.ItemRepository
import io.github.phongmanh.restaurant.menu.persistence.OptionRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A manager's menu edits (menu §5.1, §5.2, FR-020). Each runs through [MenuWriter], so it holds the
 * menu lock, bumps the menu version, and writes its `MenuChanged` event and audit entry with it.
 *
 * Checks come in the order of core-and-auth §3: a missing row → `404`, then a stale `If-Match` →
 * `412`, then a broken rule → `409`. Another restaurant's ID is `404`, as every lookup is by the
 * editor's restaurant. The version read under the lock is still current when the update lands.
 */
class MenuEditService(
    private val writer: MenuWriter,
    private val categories: CategoryRepository,
    private val items: ItemRepository,
    private val options: OptionRepository,
    private val ids: IdGenerator,
) {
    /** A new category, last in the order. */
    suspend fun createCategory(
        editor: MenuEditor,
        category: NewCategory,
        request: RequestMeta,
    ): Category =
        write(editor, request) {
            if (categories.count(tx, restaurantId) >= MenuLimits.CATEGORIES) throw DomainError.MenuLimitReached()
            if (categories.nameTaken(tx, restaurantId, category.nameVi, null)) throw DomainError.DuplicateName()
            val id = CategoryId(ids.next())
            categories.insert(tx, restaurantId, id, category, now)
            changed("menu.category.created", CATEGORY_TARGET, id.value)
            categories.find(tx, restaurantId, id)!!
        }

    suspend fun updateCategory(
        editor: MenuEditor,
        id: CategoryId,
        version: Int,
        changes: CategoryChanges,
        request: RequestMeta,
    ): Category =
        write(editor, request) {
            val current = categories.find(tx, restaurantId, id) ?: throw DomainError.NotFound()
            if (current.version != version) throw DomainError.PreconditionFailed()
            val name = changes.nameVi
            if (name != null && categories.nameTaken(tx, restaurantId, name, id)) throw DomainError.DuplicateName()
            categories.update(tx, restaurantId, id, changes, now)
            changed("menu.category.updated", CATEGORY_TARGET, id.value, fields(changes.fields))
            categories.find(tx, restaurantId, id)!!
        }

    /** Only an empty category (§14 Q7); one with items → `409 category_not_empty`. */
    suspend fun deleteCategory(
        editor: MenuEditor,
        id: CategoryId,
        version: Int,
        request: RequestMeta,
    ) = write(editor, request) {
        val current = categories.find(tx, restaurantId, id) ?: throw DomainError.NotFound()
        if (current.version != version) throw DomainError.PreconditionFailed()
        if (categories.itemCount(tx, restaurantId, id) > 0) throw DomainError.CategoryNotEmpty()
        categories.delete(tx, restaurantId, id, now)
        changed("menu.category.deleted", CATEGORY_TARGET, id.value)
    }

    /** [order] must name every category exactly once; a stale list → `400` (§14 Q6). */
    suspend fun reorderCategories(
        editor: MenuEditor,
        order: List<CategoryId>,
        request: RequestMeta,
    ) = write(editor, request) {
        val current = categories.list(tx, restaurantId).map { it.id }
        requireEveryOnce(order, current, "/category_ids")
        categories.reorder(tx, restaurantId, order)
        changed("menu.reordered", null, null, mapOf("list" to JsonPrimitive("categories")))
    }

    /** A new item, last in its category. */
    suspend fun createItem(
        editor: MenuEditor,
        item: NewItem,
        request: RequestMeta,
    ): MenuItem =
        write(editor, request) {
            categories.find(tx, restaurantId, item.categoryId) ?: throw DomainError.NotFound()
            if (items.count(tx, restaurantId) >= MenuLimits.ITEMS) throw DomainError.MenuLimitReached()
            checkGroupLimits(item.optionGroups)
            if (items.nameTaken(tx, restaurantId, item.categoryId, item.nameVi, null)) throw DomainError.DuplicateName()
            val id = ItemId(ids.next())
            items.insert(tx, restaurantId, id, item, now)
            replaceGroups(id, item.optionGroups)
            changed("menu.item.created", ITEM_TARGET, id.value, mapOf("price" to JsonPrimitive(item.price.toString())))
            items.find(tx, restaurantId, id)!!
        }

    /**
     * Any field (§5.2). A new category moves the item to its end; `option_groups` replaces the whole
     * set, and an `id` there that isn't this item's group, or that group's option, → `404`.
     */
    suspend fun updateItem(
        editor: MenuEditor,
        id: ItemId,
        version: Int,
        changes: ItemChanges,
        request: RequestMeta,
    ): MenuItem =
        write(editor, request) {
            val current = items.find(tx, restaurantId, id) ?: throw DomainError.NotFound()
            changes.categoryId?.let { categories.find(tx, restaurantId, it) ?: throw DomainError.NotFound() }
            changes.optionGroups?.let { requireOwnIds(current, it) }
            if (current.version != version) throw DomainError.PreconditionFailed()
            changes.optionGroups?.let { checkGroupLimits(it) }
            val category = changes.categoryId ?: current.categoryId
            if ((changes.nameVi != null || category != current.categoryId) &&
                items.nameTaken(tx, restaurantId, category, changes.nameVi ?: current.nameVi, id)
            ) {
                throw DomainError.DuplicateName()
            }
            items.update(tx, restaurantId, id, changes, now)
            changes.optionGroups?.let { replaceGroups(id, it) }
            changed("menu.item.updated", ITEM_TARGET, id.value, itemDetails(current, changes))
            items.find(tx, restaurantId, id)!!
        }

    /** Soft delete (FR-020): gone from every menu view; past order lines keep their snapshot. */
    suspend fun deleteItem(
        editor: MenuEditor,
        id: ItemId,
        version: Int,
        request: RequestMeta,
    ) = write(editor, request) {
        val current = items.find(tx, restaurantId, id) ?: throw DomainError.NotFound()
        if (current.version != version) throw DomainError.PreconditionFailed()
        items.delete(tx, restaurantId, id, now)
        changed("menu.item.deleted", ITEM_TARGET, id.value)
    }

    /** [order] must name every item of the category exactly once. */
    suspend fun reorderItems(
        editor: MenuEditor,
        categoryId: CategoryId,
        order: List<ItemId>,
        request: RequestMeta,
    ) = write(editor, request) {
        categories.find(tx, restaurantId, categoryId) ?: throw DomainError.NotFound()
        requireEveryOnce(order, items.idsIn(tx, restaurantId, categoryId), "/item_ids")
        items.reorder(tx, restaurantId, categoryId, order)
        changed("menu.reordered", CATEGORY_TARGET, categoryId.value, mapOf("list" to JsonPrimitive("items")))
    }

    private suspend fun <T> write(
        editor: MenuEditor,
        request: RequestMeta,
        block: suspend MenuWrite.() -> T,
    ): T = writer.write(editor.restaurantId, editor.actor, request, block)

    /** Writes the groups, then holds the restaurant to its 5,000 options; past it, the whole write rolls back. */
    private fun MenuWrite.replaceGroups(
        id: ItemId,
        groups: List<OptionGroupInput>,
    ) {
        options.replaceGroups(tx, restaurantId, id, groups, now)
        if (groups.isNotEmpty() && options.countOptions(tx, restaurantId) > MenuLimits.OPTIONS) throw DomainError.MenuLimitReached()
    }

    private fun checkGroupLimits(groups: List<OptionGroupInput>) {
        if (groups.size > MenuLimits.GROUPS_PER_ITEM || groups.any { it.options.size > MenuLimits.OPTIONS_PER_GROUP }) {
            throw DomainError.MenuLimitReached()
        }
    }

    /** Every group `id` is one of the item's groups, and every option `id` one of that group's options. */
    private fun requireOwnIds(
        item: MenuItem,
        groups: List<OptionGroupInput>,
    ) {
        val existing = item.optionGroups.associateBy { it.id }
        for (group in groups) {
            val own =
                group.id
                    ?.let { existing[it] ?: throw DomainError.NotFound() }
                    ?.options
                    ?.map { it.id }
                    ?.toSet()
                    .orEmpty()
            if (group.options.any { it.id != null && it.id !in own }) throw DomainError.NotFound()
        }
    }

    private fun <T> requireEveryOnce(
        order: List<T>,
        current: List<T>,
        field: String,
    ) {
        if (order.size != current.size || order.toSet() != current.toSet()) {
            throw DomainError.ValidationFailed(listOf(FieldError(field, "must_list_each_once", "list every one exactly once")))
        }
    }

    /** The fields the edit set; for a new price, from → to as well (NFR-SEC-03 "price edits"). */
    private fun itemDetails(
        current: MenuItem,
        changes: ItemChanges,
    ): Map<String, JsonElement> {
        val details = fields(changes.fields).toMutableMap()
        val price = changes.price
        if (price != null && price != current.price) {
            details["price"] = JsonObject(mapOf("from" to JsonPrimitive(current.price.toString()), "to" to JsonPrimitive(price.toString())))
        }
        return details
    }

    private fun fields(names: List<String>): Map<String, JsonElement> = mapOf("fields" to JsonArray(names.map(::JsonPrimitive)))

    companion object {
        const val CATEGORY_TARGET = "menu_category"
        const val ITEM_TARGET = "menu_item"
    }
}
