package io.github.phongmanh.restaurant.menu.http

import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.http.Endpoints
import io.github.phongmanh.restaurant.core.http.adminCaller
import io.github.phongmanh.restaurant.core.http.caller
import io.github.phongmanh.restaurant.core.http.endpoint
import io.github.phongmanh.restaurant.core.http.endpoints
import io.github.phongmanh.restaurant.core.http.etagOf
import io.github.phongmanh.restaurant.core.http.holdsCurrent
import io.github.phongmanh.restaurant.core.http.requestMeta
import io.github.phongmanh.restaurant.core.http.requireIfMatch
import io.github.phongmanh.restaurant.core.http.staffCaller
import io.github.phongmanh.restaurant.menu.application.MenuEditService
import io.github.phongmanh.restaurant.menu.application.MenuEditor
import io.github.phongmanh.restaurant.menu.application.MenuRead
import io.github.phongmanh.restaurant.menu.application.MenuReadService
import io.github.phongmanh.restaurant.menu.application.SoldOutEditor
import io.github.phongmanh.restaurant.menu.application.SoldOutService
import io.github.phongmanh.restaurant.menu.domain.Menu
import io.github.phongmanh.restaurant.menu.domain.MenuItem
import io.github.phongmanh.restaurant.restaurants.http.restaurantIdParam
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The menu reads (menu §4): guests, staff and admins. The guest and staff reads send
 * `Cache-Control: private, no-cache` with an ETag of the menu version, and `304` when the client's
 * copy is current (§2, CR-004). The restaurant comes from the caller's token, except on the admin
 * route, which names it.
 */
class MenuReadRoutes(
    private val menus: MenuReadService,
) {
    fun install(route: Route) {
        val e = Endpoints.Menu
        route.endpoint(e.GET_GUEST_MENU) {
            val guest = call.caller() as Caller.Guest
            val read = menus.guest(guest.restaurantId, call.request.queryParameters["lang"], call::holdsCurrent)
            respondMenu(call, read) { menu, lang -> GuestMenu.of(menu, lang!!) }
        }
        route.endpoint(e.GET_STAFF_MENU) {
            val read = menus.staff(call.staffCaller().restaurantId, call::holdsCurrent)
            respondMenu(call, read) { menu, _ -> StaffMenu.of(menu) }
        }
        route.endpoint(e.GET_MENU_ITEM) {
            val caller = call.staffCaller()
            respondItem(call, menus.item(caller.restaurantId, call.itemIdParam()))
        }
        route.endpoint(e.GET_RESTAURANT_MENU) {
            call.adminCaller()
            call.respond(StaffMenu.of(menus.admin(call.restaurantIdParam())))
        }
    }

    private suspend inline fun <reified T : Any> respondMenu(
        call: ApplicationCall,
        read: MenuRead,
        body: (Menu, String?) -> T,
    ) {
        call.response.header(HttpHeaders.ETag, read.etag)
        call.response.header(HttpHeaders.CacheControl, PRIVATE_NO_CACHE)
        when (read) {
            is MenuRead.Current -> call.respond(HttpStatusCode.NotModified)
            is MenuRead.Full -> call.respond(body(read.menu, read.lang))
        }
    }

    private companion object {
        /** The app may keep the menu, but asks again (`If-None-Match`) each time it shows it. */
        const val PRIVATE_NO_CACHE = "private, no-cache"
    }
}

/**
 * The menu writes (menu §5). Manager-only routes make the [MenuEditor] first, and the sold-out route
 * its [SoldOutEditor], before reading the body, `If-Match` or the path: 401 → 403 → 400 → 404 → 409/412
 * (core-and-auth §3).
 */
class MenuEditRoutes(
    private val edits: MenuEditService,
    private val soldOut: SoldOutService,
) {
    fun install(route: Route) {
        categories(route)
        items(route)
        route.endpoint(Endpoints.Menu.SET_MENU_ITEM_SOLD_OUT) {
            val editor = SoldOutEditor.of(call.staffCaller())
            val value = call.receive<SoldOutRequest>().toSoldOut()
            val id = call.itemIdParam()
            call.respond(SoldOutState(id.toString(), soldOut.set(editor, id, value, call.requestMeta())))
        }
    }

    private fun categories(route: Route) {
        val e = Endpoints.Menu
        route.endpoint(e.CREATE_MENU_CATEGORY) {
            val editor = MenuEditor.of(call.staffCaller())
            val category = edits.createCategory(editor, call.receive<CategoryCreateRequest>().toNewCategory(), call.requestMeta())
            call.response.header(HttpHeaders.Location, e.UPDATE_MENU_CATEGORY.pathOf("categoryId" to category.id))
            call.response.header(HttpHeaders.ETag, etagOf(category.version))
            call.respond(HttpStatusCode.Created, StaffMenuCategory.of(category))
        }
        route.endpoint(e.REORDER_MENU_CATEGORIES) {
            val editor = MenuEditor.of(call.staffCaller())
            edits.reorderCategories(editor, call.receive<CategoryOrderRequest>().toOrder(), call.requestMeta())
            call.respond(HttpStatusCode.NoContent)
        }
        route.endpoints(e.UPDATE_MENU_CATEGORY, e.UPDATE_MENU_CATEGORY_POST) {
            val editor = MenuEditor.of(call.staffCaller())
            val changes = call.receive<CategoryUpdateRequest>().toChanges()
            val category = edits.updateCategory(editor, call.categoryIdParam(), call.requireIfMatch(), changes, call.requestMeta())
            call.response.header(HttpHeaders.ETag, etagOf(category.version))
            call.respond(StaffMenuCategory.of(category))
        }
        route.endpoint(e.DELETE_MENU_CATEGORY) {
            val editor = MenuEditor.of(call.staffCaller())
            edits.deleteCategory(editor, call.categoryIdParam(), call.requireIfMatch(), call.requestMeta())
            call.respond(HttpStatusCode.NoContent)
        }
        route.endpoint(e.REORDER_MENU_ITEMS) {
            val editor = MenuEditor.of(call.staffCaller())
            val order = call.receive<ItemOrderRequest>().toOrder()
            edits.reorderItems(editor, call.categoryIdParam(), order, call.requestMeta())
            call.respond(HttpStatusCode.NoContent)
        }
    }

    private fun items(route: Route) {
        val e = Endpoints.Menu
        route.endpoint(e.CREATE_MENU_ITEM) {
            val editor = MenuEditor.of(call.staffCaller())
            val item = edits.createItem(editor, call.receive<ItemRequest>().toNewItem(), call.requestMeta())
            call.response.header(HttpHeaders.Location, e.GET_MENU_ITEM.pathOf("itemId" to item.id))
            call.response.header(HttpHeaders.ETag, etagOf(item.version))
            call.respond(HttpStatusCode.Created, StaffMenuItem.of(item))
        }
        route.endpoints(e.UPDATE_MENU_ITEM, e.UPDATE_MENU_ITEM_POST) {
            val editor = MenuEditor.of(call.staffCaller())
            val changes = call.receive<ItemRequest>().toChanges()
            respondItem(call, edits.updateItem(editor, call.itemIdParam(), call.requireIfMatch(), changes, call.requestMeta()))
        }
        route.endpoint(e.DELETE_MENU_ITEM) {
            val editor = MenuEditor.of(call.staffCaller())
            edits.deleteItem(editor, call.itemIdParam(), call.requireIfMatch(), call.requestMeta())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun respondItem(
    call: ApplicationCall,
    item: MenuItem,
) {
    call.response.header(HttpHeaders.ETag, etagOf(item.version))
    call.respond(StaffMenuItem.of(item))
}
