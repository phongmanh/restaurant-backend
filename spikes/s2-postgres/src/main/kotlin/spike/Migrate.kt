package spike

import org.flywaydb.core.Flyway
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.system.exitProcess

/**
 * Q6, run from CI (and locally): creates or updates the `app` role, runs Flyway as `migrator` over
 * the direct connection, then checks that `app` is denied DDL. Exits non-zero if a check fails.
 *
 * Reads `DATABASE_URL_MIGRATOR_DIRECT` and `DATABASE_URL_APP_DIRECT` (the app's password comes from
 * the latter). Prints no secret.
 */
fun main() {
    val migrator = JdbcTarget.parse(requiredEnv("DATABASE_URL_MIGRATOR_DIRECT"))
    val app = JdbcTarget.parse(requiredEnv("DATABASE_URL_APP_DIRECT"))
    val report = migrate(migrator, app)
    report.lines.forEach(::println)
    if (!report.ok) exitProcess(1)
}

class MigrateReport(val lines: List<String>, val ok: Boolean)

fun migrate(migrator: JdbcTarget, app: JdbcTarget): MigrateReport {
    val lines = mutableListOf<String>()
    var ok = true

    ensureAppRole(migrator, app)
    lines += "role ${app.user}: present, LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE"

    val result = Flyway.configure()
        .dataSource(migrator.jdbcUrl, migrator.user, migrator.password)
        .locations("classpath:db/migration")
        .load()
        .migrate()
    lines += "flyway: ${result.migrationsExecuted} migration(s) applied, schema version ${result.targetSchemaVersion ?: result.initialSchemaVersion}"

    for ((name, sql) in DDL_PROBES) {
        val denied = try {
            DriverManager.getConnection(app.jdbcUrl, app.user, app.password).use { conn ->
                conn.autoCommit = false
                try {
                    conn.createStatement().use { it.execute(sql) }
                    false
                } finally {
                    conn.rollback()
                }
            }
        } catch (e: SQLException) {
            if (e.sqlState != INSUFFICIENT_PRIVILEGE) throw e
            true
        }
        lines += "app $name: ${if (denied) "denied (42501), as required" else "ALLOWED — FAIL"}"
        ok = ok && denied
    }
    return MigrateReport(lines, ok)
}

/**
 * Creates `app` with plain SQL, as `migrator`. A role made in Neon's console would be a member of
 * neon_superuser, whose BYPASSRLS would make every RLS result meaningless.
 *
 * On a re-run it only resets the password: since PostgreSQL 16 a non-superuser (Neon's owner role)
 * may not even name SUPERUSER in ALTER ROLE, so the attributes are checked instead of re-set.
 */
private fun ensureAppRole(migrator: JdbcTarget, app: JdbcTarget) {
    require(app.user.matches(Regex("[a-z_][a-z0-9_]*"))) { "unexpected app role name" }
    // CREATE/ALTER ROLE take no bind parameters, so the password is quoted as a literal.
    val password = "'" + app.password.replace("'", "''") + "'"
    DriverManager.getConnection(migrator.jdbcUrl, migrator.user, migrator.password).use { conn ->
        val exists = conn.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?").use {
            it.setString(1, app.user)
            it.executeQuery().use { rs -> rs.next() }
        }
        conn.createStatement().use {
            if (exists) {
                it.execute("ALTER ROLE ${app.user} LOGIN PASSWORD $password")
            } else {
                it.execute("CREATE ROLE ${app.user} LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD $password")
            }
        }
        conn.prepareStatement(
            "SELECT rolsuper, rolbypassrls, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname = ?",
        ).use {
            it.setString(1, app.user)
            it.executeQuery().use { rs ->
                rs.next()
                check((1..4).none { i -> rs.getBoolean(i) }) {
                    "role ${app.user} has SUPERUSER, BYPASSRLS, CREATEDB or CREATEROLE; drop it and re-run"
                }
            }
        }
    }
}

private val DDL_PROBES = listOf(
    "CREATE TABLE" to "CREATE TABLE ddl_probe (id int)",
    "ALTER TABLE" to "ALTER TABLE orders ADD COLUMN ddl_probe int",
    "DROP POLICY" to "DROP POLICY tenant_isolation ON orders",
)

private const val INSUFFICIENT_PRIVILEGE = "42501"

private fun requiredEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("$name is not set (see README, owner steps)")
