package io.github.phongmanh.restaurant.core.persistence

import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxKey
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * [Transactor] on Exposed (core-and-auth §7). A tenant transaction runs on the `app` pool and starts
 * with one statement that sets the tenant and the statement timeout; a platform transaction runs on
 * the `app_platform` pool, whose role bypasses row-level security.
 */
class ExposedTransactor(
    private val pools: DatabasePools,
) : Transactor {
    private val statementTimeout = "${pools.settings.statementTimeout.inWholeMilliseconds}ms"

    override suspend fun <T> inTransaction(
        restaurantId: RestaurantId,
        block: suspend TxScope.() -> T,
    ): T = run(restaurantId, platform = false, block)

    override suspend fun <T> platformTransaction(
        reason: String,
        block: suspend TxScope.() -> T,
    ): T {
        log.debug("platform transaction: {}", reason)
        return run(restaurantId = null, platform = true, block)
    }

    override suspend fun <T> globalTransaction(block: suspend TxScope.() -> T): T = run(restaurantId = null, platform = false, block)

    private suspend fun <T> run(
        restaurantId: RestaurantId?,
        platform: Boolean,
        block: suspend TxScope.() -> T,
    ): T {
        val open = currentCoroutineContext()[CurrentTx]?.scope
        if (open != null) {
            check(open.restaurantId == restaurantId && open.platform == platform) {
                "a transaction can't join one bound to another tenant"
            }
            return open.block()
        }
        val database = if (platform) pools.platform else pools.app
        return try {
            withContext(Dispatchers.IO) {
                suspendTransaction(database) {
                    val scope = Scope(restaurantId, platform, this)
                    withContext(CurrentTx(scope)) {
                        scope.start()
                        scope.block().also { scope.runBeforeCommit() }
                    }
                }
            }
        } catch (e: SQLException) {
            throw translate(e)
        }
    }

    private inner class Scope(
        override val restaurantId: RestaurantId?,
        val platform: Boolean,
        val transaction: JdbcTransaction,
    ) : TxScope {
        private val beforeCommit = mutableListOf<suspend () -> Unit>()
        private val attributes = mutableMapOf<TxKey<*>, Any>()

        val connection: Connection get() = transaction.connection.connection as Connection

        override fun beforeCommit(action: suspend () -> Unit) {
            beforeCommit += action
        }

        override fun <T : Any> getOrPut(
            key: TxKey<T>,
            init: () -> T,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return attributes.getOrPut(key, init) as T
        }

        fun start() {
            // set_config(…, true) is SET LOCAL in a form that takes bind parameters; both settings
            // end with the transaction, so a pooled connection carries neither into the next one.
            // The policy reads an unset tenant as NULL, so a platform transaction sets none.
            connection
                .prepareStatement(
                    "SELECT set_config('app.restaurant_id', ?, true), set_config('statement_timeout', ?, true)",
                ).use {
                    it.setString(1, restaurantId?.toString() ?: "")
                    it.setString(2, statementTimeout)
                    it.executeQuery().close()
                }
        }

        suspend fun runBeforeCommit() {
            // An action may register another one (an audit entry recorded by a hook), so loop by index.
            var i = 0
            while (i < beforeCommit.size) beforeCommit[i++]()
        }
    }

    private class CurrentTx(
        val scope: Scope,
    ) : AbstractCoroutineContextElement(CurrentTx) {
        companion object Key : CoroutineContext.Key<CurrentTx>
    }

    internal companion object {
        private val log = LoggerFactory.getLogger(ExposedTransactor::class.java)

        /** The JDBC connection of the transaction [tx] belongs to, for the SQL Exposed's DSL can't say. */
        fun connection(tx: TxScope): Connection = (tx as ExposedTransactor.Scope).connection

        /**
         * Database failures that mean "try again later" become `503 service_unavailable`
         * (architecture §10). Exposed's wrapper is dropped either way: its `toString()` appends the
         * failed SQL with its parameter values, which would reach the log with the stack trace.
         */
        fun translate(e: SQLException): Exception {
            val cause = if (e is ExposedSQLException) (e.cause as? SQLException ?: e) else e
            val state = cause.sqlState.orEmpty()
            val unavailable =
                cause is SQLTransientConnectionException ||
                    state.startsWith("08") ||
                    state == QUERY_CANCELED ||
                    state == ADMIN_SHUTDOWN ||
                    state == CANNOT_CONNECT_NOW
            return if (unavailable) DomainError.ServiceUnavailable() else cause
        }

        /** `statement_timeout` fired. */
        private const val QUERY_CANCELED = "57014"
        private const val ADMIN_SHUTDOWN = "57P01"
        private const val CANNOT_CONNECT_NOW = "57P03"
    }
}
