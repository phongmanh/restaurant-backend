package io.github.phongmanh.restaurant.menu.application

import io.github.phongmanh.restaurant.core.application.Actor
import io.github.phongmanh.restaurant.core.application.AuditEvent
import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.StaffRole
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.application.requireRole
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.menu.persistence.MenuStateRepository
import io.github.phongmanh.restaurant.realtime.application.EventAudience
import io.github.phongmanh.restaurant.realtime.application.EventLog
import io.github.phongmanh.restaurant.realtime.application.EventSubject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * A manager editing their own restaurant's menu (menu §5). The only way to get one is the role
 * check, so the route makes it before it reads the request: 401 → 403 → 400 → 404 (core-and-auth §3).
 */
class MenuEditor private constructor(
    val restaurantId: RestaurantId,
    val actor: Actor,
) {
    companion object {
        /** A manager; any other role → `403 forbidden`. Admins read menus but don't edit them. */
        fun of(caller: Caller.Staff): MenuEditor {
            caller.requireRole(StaffRole.MANAGER)
            return MenuEditor(caller.restaurantId, Actor.of(caller))
        }
    }
}

/** Kitchen staff or a manager marking items sold out (menu §5.3, FR-021); a waiter → `403`. */
class SoldOutEditor private constructor(
    val restaurantId: RestaurantId,
    val actor: Actor,
) {
    companion object {
        fun of(caller: Caller.Staff): SoldOutEditor {
            caller.requireRole(StaffRole.KITCHEN, StaffRole.MANAGER)
            return SoldOutEditor(caller.restaurantId, Actor.of(caller))
        }
    }
}

/** One menu write in progress: its transaction, its instant, and the audit entries of what it changed. */
class MenuWrite internal constructor(
    val tx: TxScope,
    val restaurantId: RestaurantId,
    val now: Instant,
    private val actor: Actor,
    private val request: RequestMeta,
) {
    internal val entries = mutableListOf<AuditEvent>()

    /** Records a change. A write that records none changed nothing: no new menu version, no event. */
    fun changed(
        action: String,
        targetType: String?,
        targetId: UUID?,
        details: Map<String, JsonElement> = emptyMap(),
    ) {
        entries += AuditEvent(restaurantId, actor, action, targetType, targetId, JsonObject(details), request)
    }
}

/**
 * Every menu write, in one transaction of the restaurant (menu §5.2): the menu lock first, then the
 * change, then the new menu version, its `MenuChanged` event and the audit entries. That is the
 * lock order of CLAUDE.md: menu rows → event counter → audit chain head.
 */
class MenuWriter(
    private val transactor: Transactor,
    private val states: MenuStateRepository,
    private val events: EventLog,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    suspend fun <T> write(
        restaurantId: RestaurantId,
        actor: Actor,
        request: RequestMeta,
        block: suspend MenuWrite.() -> T,
    ): T =
        transactor.inTransaction(restaurantId) {
            states.lock(this, restaurantId)
            val write = MenuWrite(this, restaurantId, clock.instant(), actor, request)
            val result = write.block()
            if (write.entries.isNotEmpty()) {
                val version = states.bump(this, restaurantId, write.now)
                val subject = EventSubject(MENU_AGGREGATE, restaurantId.value, Math.toIntExact(version))
                events.append(this, restaurantId, MENU_CHANGED, subject, EventAudience.STAFF)
                write.entries.forEach { audit.record(this, it) }
            }
            result
        }

    companion object {
        /** Staff only: guests see menu changes on their next load (realtime §5). */
        const val MENU_CHANGED = "MenuChanged"
        const val MENU_AGGREGATE = "menu"
    }
}
