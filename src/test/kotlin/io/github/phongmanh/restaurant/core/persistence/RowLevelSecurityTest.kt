package io.github.phongmanh.restaurant.core.persistence

import io.github.phongmanh.restaurant.support.TestDatabase
import java.sql.Connection
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * IT-CORE-06 (ADR-0005, core-and-auth §7): the migrated schema keeps every tenant table behind
 * forced row-level security. A new table either has `restaurant_id` and the `tenant_isolation`
 * policy, or is named below as global, so a migration that forgets RLS fails here.
 */
class RowLevelSecurityTest {
    @BeforeTest
    fun setUp() = TestDatabase.truncate()

    private data class Table(
        val name: String,
        val hasRestaurantId: Boolean,
        val rlsEnabled: Boolean,
        val rlsForced: Boolean,
    )

    private data class Policy(
        val table: String,
        val name: String,
        val permissive: Boolean,
        val command: String,
        val using: String?,
        val withCheck: String?,
    )

    private fun Connection.tables(): List<Table> =
        prepareStatement(
            """
            SELECT c.relname,
                   EXISTS (SELECT 1 FROM pg_attribute a
                           WHERE a.attrelid = c.oid AND a.attname = 'restaurant_id' AND NOT a.attisdropped),
                   c.relrowsecurity, c.relforcerowsecurity
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND c.relname <> 'flyway_schema_history'
            ORDER BY c.relname
            """.trimIndent(),
        ).use {
            it.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) Table(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4)) else null
                }.toList()
            }
        }

    private fun Connection.policies(): List<Policy> =
        prepareStatement(
            "SELECT tablename, policyname, permissive = 'PERMISSIVE', cmd, qual, with_check FROM pg_policies WHERE schemaname = 'public'",
        ).use {
            it.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) {
                        Policy(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4), rs.getString(5), rs.getString(6))
                    } else {
                        null
                    }
                }.toList()
            }
        }

    @Test
    fun `every table with restaurant_id but staff_accounts has forced RLS and only the tenant policy`() {
        val (tables, policies) = TestDatabase.superuser().use { it.tables() to it.policies() }
        val tenant = tables.filter { it.hasRestaurantId && it.name !in GLOBAL }
        assertTrue(tenant.size >= MIN_TENANT_TABLES, "the catalogue query found the tenant tables: $tenant")
        for (t in tenant) {
            assertTrue(t.rlsEnabled && t.rlsForced, "${t.name}: ENABLE and FORCE ROW LEVEL SECURITY")
            val own = policies.filter { it.table == t.name }
            // Permissive policies add up, so a second one could only widen what a tenant sees.
            assertEquals(listOf("tenant_isolation"), own.map { it.name }, "${t.name}: exactly one policy")
            val p = own.single()
            assertTrue(p.permissive && p.command == "ALL", "${t.name}: one permissive policy for every command")
            for (expr in listOf(p.using, p.withCheck)) {
                assertTrue(
                    expr != null && "restaurant_id = " in expr && "current_setting('app.restaurant_id'" in expr,
                    "${t.name}: USING and WITH CHECK compare restaurant_id with the tenant setting, got $expr",
                )
            }
        }
    }

    @Test
    fun `every table without restaurant_id is named as global`() {
        val tables = TestDatabase.superuser().use { it.tables() }
        val unnamed = tables.filter { !it.hasRestaurantId && it.name !in GLOBAL }.map { it.name }
        assertEquals(emptyList<String>(), unnamed, "a new table either has restaurant_id and RLS, or is added to GLOBAL with its reason")
        assertEquals(emptySet<String>(), GLOBAL - tables.map { it.name }.toSet(), "GLOBAL names only tables that exist")
    }

    @Test
    fun `the audit chain heads are isolated by chain`() {
        val (tables, policies) = TestDatabase.superuser().use { it.tables() to it.policies() }
        val heads = tables.single { it.name == "audit_chain_heads" }
        assertTrue(heads.rlsEnabled && heads.rlsForced, "audit_chain_heads: forced RLS")
        val p = policies.single { it.table == "audit_chain_heads" }
        assertTrue("chain_key = " in p.using!! && "chain_key = " in p.withCheck!!, "keyed by chain_key: $p")
    }

    private companion object {
        /** All 22 tables that carry restaurant_id today, less staff_accounts; a guard against a query that finds none. */
        const val MIN_TENANT_TABLES = 22

        /**
         * Global tables, outside RLS. Their isolation is the repositories' filter (IT-CORE-07), or
         * they're reached only through a token hash or an account already found in its restaurant.
         */
        val GLOBAL =
            setOf(
                "restaurants",
                "staff_accounts", // platform admins have no restaurant (core-and-auth §16 Q1)
                "staff_sessions",
                "staff_refresh_tokens",
                "staff_mfa_challenges",
                "staff_link_tokens",
                "audit_chain_heads", // RLS by chain_key, checked on its own above
            )
    }
}
