package io.github.phongmanh.restaurant.core.application

import io.github.phongmanh.restaurant.core.domain.IdGenerator
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.Rfc3339
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetAddress
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class ActorType(
    val value: String,
) {
    STAFF("staff"),
    ADMIN("admin"),
    GUEST("guest"),
    SYSTEM("system"),
    ANONYMOUS("anonymous"),
    ;

    companion object {
        fun of(value: String): ActorType = entries.first { it.value == value }
    }
}

/** Who did it. A guest is identified by their device, staff and admins by their account. */
data class Actor(
    val type: ActorType,
    val id: UUID?,
) {
    companion object {
        val SYSTEM = Actor(ActorType.SYSTEM, null)
        val ANONYMOUS = Actor(ActorType.ANONYMOUS, null)

        fun of(caller: Caller): Actor =
            when (caller) {
                is Caller.Guest -> Actor(ActorType.GUEST, caller.deviceId.value)
                is Caller.Staff -> Actor(ActorType.STAFF, caller.staffId.value)
                is Caller.Admin -> Actor(ActorType.ADMIN, caller.staffId.value)
            }
    }
}

/** Where a request came from, for the audit log only (never for application logs). */
data class RequestMeta(
    val requestId: String?,
    /** From Vercel's `x-real-ip`, set by the edge, not by the client (§12). */
    val ip: String?,
    val userAgent: String?,
)

/**
 * Something to record (core-and-auth §12). [restaurantId] picks the chain: a restaurant's own, or
 * `null` for the platform chain. [details] holds no secrets, passwords, tokens or free text, and
 * only strings, integers, booleans, arrays and objects.
 */
data class AuditEvent(
    val restaurantId: RestaurantId?,
    val actor: Actor,
    val action: String,
    val targetType: String? = null,
    val targetId: UUID? = null,
    val details: JsonObject = JsonObject(emptyMap()),
    val request: RequestMeta? = null,
)

/**
 * The audit log: append-only, one hash chain per restaurant plus one for the platform (§12, NFR-SEC-03).
 *
 * **In plain words:** each entry stores the hash of the entry before it, and its own hash covers
 * that. Changing or deleting any entry breaks every hash after it, so [AuditChains.verify] can
 * tell the log was altered.
 */
interface AuditLog {
    /**
     * Records [event] in [tx], the transaction of the change it describes, so there's never a
     * change without its entry or an entry without its change. The entry is written just before
     * the commit, which makes the chain-head lock the transaction's last lock (CLAUDE.md, lock order).
     */
    fun record(
        tx: TxScope,
        event: AuditEvent,
    )
}

/** One row of `audit_events`. Equality isn't used: the hashes are arrays, compared by reference. */
data class AuditRecord(
    val id: UUID,
    val restaurantId: RestaurantId?,
    val chainSeq: Long,
    val occurredAt: Instant,
    val actor: Actor,
    val action: String,
    val targetType: String?,
    val targetId: UUID?,
    val requestId: String?,
    val ip: String?,
    val userAgent: String?,
    val details: JsonObject,
    val prevHash: ByteArray,
    val hash: ByteArray,
) {
    /** The row without its hashes, as RFC 8785 canonical JSON. Absent values are left out. */
    fun canonical(): String {
        val fields =
            listOfNotNull(
                "id" to JsonPrimitive(id.toString()),
                restaurantId?.let { "restaurant_id" to JsonPrimitive(it.toString()) },
                "chain_seq" to JsonPrimitive(chainSeq),
                "occurred_at" to JsonPrimitive(Rfc3339.format(occurredAt)),
                "actor_type" to JsonPrimitive(actor.type.value),
                actor.id?.let { "actor_id" to JsonPrimitive(it.toString()) },
                "action" to JsonPrimitive(action),
                targetType?.let { "target_type" to JsonPrimitive(it) },
                targetId?.let { "target_id" to JsonPrimitive(it.toString()) },
                requestId?.let { "request_id" to JsonPrimitive(it) },
                ip?.let { "ip" to JsonPrimitive(it) },
                userAgent?.let { "user_agent" to JsonPrimitive(it) },
                "details" to details,
            )
        return CanonicalJson.encode(JsonObject(fields.toMap<String, JsonElement>()))
    }
}

/** The last entry of a chain, locked until the transaction ends. */
class ChainHead(
    val lastSeq: Long,
    val lastHash: ByteArray,
)

/** Where chains are kept: `audit_events` and `audit_chain_heads`. */
interface AuditChainStore {
    /** Locks the head of [restaurantId]'s chain (`null`: the platform's), creating it if needed. */
    fun lockHead(
        tx: TxScope,
        restaurantId: RestaurantId?,
    ): ChainHead

    /** Inserts [record] and moves the chain's head to it. */
    fun append(
        tx: TxScope,
        record: AuditRecord,
    )

    /** Up to [limit] entries of a chain after [afterSeq], in order. */
    fun read(
        tx: TxScope,
        restaurantId: RestaurantId?,
        afterSeq: Long,
        limit: Int,
    ): List<AuditRecord>
}

object AuditChains {
    private const val SHA_256_BYTES = 32

    /** The `prev_hash` of a chain's first entry: a SHA-256-sized run of zeros. */
    val GENESIS: ByteArray = ByteArray(SHA_256_BYTES)

    /** `SHA-256(prev_hash ‖ JCS(row without the hashes))` (§12). */
    fun hash(
        prevHash: ByteArray,
        record: AuditRecord,
    ): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(prevHash)
            update(record.canonical().toByteArray(Charsets.UTF_8))
            digest()
        }

    /**
     * Checks a run of consecutive entries of one chain, starting after the entry with [startSeq]
     * and [startHash] (`0` and [GENESIS] for the whole chain). Returns the `chain_seq` of the first
     * entry that doesn't fit, or `null` if all do.
     */
    fun verify(
        records: List<AuditRecord>,
        startSeq: Long = 0,
        startHash: ByteArray = GENESIS,
    ): Long? {
        var seq = startSeq
        var prev = startHash
        for (r in records) {
            val fits =
                r.chainSeq == seq + 1 &&
                    MessageDigest.isEqual(r.prevHash, prev) &&
                    MessageDigest.isEqual(r.hash, hash(r.prevHash, r))
            if (!fits) return r.chainSeq
            seq = r.chainSeq
            prev = r.hash
        }
        return null
    }

    /**
     * An IP address in one textual form, so it hashes the same when read back from the `inet`
     * column, or `null` if [raw] isn't an address. `ofLiteral` never does a DNS lookup.
     */
    fun canonicalIp(raw: String?): String? =
        raw
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_IP_LENGTH }
            ?.let { runCatching { InetAddress.ofLiteral(it).hostAddress }.getOrNull() }

    /** The user agent's first 200 characters, without control characters (PostgreSQL text can't hold NUL). */
    fun cleanUserAgent(raw: String?): String? =
        raw
            ?.filterNot { it.isISOControl() }
            ?.take(MAX_USER_AGENT_LENGTH)
            ?.takeIf { it.isNotEmpty() }

    private const val MAX_IP_LENGTH = 45
    private const val MAX_USER_AGENT_LENGTH = 200
}

/** [AuditLog] that appends to the hash chains through an [AuditChainStore]. */
class ChainedAuditLog(
    private val store: AuditChainStore,
    private val clock: Clock,
    private val ids: IdGenerator,
) : AuditLog {
    private val pendingKey = TxKey<MutableList<AuditEvent>>("audit.pending")

    override fun record(
        tx: TxScope,
        event: AuditEvent,
    ) {
        require(tx.restaurantId == null || tx.restaurantId == event.restaurantId) {
            "a tenant transaction records only to its own restaurant's chain"
        }
        require(CanonicalJson.isSupported(event.details)) { "audit details hold an unsupported value" }
        val pending = tx.getOrPut(pendingKey) { mutableListOf<AuditEvent>().also { list -> tx.beforeCommit { flush(tx, list) } } }
        pending += event
    }

    private fun flush(
        tx: TxScope,
        pending: List<AuditEvent>,
    ) {
        // Chains in one fixed order (platform first, then by restaurant ID), so two transactions
        // that both touch two chains can't lock their heads in opposite orders.
        val byChain = pending.groupBy { it.restaurantId }
        val order = byChain.keys.sortedWith(compareBy(nullsFirst()) { it?.value })
        for (chain in order) {
            var head = store.lockHead(tx, chain)
            for (event in byChain.getValue(chain)) {
                head = append(tx, head, event)
            }
        }
    }

    private fun append(
        tx: TxScope,
        head: ChainHead,
        event: AuditEvent,
    ): ChainHead {
        val unhashed =
            AuditRecord(
                id = ids.next(),
                restaurantId = event.restaurantId,
                chainSeq = head.lastSeq + 1,
                occurredAt = Rfc3339.truncate(clock.instant()),
                actor = event.actor,
                action = event.action,
                targetType = event.targetType,
                targetId = event.targetId,
                requestId = event.request?.requestId,
                ip = AuditChains.canonicalIp(event.request?.ip),
                userAgent = AuditChains.cleanUserAgent(event.request?.userAgent),
                details = event.details,
                prevHash = head.lastHash,
                hash = ByteArray(0),
            )
        val record = unhashed.copy(hash = AuditChains.hash(head.lastHash, unhashed))
        store.append(tx, record)
        return ChainHead(record.chainSeq, record.hash)
    }
}
