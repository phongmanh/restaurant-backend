package io.github.phongmanh.restaurant.core.http

import io.github.phongmanh.restaurant.Contract
import io.github.phongmanh.restaurant.core.domain.UuidV7
import io.github.phongmanh.restaurant.support.Guests
import io.github.phongmanh.restaurant.support.RouteLog
import io.github.phongmanh.restaurant.support.Tenants
import io.github.phongmanh.restaurant.support.TestDatabase
import io.github.phongmanh.restaurant.support.api
import io.github.phongmanh.restaurant.support.notYetRouted
import io.github.phongmanh.restaurant.support.problem
import io.github.phongmanh.restaurant.support.testApp
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The token rows of the authorisation matrix (test-plan §2; core-and-auth §6), over every operation
 * the contract secures with a bearer token: no token, and each kind of token the operation doesn't
 * accept, is `401 unauthenticated` with `WWW-Authenticate: Bearer`, except a staff token on an
 * admin-only operation, which is `404 not_found` so the admin routes look absent (FR-040). A token
 * the operation does accept is never 401, which also proves the matrix's tokens are valid.
 *
 * The operations and the tokens each accepts come from the contract's `security`, so a new endpoint
 * is in the matrix without a change here. An operation that also allows no credentials (`{}`, i.e.
 * sign-out, which is always 204) is left out. Path IDs are random: authentication runs before the
 * handler reads them.
 */
class AuthenticationMatrixTest {
    private val kinds = mapOf("staffBearer" to "staff", "adminBearer" to "admin", "guestBearer" to "guest")

    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    @Test
    fun `every bearer operation refuses no token and the wrong kind of token`() =
        testApp {
            val tenant = Tenants.create("Phở Lan", "manager@lan.example")
            val tokens =
                mapOf(
                    "staff" to tenant.managerToken,
                    "admin" to Tenants.admin("ops@platform.example").second,
                    "guest" to Guests.seated(tenant.id).token,
                )
            val routed = (Endpoints.all - notYetRouted).map { "${it.method.value} ${it.path}" }.toSet()
            val operations =
                Contract.operations
                    .filter { "${it.method} ${it.path}" in routed }
                    .filter { op -> op.security.none { it.isEmpty() } }
                    .map { op ->
                        op to
                            op.security
                                .flatten()
                                .mapNotNull(kinds::get)
                                .toSet()
                    }.filter { (_, accepted) -> accepted.isNotEmpty() }
            assertTrue(operations.size > 80, "the contract's bearer operations weren't found: ${operations.size}")

            val failures = mutableListOf<String>()
            for ((op, accepted) in operations) {
                val path = op.path.replace(Regex("\\{[^}]+}"), UuidV7.next().toString())
                val method = HttpMethod.parse(op.method)
                val cases = listOf("no token" to null) + tokens.keys.map { "$it token" to it }
                for ((case, kind) in cases) {
                    val hidden = kind == "staff" && accepted == setOf("admin")
                    // Only the 401s count in the route-test matrix: the other statuses here aren't checked
                    // for what they are, and the hidden admin route's 404 isn't an unknown ID's.
                    val response =
                        client.api(method, path, kind?.let(tokens::getValue)) {
                            if (kind in accepted || hidden) header(RouteLog.UNRECORDED, "1")
                        }
                    problemWith(response, kind in accepted, hidden)?.let { failures += "${op.method} ${op.path} ($case): $it" }
                }
            }
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        }

    /** What's wrong with [response], or null. [accepted]: the operation takes the token; [hidden]: a staff token on an admin route. */
    private suspend fun problemWith(
        response: HttpResponse,
        accepted: Boolean,
        hidden: Boolean,
    ): String? {
        val status = response.status
        val problem = response.takeIf { status.value >= 400 }?.problem()
        return when {
            accepted -> {
                "accepted kind got 401".takeIf { status == HttpStatusCode.Unauthorized }
            }

            hidden -> {
                "$status, expected 404 not_found".takeUnless { status == HttpStatusCode.NotFound && problem == "not_found" }
            }

            else -> {
                val bearer = response.headers[HttpHeaders.WWWAuthenticate] == "Bearer"
                "$status, expected 401 unauthenticated with WWW-Authenticate: Bearer".takeUnless {
                    status == HttpStatusCode.Unauthorized && problem == "unauthenticated" && bearer
                }
            }
        }
    }
}
