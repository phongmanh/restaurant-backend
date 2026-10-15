package io.github.phongmanh.restaurant.restaurants.ops

import io.github.phongmanh.restaurant.appModule
import io.github.phongmanh.restaurant.core.config.AppConfig
import io.github.phongmanh.restaurant.core.domain.Rfc3339
import io.github.phongmanh.restaurant.core.persistence.DatabasePools
import io.github.phongmanh.restaurant.restaurants.application.AdminAccountService
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * The operator's commands (restaurants §5, §8; architecture §7), for when no platform admin can act
 * through the API:
 *
 * - `invite-admin <email> <display name>`: the first platform admin, while no admin is active.
 * - `reset-admin-totp <email>`: the only active admin's lost authenticator, or a fresh link for an
 *   invited admin whose link expired.
 *
 * `./gradlew operator --args="invite-admin lan@example.com Lan"`, with the service's environment
 * variables set (the database URLs and the auth secrets). The link is printed once, here only: it's
 * never logged or stored. Both commands are audited with the system as the actor.
 */
fun main(args: Array<String>) {
    val code = runOperator(args.toList(), { AppConfig.load(ApplicationConfig("application.yaml")) }, System.out)
    exitProcess(code)
}

/** Runs one command; returns the exit code. */
fun runOperator(
    args: List<String>,
    config: () -> AppConfig,
    out: PrintStream,
): Int {
    val command = args.firstOrNull()
    val email = args.getOrNull(1)
    val displayName = args.drop(2).joinToString(" ").takeIf { it.isNotBlank() }
    val valid =
        email != null &&
            ((command == INVITE && displayName != null) || (command == RESET && args.size == 2))
    if (!valid) {
        out.println(USAGE)
        return USAGE_ERROR
    }
    val settings = config()
    return DatabasePools(settings.database).use { pools ->
        val koin = koinApplication { modules(appModule(settings, pools)) }.koin
        val result = runCatching { runBlocking { koin.get<AdminAccountService>().bootstrap(email, displayName) } }
        result.fold(
            onSuccess = { (admin, link) ->
                out.println(
                    "Platform admin ${admin.id} is invited. Pass this link on; it works once, until ${Rfc3339.format(link.expiresAt)}:",
                )
                out.println(link.link)
                0
            },
            onFailure = {
                // The message names the rule that refused; it holds no email or secret.
                out.println("Refused: ${it.message ?: it::class.simpleName}")
                FAILED
            },
        )
    }
}

private const val INVITE = "invite-admin"
private const val RESET = "reset-admin-totp"
private const val USAGE_ERROR = 2
private const val FAILED = 1
private const val USAGE = "usage: $INVITE <email> <display name> | $RESET <email>"
