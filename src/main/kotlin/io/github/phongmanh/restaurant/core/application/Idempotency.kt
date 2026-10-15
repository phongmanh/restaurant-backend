package io.github.phongmanh.restaurant.core.application

import io.github.phongmanh.restaurant.core.domain.DomainError
import io.github.phongmanh.restaurant.core.domain.FieldError
import java.security.MessageDigest

/**
 * One request that carries an `Idempotency-Key` (core-and-auth §4).
 *
 * - [scope]: the caller's [Caller.idempotencyScope], or a fixed scope for unauthenticated routes.
 * - [operation]: the route template, e.g. `POST /v1/guest/orders`.
 * - [fingerprint]: SHA-256 over the operation, the path parameters and the raw body.
 */
class IdempotencyRequest private constructor(
    val scope: String,
    val operation: String,
    val key: String,
    val fingerprint: ByteArray,
) {
    companion object {
        private val KEY_FORMAT = Regex("[A-Za-z0-9_-]{1,64}")

        /** Checks the key's format and computes the fingerprint. A missing key → 400. */
        fun of(
            scope: String,
            operation: String,
            key: String?,
            pathParameters: Map<String, String>,
            body: ByteArray,
        ): IdempotencyRequest {
            if (key == null) throw DomainError.IdempotencyKeyRequired()
            if (!KEY_FORMAT.matches(key)) {
                throw DomainError.ValidationFailed(
                    listOf(FieldError("header.Idempotency-Key", "invalid_format", "1–64 characters of A–Z, a–z, 0–9, _ and -")),
                )
            }
            return IdempotencyRequest(scope, operation, key, fingerprint(operation, pathParameters, body))
        }

        private fun fingerprint(
            operation: String,
            pathParameters: Map<String, String>,
            body: ByteArray,
        ): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")

            // Length-prefixed parts, so no two different requests can produce the same byte stream.
            fun part(bytes: ByteArray) {
                digest.update(bytes.size.toString().toByteArray())
                digest.update(':'.code.toByte())
                digest.update(bytes)
            }
            part(operation.toByteArray())
            pathParameters.toSortedMap().forEach { (name, value) ->
                part(name.toByteArray())
                part(value.toByteArray())
            }
            part(body)
            return digest.digest()
        }
    }
}

/** A response as stored for replay: the status, the headers worth replaying and the body. */
class StoredResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/** What [IdempotencyStore.claim] found when the key was already there. */
class ExistingKey(
    val fingerprint: ByteArray,
    /** Set before the first request committed, so `null` only while that request still runs. */
    val response: StoredResponse?,
)

/** Where keys are kept: `idempotency_keys`, in the operation's own transaction. */
interface IdempotencyStore {
    /**
     * Inserts the key. Returns `null` when this request inserted it (it's the first), or the row
     * that was there. If another transaction holds the same key, it waits for that one to finish,
     * up to 2 s, then throws [DomainError.IdempotencyRequestInProgress].
     */
    suspend fun claim(
        tx: TxScope,
        request: IdempotencyRequest,
    ): ExistingKey?

    /** Stores the first request's response. */
    suspend fun complete(
        tx: TxScope,
        request: IdempotencyRequest,
        response: StoredResponse,
    )
}

/**
 * The first request's answer: [sent] goes to the client, [stored] is kept for replays. They differ
 * only when the answer holds a one-time secret, such as an invitation link, which is never stored
 * (core-and-auth §4).
 */
class FirstAnswer(
    val sent: StoredResponse,
    val stored: StoredResponse = sent,
)

sealed interface IdempotentOutcome {
    val response: StoredResponse

    class First(
        override val response: StoredResponse,
    ) : IdempotentOutcome

    class Replayed(
        override val response: StoredResponse,
    ) : IdempotentOutcome
}

/**
 * The idempotency rule (core-and-auth §4), run inside the operation's transaction so the key and
 * the change commit or roll back together.
 *
 * **In plain words:** the first request with a key does the work and saves its answer next to the
 * key. A retry with the same key and the same request gets the saved answer instead of doing the
 * work again; a retry with the same key but a different request is refused. A rejected request
 * throws, which rolls the key back too, so its retry is judged again on the current state.
 */
class Idempotency(
    private val store: IdempotencyStore,
) {
    suspend fun execute(
        tx: TxScope,
        request: IdempotencyRequest,
        work: suspend () -> FirstAnswer,
    ): IdempotentOutcome {
        val existing = store.claim(tx, request)
        if (existing != null) {
            if (!MessageDigest.isEqual(existing.fingerprint, request.fingerprint)) throw DomainError.IdempotencyKeyReused()
            val response = existing.response ?: throw DomainError.IdempotencyRequestInProgress()
            return IdempotentOutcome.Replayed(response)
        }
        val answer = work()
        check(answer.sent.status in SUCCESS && answer.stored.status == answer.sent.status) {
            "only 2xx responses are stored; a rejection throws a DomainError"
        }
        store.complete(tx, request, answer.stored)
        return IdempotentOutcome.First(answer.sent)
    }

    private companion object {
        val SUCCESS = 200..299
    }
}
