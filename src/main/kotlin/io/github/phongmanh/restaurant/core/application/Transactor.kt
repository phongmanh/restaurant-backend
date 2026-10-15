package io.github.phongmanh.restaurant.core.application

import io.github.phongmanh.restaurant.core.domain.RestaurantId

/**
 * Database transactions (core-and-auth §7, ADR-0005).
 *
 * **In plain words:** every tenant table has a row-level security policy that only shows rows whose
 * `restaurant_id` matches a setting of the current transaction. [inTransaction] sets it first, so
 * even a query that forgot its `WHERE restaurant_id = ?` can't see another restaurant's rows. A
 * transaction that never set it sees no rows at all.
 *
 * Calls nest: a call inside an open transaction joins it instead of opening a second one, so a
 * route helper (such as the idempotency one) and the service it calls share one transaction and
 * commit or roll back together. Joining a transaction of another tenant is a bug and throws.
 *
 * Any exception rolls the transaction back and propagates.
 */
interface Transactor {
    /** A transaction that sees only [restaurantId]'s rows. */
    suspend fun <T> inTransaction(
        restaurantId: RestaurantId,
        block: suspend TxScope.() -> T,
    ): T

    /**
     * The audited bypass: a separate database role that RLS doesn't restrict. Only for the services
     * of the `/v1/admin` routes, platform-wide jobs, global tables and the sign-in steps before a
     * restaurant is known. [reason] goes to the debug log.
     */
    suspend fun <T> platformTransaction(
        reason: String,
        block: suspend TxScope.() -> T,
    ): T

    /**
     * A transaction of the `app` role with no restaurant set: it sees the global tables, and no row
     * of a tenant table (nor may it write one). For hot paths that read or write only global tables,
     * such as the caller lookup on every staff call (§6) and a refresh (§8.3), so they don't queue
     * for the small platform pool.
     */
    suspend fun <T> globalTransaction(block: suspend TxScope.() -> T): T
}

/** What the code inside a transaction can ask of it. */
interface TxScope {
    /** The restaurant this transaction is bound to; `null` in a platform transaction. */
    val restaurantId: RestaurantId?

    /**
     * Runs [action] after the block has finished and just before the commit, in the order
     * registered, and only if the block succeeded. The audit log uses it so the chain-head lock is
     * always the last lock a transaction takes (CLAUDE.md, lock order).
     */
    fun beforeCommit(action: suspend () -> Unit)

    /** A value kept for the length of this transaction, made by [init] on first use. */
    fun <T : Any> getOrPut(
        key: TxKey<T>,
        init: () -> T,
    ): T
}

/** Names a value kept in a transaction with [TxScope.getOrPut]. Compared by identity. */
class TxKey<T : Any>(
    val name: String,
)

/** Which kind of transaction an operation needs: one restaurant's, or the platform's (§7). */
sealed interface Tenant {
    data class Restaurant(
        val id: RestaurantId,
    ) : Tenant

    data class Platform(
        val reason: String,
    ) : Tenant
}

suspend fun <T> Transactor.inTransaction(
    tenant: Tenant,
    block: suspend TxScope.() -> T,
): T =
    when (tenant) {
        is Tenant.Restaurant -> inTransaction(tenant.id, block)
        is Tenant.Platform -> platformTransaction(tenant.reason, block)
    }
