package io.github.phongmanh.restaurant.core.persistence

import io.github.phongmanh.restaurant.core.application.Actor
import io.github.phongmanh.restaurant.core.application.ActorType
import io.github.phongmanh.restaurant.core.application.AuditChainStore
import io.github.phongmanh.restaurant.core.application.AuditChains
import io.github.phongmanh.restaurant.core.application.AuditRecord
import io.github.phongmanh.restaurant.core.application.ChainHead
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.sql.ResultSet
import java.util.UUID

/** [AuditChainStore] on `audit_events` and `audit_chain_heads` (core-and-auth §12). */
class AuditRepository : AuditChainStore {
    override fun lockHead(
        tx: TxScope,
        restaurantId: RestaurantId?,
    ): ChainHead {
        val conn = ExposedTransactor.connection(tx)
        val chainKey = chainKey(restaurantId)
        conn
            .prepareStatement("INSERT INTO audit_chain_heads (chain_key, last_seq, last_hash) VALUES (?, 0, ?) ON CONFLICT DO NOTHING")
            .use { it.bind(chainKey, AuditChains.GENESIS).executeUpdate() }
        return conn.prepareStatement("SELECT last_seq, last_hash FROM audit_chain_heads WHERE chain_key = ? FOR UPDATE").use {
            it.bind(chainKey).firstOrNull { rs -> ChainHead(rs.getLong("last_seq"), rs.getBytes("last_hash")) }
        } ?: error("audit chain head not visible")
    }

    override fun append(
        tx: TxScope,
        record: AuditRecord,
    ) {
        val conn = ExposedTransactor.connection(tx)
        conn
            .prepareStatement(
                """
                INSERT INTO audit_events (id, restaurant_id, chain_seq, occurred_at, actor_type, actor_id, action,
                    target_type, target_id, request_id, ip, user_agent, details, prev_hash, hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::inet, ?, ?::jsonb, ?, ?)
                """.trimIndent(),
            ).use {
                it
                    .bind(
                        record.id,
                        record.restaurantId?.value,
                        record.chainSeq,
                        record.occurredAt,
                        record.actor.type.value,
                        record.actor.id,
                        record.action,
                        record.targetType,
                        record.targetId,
                        record.requestId,
                        record.ip,
                        record.userAgent,
                        record.details.toString(),
                        record.prevHash,
                        record.hash,
                    ).executeUpdate()
            }
        conn.prepareStatement("UPDATE audit_chain_heads SET last_seq = ?, last_hash = ? WHERE chain_key = ?").use {
            check(
                it.bind(record.chainSeq, record.hash, chainKey(record.restaurantId)).executeUpdate() == 1,
            ) { "audit chain head not visible" }
        }
    }

    override fun read(
        tx: TxScope,
        restaurantId: RestaurantId?,
        afterSeq: Long,
        limit: Int,
    ): List<AuditRecord> {
        // Two forms rather than IS NOT DISTINCT FROM, which can't use the (restaurant_id, chain_seq) index.
        val chain = if (restaurantId == null) "restaurant_id IS NULL" else "restaurant_id = ?"
        return ExposedTransactor
            .connection(tx)
            .prepareStatement(
                """
                SELECT id, restaurant_id, chain_seq, occurred_at, actor_type, actor_id, action, target_type,
                    target_id, request_id, host(ip) AS ip, user_agent, details::text AS details, prev_hash, hash
                FROM audit_events
                WHERE $chain AND chain_seq > ?
                ORDER BY chain_seq
                LIMIT ?
                """.trimIndent(),
            ).use {
                val bound = if (restaurantId == null) it.bind(afterSeq, limit) else it.bind(restaurantId.value, afterSeq, limit)
                bound.list { rs -> rs.toRecord() }
            }
    }

    private fun ResultSet.toRecord() =
        AuditRecord(
            id = getObject("id", UUID::class.java),
            restaurantId = getObject("restaurant_id", UUID::class.java)?.let(::RestaurantId),
            chainSeq = getLong("chain_seq"),
            occurredAt = getTimestamp("occurred_at").toInstant(),
            actor = Actor(ActorType.of(getString("actor_type")), getObject("actor_id", UUID::class.java)),
            action = getString("action"),
            targetType = getString("target_type"),
            targetId = getObject("target_id", UUID::class.java),
            requestId = getString("request_id"),
            ip = AuditChains.canonicalIp(getString("ip")),
            userAgent = getString("user_agent"),
            details = Json.parseToJsonElement(getString("details")).jsonObject,
            prevHash = getBytes("prev_hash"),
            hash = getBytes("hash"),
        )

    private companion object {
        /** The platform chain's key: the nil UUID (§12). */
        val PLATFORM_CHAIN: UUID = UUID(0, 0)

        fun chainKey(restaurantId: RestaurantId?): UUID = restaurantId?.value ?: PLATFORM_CHAIN
    }
}
