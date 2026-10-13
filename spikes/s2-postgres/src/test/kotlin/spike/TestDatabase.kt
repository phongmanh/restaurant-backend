package spike

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

/**
 * One real PostgreSQL 17 for the whole test run, in-process (no Docker), migrated the way CI does
 * it: `postgres` stands in for `migrator`, and [migrate] creates `app`. There is no pooler locally,
 * so both of the app's pools connect directly; pooler behaviour is only measured on Neon.
 */
object TestDatabase {
    private val postgres: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }

    val migrator: JdbcTarget by lazy { target("postgres", "postgres") }
    val app: JdbcTarget by lazy { target("app", "app-test-password") }

    val migration: MigrateReport by lazy { migrate(migrator, app) }

    val db: Database by lazy {
        check(migration.ok) { migration.lines.joinToString("\n") }
        Database(pooled = app, direct = app)
    }

    private fun target(user: String, password: String) =
        JdbcTarget(postgres.getJdbcUrl(user, "postgres").substringBefore('?'), user, password)
}
