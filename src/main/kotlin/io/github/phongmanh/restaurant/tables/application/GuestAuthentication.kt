package io.github.phongmanh.restaurant.tables.application

import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.auth.application.GuestAuthenticator
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.tables.crypto.GuestTokens
import io.github.phongmanh.restaurant.tables.domain.SessionStatus
import io.github.phongmanh.restaurant.tables.persistence.SessionRepository

/**
 * The guest authentication of every `/v1/guest` route but the scan (tables §3.2, ADR-0006):
 *
 * 1. verify the token's signature, `aud`, `iss` and `exp` → otherwise `401 unauthenticated`;
 * 2. one short query in the token's restaurant reads the session and the restaurant's status;
 * 3. no such session, or a closed one → `401 table_session_closed`, so the app can say "this visit
 *    has ended, scan the code again" (§13 Q6). Each call reads the status, so closing a session
 *    cuts off every token at once;
 * 4. a suspended restaurant, on an endpoint not allowed then → `403 restaurant_suspended`;
 * 5. the guest, with the table the session is at now (a session can move).
 *
 * A token naming another restaurant's session finds no row, as the query runs under the token's
 * restaurant: `401`, never that session's data (NFR-SEC-02).
 */
class GuestAuthentication(
    private val transactor: Transactor,
    private val tokens: GuestTokens,
    private val sessions: SessionRepository,
) : GuestAuthenticator {
    override suspend fun authenticate(
        bearer: String?,
        allowedWhenSuspended: Boolean,
    ): Caller.Guest {
        val claims = bearer?.let(tokens::verify) ?: throw DomainError.Unauthenticated()
        val rid = claims.restaurantId
        val row = transactor.inTransaction(rid) { sessions.guestRow(this, rid, claims.sessionId) }
        if (row == null || row.status != SessionStatus.OPEN) throw DomainError.TableSessionClosed()
        if (row.restaurantSuspended && !allowedWhenSuspended) throw DomainError.RestaurantSuspended()
        return Caller.Guest(claims.deviceId, claims.sessionId, row.tableId, rid)
    }
}
