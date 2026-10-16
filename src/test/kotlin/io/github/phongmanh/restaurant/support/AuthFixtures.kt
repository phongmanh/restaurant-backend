package io.github.phongmanh.restaurant.support

import io.github.phongmanh.restaurant.core.auth.crypto.AccessClaims
import io.github.phongmanh.restaurant.core.auth.crypto.AccessTokens
import io.github.phongmanh.restaurant.core.auth.crypto.Argon2Hasher
import io.github.phongmanh.restaurant.core.auth.crypto.Audience
import io.github.phongmanh.restaurant.core.auth.crypto.SecureTokens
import io.github.phongmanh.restaurant.core.auth.crypto.Totp
import io.github.phongmanh.restaurant.core.domain.RestaurantId
import io.github.phongmanh.restaurant.core.domain.StaffId
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.core.http.ApiJson
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Rows tests start from, written as the database superuser: quicker than onboarding through the
 * API, which the restaurants tests cover on their own.
 */
object AuthFixtures {
    const val PASSWORD = "correct horse battery staple"
    val hasher = Argon2Hasher(TestKeys.argon2)

    fun restaurant(
        name: String = "Phở Hà Nội",
        suspended: Boolean = false,
    ): RestaurantId {
        val id = UuidV7.next()
        sql(
            "INSERT INTO restaurants (id, name, address, status, suspended_at, created_at, updated_at) " +
                "VALUES (?, ?, 'Hà Nội', ?, ?, now(), now())",
            id,
            name,
            if (suspended) "suspended" else "active",
            if (suspended) Timestamp.from(Instant.now()) else null,
        )
        // Onboarding makes the menu row through the MenuSetup port (menu §12); a fixture does it here.
        sql("INSERT INTO menu_state (restaurant_id, version, updated_at) VALUES (?, 0, now())", id)
        return RestaurantId(id)
    }

    fun suspend(restaurant: RestaurantId) =
        sql("UPDATE restaurants SET status = 'suspended', suspended_at = now() WHERE id = ?", restaurant.value)

    fun staff(
        restaurant: RestaurantId,
        email: String,
        role: String = "manager",
        status: String = "active",
        password: String? = PASSWORD,
    ): StaffId {
        val id = UuidV7.next()
        sql(
            "INSERT INTO staff_accounts (id, email, display_name, restaurant_id, role, status, password_hash, created_at, updated_at) " +
                "VALUES (?, ?, 'Lan', ?, ?, ?, ?, now(), now())",
            id,
            email,
            restaurant.value,
            role,
            status,
            password?.let(hasher::hash),
        )
        return StaffId(id)
    }

    /** A platform admin with [totpSecret] enrolled, or invited (no password, no secret) when [status] is `invited`. */
    fun admin(
        email: String,
        totpSecret: ByteArray? = Totp.newSecret(SecureRandom()),
        status: String = "active",
        password: String? = PASSWORD,
    ): StaffId {
        val id = UuidV7.next()
        val sealed = totpSecret?.let { TestKeys.auth.totpKeys.seal(it, id.toString().toByteArray(Charsets.US_ASCII)) }
        sql(
            "INSERT INTO staff_accounts (id, email, display_name, is_platform_admin, status, password_hash, totp_secret, " +
                "created_at, updated_at) VALUES (?, ?, 'Admin', true, ?, ?, ?, now(), now())",
            id,
            email,
            status,
            password?.let(hasher::hash),
            sealed,
        )
        return StaffId(id)
    }

    /** An invitation or reset link for [staffId]; returns the token the link would carry. */
    fun link(
        staffId: StaffId,
        kind: String,
        createdBy: StaffId = staffId,
        expiresAt: Instant = Instant.now().plus(1, ChronoUnit.DAYS),
    ): String {
        val token = SecureTokens().generate()
        sql(
            "INSERT INTO staff_link_tokens (token_hash, staff_id, kind, created_by, created_at, expires_at) VALUES (?, ?, ?, ?, now(), ?)",
            SecureTokens.hash(token),
            staffId.value,
            kind,
            createdBy.value,
            Timestamp.from(expiresAt),
        )
        return token
    }

    /**
     * A live session for [staffId] and its access token, without the sign-in: for tests of other
     * features that only need an authenticated caller.
     */
    fun accessToken(
        staffId: StaffId,
        audience: Audience = Audience.STAFF,
    ): String {
        val session = UuidV7.next()
        val amr = if (audience == Audience.ADMIN) listOf("pwd", "otp") else listOf("pwd")
        sql(
            "INSERT INTO staff_sessions (id, staff_id, audience, amr, started_at, expires_at) " +
                "VALUES (?, ?, ?, ?::text[], now(), now() + interval '1 day')",
            session,
            staffId.value,
            audience.value,
            amr.joinToString(",", "{", "}"),
        )
        return AccessTokens(
            TestKeys.auth.staffKeys,
            TestKeys.ISSUER,
            Clock.systemUTC(),
        ).sign(AccessClaims(staffId.value, session, audience, amr))
    }

    fun sql(
        statement: String,
        vararg params: Any?,
    ) {
        TestDatabase.superuser().use { conn ->
            conn.prepareStatement(statement).use { ps ->
                params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
                ps.executeUpdate()
            }
        }
    }

    fun <T> query(
        statement: String,
        vararg params: Any?,
        read: (java.sql.ResultSet) -> T,
    ): List<T> =
        TestDatabase.superuser().use { conn ->
            conn.prepareStatement(statement).use { ps ->
                params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
                ps.executeQuery().use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
            }
        }

    /** The audit actions recorded so far, oldest first. */
    fun auditActions(): List<String> = query("SELECT action FROM audit_events ORDER BY occurred_at, chain_seq") { it.getString(1) }
}

/** `POST` a JSON body, as every `/v1/auth` call is. */
suspend fun HttpClient.postJson(
    path: String,
    body: String,
    configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    post(path) {
        contentType(ContentType.Application.Json)
        header("x-real-ip", "203.0.113.7")
        setBody(body)
        configure()
    }

suspend fun HttpClient.signIn(
    email: String,
    password: String = AuthFixtures.PASSWORD,
): HttpResponse = postJson("/v1/auth/sign-in", """{"email": "$email", "password": "$password"}""")

/** Signs in and returns the access token. */
suspend fun HttpClient.accessToken(
    email: String,
    password: String = AuthFixtures.PASSWORD,
): String = signIn(email, password).field("access_token")

suspend fun HttpResponse.field(name: String): String =
    ApiJson
        .parseToJsonElement(bodyAsText())
        .jsonObject
        .getValue(name)
        .jsonPrimitive.content

/** The value of the response's refresh cookie [name], or `null` if it set none. */
fun HttpResponse.cookie(name: String): String? =
    headers
        .getAll(HttpHeaders.SetCookie)
        .orEmpty()
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter("=")
        ?.substringBefore(";")

fun HttpResponse.setCookieHeader(name: String): String? =
    headers.getAll(HttpHeaders.SetCookie).orEmpty().firstOrNull {
        it.startsWith("$name=")
    }
