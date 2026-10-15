package spike

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

/**
 * One real PostgreSQL 18 for the whole test run, in-process (no Docker), set up like Neon: the
 * migrator is a non-superuser with CREATEROLE that owns the database, and [migrate] creates `app`.
 * There is no pooler locally, so both of the app's pools connect directly; pooler behaviour is only
 * measured on Neon.
 */
object TestDatabase {
    private val postgres: EmbeddedPostgres by lazy {
        EmbeddedPostgres.start().also { pg ->
            pg.postgresDatabase.connection.use { conn ->
                conn.createStatement().use {
                    it.execute("CREATE ROLE migrator LOGIN CREATEROLE PASSWORD 'migrator-test-password'")
                    it.execute("CREATE DATABASE spike OWNER migrator")
                }
            }
        }
    }

    val migrator: JdbcTarget by lazy { target("migrator", "migrator-test-password") }
    val app: JdbcTarget by lazy { target("app", "app-test-password") }

    val migration: MigrateReport by lazy { migrate(migrator, app) }

    val db: Database by lazy {
        check(migration.ok) { migration.lines.joinToString("\n") }
        Database(pooled = app, direct = app)
    }

    private fun target(user: String, password: String) =
        JdbcTarget(postgres.getJdbcUrl(user, "spike").substringBefore('?'), user, password)
}
