package io.github.phongmanh.restaurant.core.persistence

import io.github.phongmanh.restaurant.core.application.ExistingKey
import io.github.phongmanh.restaurant.core.application.IdempotencyRequest
import io.github.phongmanh.restaurant.core.application.IdempotencyStore
import io.github.phongmanh.restaurant.core.application.StoredResponse
import io.github.phongmanh.restaurant.core.application.TxScope
import io.github.phongmanh.restaurant.core.domain.DomainError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.SQLException
import java.time.Clock

/** [IdempotencyStore] on the `idempotency_keys` table (core-and-auth §4, §9). */
class IdempotencyRepository(
    private val clock: Clock,
) : IdempotencyStore {
    override suspend fun claim(
        tx: TxScope,
        request: IdempotencyRequest,
    ): ExistingKey? {
        val conn = ExposedTransactor.connection(tx)
        // A second copy of the same key waits here until the first commits or rolls back. The wait
        // is capped for this one statement; the rest of the transaction keeps no lock timeout.
        conn.prepareStatement("SELECT set_config('lock_timeout', ?, true)").use { it.bind(LOCK_TIMEOUT).executeQuery().close() }
        val inserted =
            try {
                conn
                    .prepareStatement(
                        """
                        INSERT INTO idempotency_keys (scope, operation, key, restaurant_id, request_hash, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT DO NOTHING
                        """.trimIndent(),
                    ).use {
                        it
                            .bind(
                                request.scope,
                                request.operation,
                                request.key,
                                tx.restaurantId?.value,
                                request.fingerprint,
                                clock.instant(),
                            ).executeUpdate() == 1
                    }
            } catch (e: SQLException) {
                if (e.sqlState == LOCK_NOT_AVAILABLE) throw DomainError.IdempotencyRequestInProgress()
                throw e
            }
        conn.prepareStatement("SELECT set_config('lock_timeout', '0', true)").use { it.executeQuery().close() }
        if (inserted) return null

        // The row conflicted, so it exists. RLS hides it only when it belongs to another
        // restaurant: the same device used the same key there. Not this request, then.
        return conn
            .prepareStatement(
                """
                SELECT request_hash, response_status, response_headers, response_body
                FROM idempotency_keys WHERE scope = ? AND operation = ? AND key = ?
                """.trimIndent(),
            ).use {
                it.bind(request.scope, request.operation, request.key).firstOrNull { rs ->
                    val status = rs.getInt("response_status").takeUnless { rs.wasNull() }
                    ExistingKey(
                        fingerprint = rs.getBytes("request_hash"),
                        response =
                            status?.let { s ->
                                StoredResponse(
                                    s,
                                    decodeHeaders(rs.getString("response_headers")),
                                    rs.getBytes("response_body") ?: ByteArray(0),
                                )
                            },
                    )
                }
            } ?: throw DomainError.IdempotencyKeyReused()
    }

    override suspend fun complete(
        tx: TxScope,
        request: IdempotencyRequest,
        response: StoredResponse,
    ) {
        ExposedTransactor
            .connection(tx)
            .prepareStatement(
                """
                UPDATE idempotency_keys SET response_status = ?, response_headers = ?::jsonb, response_body = ?
                WHERE scope = ? AND operation = ? AND key = ?
                """.trimIndent(),
            ).use {
                val updated =
                    it
                        .bind(
                            response.status,
                            encodeHeaders(response.headers),
                            response.body,
                            request.scope,
                            request.operation,
                            request.key,
                        ).executeUpdate()
                check(updated == 1) { "idempotency key row vanished" }
            }
    }

    private fun encodeHeaders(headers: Map<String, String>): String = JsonObject(headers.mapValues { JsonPrimitive(it.value) }).toString()

    private fun decodeHeaders(json: String?): Map<String, String> =
        json?.let { Json.parseToJsonElement(it).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content } }.orEmpty()

    private companion object {
        const val LOCK_TIMEOUT = "2s"
        const val LOCK_NOT_AVAILABLE = "55P03"
    }
}
