package io.github.phongmanh.restaurant.core.auth.application

import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.auth.crypto.AccessClaims
import io.github.phongmanh.restaurant.core.auth.crypto.AccessTokens
import io.github.phongmanh.restaurant.core.auth.crypto.Audience
import io.github.phongmanh.restaurant.core.auth.persistence.CallerRepository
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.domain.StaffSessionId
import java.time.Clock

/** Which callers a route accepts: its group's (§2), or either kind for `/v1/auth/me` and `/v1/auth/password`. */
enum class Accepts {
    STAFF,
    ADMIN,
    STAFF_OR_ADMIN,
}

/**
 * Turns an access token into a [Caller] (core-and-auth §6): the token is verified, then one query
 * reads the account, the session and the restaurant's status. Because that query joins the
 * session, a sign-out, password change, role change or deactivation takes effect on the very next
 * call. Every failure is `401 unauthenticated`, except a valid staff token on an admin route, which
 * is `404 not_found` so the admin routes look absent to restaurant staff (FR-040).
 */
class Authentication(
    private val transactor: Transactor,
    private val callers: CallerRepository,
    private val accessTokens: AccessTokens,
    private val clock: Clock,
) {
    suspend fun authenticate(
        bearer: String?,
        accepts: Accepts,
        allowedWhenSuspended: Boolean,
    ): Caller {
        val claims = verified(bearer, accepts)
        val admin = claims.audience == Audience.ADMIN
        val staffId = StaffId(claims.staffId)
        val sessionId = StaffSessionId(claims.sessionId)
        val row =
            transactor
                .globalTransaction { callers.lookup(this, staffId, sessionId, clock.instant()) }
                ?.takeIf { it.isPlatformAdmin == admin }
                ?: throw DomainError.Unauthenticated()
        if (row.restaurantSuspended && !allowedWhenSuspended) throw DomainError.RestaurantSuspended()
        return if (admin) {
            Caller.Admin(staffId, sessionId)
        } else {
            Caller.Staff(staffId, requireNotNull(row.restaurantId), requireNotNull(row.role), sessionId)
        }
    }

    /** The token's claims if it's valid for the route; an admin token must carry `otp` (§6 step 1). */
    private fun verified(
        bearer: String?,
        accepts: Accepts,
    ): AccessClaims {
        val audiences =
            when (accepts) {
                Accepts.STAFF -> setOf(Audience.STAFF)

                // A staff token is verified here too, so it can be told apart from a bad one (404, not 401).
                Accepts.ADMIN, Accepts.STAFF_OR_ADMIN -> setOf(Audience.STAFF, Audience.ADMIN)
            }
        val claims =
            bearer
                ?.let { accessTokens.verify(it, audiences) }
                ?.takeIf { it.audience != Audience.ADMIN || SignInService.OTP in it.amr }
                ?: throw DomainError.Unauthenticated()
        if (accepts == Accepts.ADMIN && claims.audience == Audience.STAFF) throw DomainError.NotFound()
        return claims
    }
}
