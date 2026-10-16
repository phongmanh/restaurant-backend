package io.github.phongmanh.restaurant.menu.application

import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.menu.domain.ItemId
import io.github.phongmanh.restaurant.menu.domain.Menu
import io.github.phongmanh.restaurant.menu.domain.MenuItem
import io.github.phongmanh.restaurant.menu.domain.MenuLanguages
import io.github.phongmanh.restaurant.menu.persistence.CategoryRepository
import io.github.phongmanh.restaurant.menu.persistence.ItemRepository
import io.github.phongmanh.restaurant.menu.persistence.MenuStateRepository
import io.github.phongmanh.restaurant.restaurants.application.RestaurantDirectory
import io.github.phongmanh.restaurant.restaurants.application.TenantStatus

/** A menu read's answer: the client's copy is current, or here's the menu. [etag] goes out either way. */
sealed interface MenuRead {
    val etag: String

    data class Current(
        override val etag: String,
    ) : MenuRead

    data class Full(
        override val etag: String,
        val menu: Menu,
        /** The language of a guest's read; `null` for staff, who get both. */
        val lang: String?,
    ) : MenuRead
}

/**
 * The menu reads (menu §2, §4), revalidated by menu version.
 *
 * **In plain words:** each read first looks at one number, the menu version. If the client already
 * holds that version (`If-None-Match`), the answer is `304` with no body. Otherwise the menu is
 * loaded, or taken from [MenuCache], which keeps menus by version, so an entry never goes stale.
 *
 * The version is read before the rows, in one transaction, so a menu is never older than its
 * version: a change committed in between is in the rows already, and the next read sees its new
 * version and loads again.
 */
class MenuReadService(
    private val transactor: Transactor,
    private val states: MenuStateRepository,
    private val categories: CategoryRepository,
    private val items: ItemRepository,
    private val directory: RestaurantDirectory,
    private val tenants: TenantStatus,
    private val cache: MenuCache,
) {
    /**
     * A guest's read (§4.1), in [requestedLang] if the restaurant offers it. [isCurrent] tells
     * whether the client holds the copy with that ETag, `"<version>-<lang>"`.
     */
    suspend fun guest(
        restaurantId: RestaurantId,
        requestedLang: String?,
        isCurrent: (String) -> Boolean,
    ): MenuRead =
        transactor.inTransaction(restaurantId) {
            val version = version(restaurantId)
            val offered = directory.profile(this, restaurantId)?.languages ?: throw DomainError.NotFound()
            val lang = MenuLanguages.resolve(requestedLang, offered)
            val etag = "\"$version-$lang\""
            if (isCurrent(etag)) MenuRead.Current(etag) else MenuRead.Full(etag, menu(restaurantId, version), lang)
        }

    /** A staff member's read (§4.2): everything not deleted, both languages. ETag `"<version>"`. */
    suspend fun staff(
        restaurantId: RestaurantId,
        isCurrent: (String) -> Boolean,
    ): MenuRead =
        transactor.inTransaction(restaurantId) {
            val version = version(restaurantId)
            val etag = "\"$version\""
            if (isCurrent(etag)) MenuRead.Current(etag) else MenuRead.Full(etag, menu(restaurantId, version), null)
        }

    /** An admin's read of any restaurant's menu (§4.3); one that doesn't exist → `404`. */
    suspend fun admin(restaurantId: RestaurantId): Menu =
        transactor.inTransaction(restaurantId) {
            if (tenants.of(this, restaurantId) == null) throw DomainError.NotFound()
            menu(restaurantId, version(restaurantId))
        }

    /** One item, for a staff member of its restaurant; another restaurant's → `404`. */
    suspend fun item(
        restaurantId: RestaurantId,
        id: ItemId,
    ): MenuItem = transactor.inTransaction(restaurantId) { items.find(this, restaurantId, id) ?: throw DomainError.NotFound() }

    private fun TxScope.version(restaurantId: RestaurantId): Long = states.version(this, restaurantId) ?: throw DomainError.NotFound()

    private fun TxScope.menu(
        restaurantId: RestaurantId,
        version: Long,
    ): Menu =
        cache.get(restaurantId, version) ?: Menu(
            version,
            categories.list(this, restaurantId),
            items.list(this, restaurantId).groupBy { it.categoryId },
        ).also { cache.put(restaurantId, it) }
}

/**
 * The last menus built on this instance, by restaurant and version (menu §2). Any change makes a new
 * version, so an entry is never stale and nothing needs invalidating; an old version just ages out.
 * Lost on a cold start, which only costs one load.
 */
class MenuCache(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val entries =
        object : LinkedHashMap<Pair<RestaurantId, Long>, Menu>(capacity, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<RestaurantId, Long>, Menu>) = size > capacity
        }

    fun get(
        restaurantId: RestaurantId,
        version: Long,
    ): Menu? = synchronized(entries) { entries[restaurantId to version] }

    fun put(
        restaurantId: RestaurantId,
        menu: Menu,
    ) {
        synchronized(entries) { entries[restaurantId to menu.version] = menu }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 200
        const val LOAD_FACTOR = 0.75f
    }
}
