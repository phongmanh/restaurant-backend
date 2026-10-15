package io.github.phongmanh.restaurant.core.persistence

import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager
import kotlin.system.exitProcess

/**
 * Database migrations (ADR-0004): creates or updates the two application roles, then runs Flyway
 * as the migrator over a direct (unpooled) connection.
 *
 * The roles are made with plain SQL here, **never in Neon's console**: Neon adds console-made roles
 * to `neon_superuser`, which bypasses row-level security and would quietly switch it off (S-2).
 * - `app`: DML only, no `BYPASSRLS`. Every tenant request runs as it.
 * - `app_platform`: DML only, with `BYPASSRLS`, for platform work (core-and-auth §7).
 */
object Migrations {
    private const val STATEMENT_TIMEOUT = "5s"
    private val ROLE_NAME = Regex("[a-z_][a-z0-9_]*")

    fun run(
        migrator: JdbcTarget,
        app: JdbcTarget,
        platform: JdbcTarget,
    ): Int {
        DriverManager.getConnection(migrator.jdbcUrl, migrator.user, migrator.password).use { conn ->
            ensureRole(conn, app, bypassRls = false)
            ensureRole(conn, platform, bypassRls = true)
        }
        val result =
            Flyway
                .configure()
                .dataSource(migrator.jdbcUrl, migrator.user, migrator.password)
                .locations("classpath:db/migration")
                .placeholders(mapOf("appRole" to app.user, "platformRole" to platform.user))
                .load()
                .migrate()
        return result.migrationsExecuted
    }

    /**
     * On a re-run only the password is reset: since PostgreSQL 16 a non-superuser (Neon's owner role)
     * may not even name SUPERUSER in ALTER ROLE, so the attributes are checked instead.
     */
    private fun ensureRole(
        conn: Connection,
        role: JdbcTarget,
        bypassRls: Boolean,
    ) {
        require(ROLE_NAME.matches(role.user)) { "unexpected role name" }
        // CREATE and ALTER ROLE take no bind parameters, so the password is quoted as a literal.
        val password = "'" + role.password.replace("'", "''") + "'"
        val exists =
            conn.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?").use {
                it.setString(1, role.user)
                it.executeQuery().use { rs -> rs.next() }
            }
        conn.createStatement().use {
            if (exists) {
                it.execute("ALTER ROLE ${role.user} LOGIN PASSWORD $password")
            } else {
                val rls = if (bypassRls) "BYPASSRLS" else "NOBYPASSRLS"
                it.execute("CREATE ROLE ${role.user} LOGIN NOSUPERUSER $rls NOCREATEDB NOCREATEROLE PASSWORD $password")
            }
            // A role setting, because the transaction pooler doesn't keep session settings; the
            // transactor also sets it in every transaction (core-and-auth §7).
            it.execute("ALTER ROLE ${role.user} SET statement_timeout = '$STATEMENT_TIMEOUT'")
        }
        conn.prepareStatement("SELECT rolsuper, rolcreatedb, rolcreaterole, rolbypassrls FROM pg_roles WHERE rolname = ?").use {
            it.setString(1, role.user)
            it.executeQuery().use { rs ->
                rs.next()
                check(!rs.getBoolean("rolsuper") && !rs.getBoolean("rolcreatedb") && !rs.getBoolean("rolcreaterole")) {
                    "role ${role.user} has SUPERUSER, CREATEDB or CREATEROLE; drop it and re-run"
                }
                check(
                    rs.getBoolean("rolbypassrls") == bypassRls,
                ) { "role ${role.user} must ${if (bypassRls) "" else "not "}have BYPASSRLS" }
            }
        }
    }
}

/**
 * `./gradlew migrate`: reads `DATABASE_URL_MIGRATOR_DIRECT`, `DATABASE_URL` and
 * `DATABASE_URL_PLATFORM` (the two roles' passwords come from the latter). Prints no secret.
 */
fun main() {
    fun env(name: String) =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: run {
            System.err.println("$name is not set")
            exitProcess(1)
        }
    val applied =
        Migrations.run(
            migrator = JdbcTarget.parse(env("DATABASE_URL_MIGRATOR_DIRECT")),
            app = JdbcTarget.parse(env("DATABASE_URL")),
            platform = JdbcTarget.parse(env("DATABASE_URL_PLATFORM")),
        )
    println("migrations applied: $applied")
}
