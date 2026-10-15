package io.github.phongmanh.restaurant.restaurants.application

import io.github.phongmanh.restaurant.core.application.Actor
import io.github.phongmanh.restaurant.core.application.AuditLog
import io.github.phongmanh.restaurant.core.application.Caller
import io.github.phongmanh.restaurant.core.application.RequestMeta
import io.github.phongmanh.restaurant.core.application.Transactor
import io.github.phongmanh.restaurant.core.auth.application.StaffAccounts
import io.github.phongmanh.restaurant.core.auth.application.StaffPrivacy
import io.github.phongmanh.restaurant.core.auth.domain.StaffMember
import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.Rfc3339
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.restaurants.persistence.AuditEntry
import io.github.phongmanh.restaurant.restaurants.persistence.AuditQueryRepository
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.Instant

/** A staff member's data, for a privacy request (restaurants §10). */
class StaffDataExport(
    val account: StaffMember,
    val auditEntries: List<AuditEntry>,
)

class Erased(
    val staffId: StaffId,
    val anonymisedAt: Instant,
    /** The restaurant has no active manager now; the admin may want to invite one. */
    val lastManagerRemoved: Boolean,
)

/**
 * Privacy requests from a restaurant's staff member (NFR-PRIV-01, restaurants §10), handled by
 * platform admins because the request goes to the operator. Both read across chains, so both run
 * in the platform transaction; each is audited in the member's restaurant chain.
 */
class PrivacyService(
    private val transactor: Transactor,
    private val privacy: StaffPrivacy,
    private val staff: StaffAccounts,
    private val entries: AuditQueryRepository,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /**
     * The account's personal fields (never a hash, secret or token) and the audit entries where
     * it's the actor or the target. Entries about the member made by someone else keep that
     * person's IP and user agent out: they're the other person's data.
     */
    suspend fun export(
        caller: Caller.Admin,
        id: StaffId,
        request: RequestMeta,
    ): StaffDataExport =
        transactor.platformTransaction("privacy: export") {
            val member = privacy.find(this, id) ?: throw DomainError.NotFound()
            val about =
                entries.about(this, id).map { entry ->
                    if (entry.actorId == id.value) entry else entry.copy(ip = null, userAgent = null)
                }
            audit.recordAccount(this, member, Actor.of(caller), "staff.data_exported", request)
            StaffDataExport(member, about)
        }

    /**
     * Deactivates and anonymises the account now. Erasing the last active manager is allowed; the
     * answer says so, so the admin can invite a new one (§10).
     */
    suspend fun erase(
        caller: Caller.Admin,
        id: StaffId,
        request: RequestMeta,
    ): Erased =
        transactor.platformTransaction("privacy: erase") {
            val member = privacy.find(this, id) ?: throw DomainError.NotFound()
            val restaurantId = requireNotNull(member.restaurantId)
            staff.lockStaffOf(this, restaurantId)
            val now = Rfc3339.truncate(clock.instant())
            privacy.erase(this, member, now)
            val lastManagerRemoved = member.isActiveManager && staff.counts(this, restaurantId).activeManagers == 0
            val details = if (lastManagerRemoved) mapOf("last_manager_removed" to JsonPrimitive(true)) else emptyMap()
            audit.recordAccount(this, member, Actor.of(caller), "staff.erased", request, details)
            Erased(id, now, lastManagerRemoved)
        }
}
