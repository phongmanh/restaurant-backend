package io.github.phongmanh.restaurant.core.auth.application

import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.Tenant
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.application.inTransaction
import io.github.phongmanh.restaurant.core.auth.crypto.PasswordHashing
import io.github.phongmanh.restaurant.core.auth.domain.AccountStatus
import io.github.phongmanh.restaurant.core.auth.domain.PasswordPolicy
import io.github.phongmanh.restaurant.core.auth.domain.StaffAccount
import io.github.phongmanh.restaurant.core.auth.persistence.CallerRepository
import io.github.phongmanh.restaurant.core.auth.persistence.MeRow
import io.github.phongmanh.restaurant.core.auth.persistence.RevokeReason
import io.github.phongmanh.restaurant.core.auth.persistence.StaffAccountRepository
import io.github.phongmanh.restaurant.core.auth.persistence.StaffSessionRepository
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.domain.StaffSessionId
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock

/**
 * What a signed-in staff member or admin does with their own account: `GET /v1/auth/me` (§8.8)
 * and `POST /v1/auth/password` (§8.7). Each reads the account through the caller's restaurant, as
 * every query a restaurant's staff can trigger does (IT-CORE-07).
 */
class PasswordService(
    private val transactor: Transactor,
    private val accounts: StaffAccountRepository,
    private val sessions: StaffSessionRepository,
    private val callers: CallerRepository,
    private val policy: PasswordPolicy,
    private val hashing: PasswordHashing,
    private val failures: CredentialFailures,
    private val audit: AuthAudit,
    private val clock: Clock,
) {
    suspend fun me(caller: Caller): MeRow {
        val (staffId, sessionId, restaurantId) = own(caller)
        return transactor.globalTransaction { callers.me(this, staffId, sessionId, restaurantId) } ?: throw DomainError.Unauthenticated()
    }

    /**
     * Checks the current password (same slot and lock counter as sign-in), then stores the new one
     * and ends every session of the person, this one included; the app signs in again.
     */
    suspend fun change(
        caller: Caller,
        currentPassword: String,
        newPassword: String,
        request: RequestMeta,
    ) {
        val (staffId, _, restaurantId) = own(caller)
        val tenant = restaurantId?.let { Tenant.Restaurant(it) } ?: Tenant.Platform("auth: admin password change")
        val account = transactor.globalTransaction { find(this, staffId, restaurantId) } ?: throw DomainError.Unauthenticated()
        val usable = account.takeIf { it.status == AccountStatus.ACTIVE && !it.isLocked(clock.instant()) }
        val matched = hashing.verifyOrDummy(PasswordPolicy.normalise(currentPassword), usable?.passwordHash)
        if (!matched) {
            transactor.inTransaction(tenant) {
                failures.record(
                    this,
                    account,
                    clock.instant(),
                    "auth.password.change_failed",
                    request,
                    mapOf("reason" to JsonPrimitive("bad_password")),
                )
            }
            throw DomainError.InvalidCredentials()
        }
        val hash = hashing.hash(policy.check(newPassword, account.email, "/new_password"))
        val now = clock.instant()
        transactor.inTransaction(tenant) {
            accounts.setPassword(this, account.id, hash, now)
            sessions.revokeAll(this, account.id, RevokeReason.PASSWORD_CHANGED, now)
            audit.record(this, account, "auth.password.changed", request)
        }
    }

    private fun find(
        tx: TxScope,
        staffId: StaffId,
        restaurantId: RestaurantId?,
    ): StaffAccount? =
        if (restaurantId !=
            null
        ) {
            accounts.findInRestaurant(tx, restaurantId, staffId)
        } else {
            accounts.findPlatformAdmin(tx, staffId)
        }

    private fun own(caller: Caller): Triple<StaffId, StaffSessionId, RestaurantId?> =
        when (caller) {
            is Caller.Staff -> Triple(caller.staffId, caller.sessionId, caller.restaurantId)
            is Caller.Admin -> Triple(caller.staffId, caller.sessionId, null)
            is Caller.Guest -> throw DomainError.Unauthenticated()
        }
}
