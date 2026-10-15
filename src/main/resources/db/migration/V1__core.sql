-- Core tables (core-and-auth §9, §12). Runs as the migrator over a direct connection; Migrations.kt
-- creates ${appRole} and ${platformRole} first, with plain SQL (ADR-0004).
--
-- Row-level security (ADR-0005): every tenant table has RLS enabled and FORCED (so the owner is held
-- to it too) with the policy below. It compares restaurant_id with the transaction's
-- app.restaurant_id, which the transactor sets with set_config(…, true). An unset or empty setting
-- reads as NULL, so a transaction that forgot it sees 0 rows and can write none, instead of failing.
-- ${platformRole} has BYPASSRLS and writes the rows whose restaurant_id is NULL (platform work).

CREATE TABLE idempotency_keys (
    scope            text NOT NULL,                  -- guest:<device> | staff:<id> | admin:<id>
    operation        text NOT NULL,                  -- route template
    key              text NOT NULL,
    restaurant_id    uuid,                           -- NULL: a platform-admin operation
    request_hash     bytea NOT NULL,
    response_status  integer,                        -- set before commit, so never NULL once committed
    response_headers jsonb,
    response_body    bytea,
    created_at       timestamptz NOT NULL,
    PRIMARY KEY (scope, operation, key)
);
CREATE INDEX idempotency_keys_created_ix ON idempotency_keys (created_at);

CREATE TABLE audit_events (
    id            uuid PRIMARY KEY,                  -- UUID v7
    restaurant_id uuid,                              -- NULL: the platform chain
    chain_seq     bigint NOT NULL,
    occurred_at   timestamptz NOT NULL,
    actor_type    text NOT NULL CHECK (actor_type IN ('staff', 'admin', 'guest', 'system', 'anonymous')),
    actor_id      uuid,
    action        text NOT NULL,                     -- e.g. auth.sign_in.failed
    target_type   text,
    target_id     uuid,
    request_id    text,
    ip            inet,
    user_agent    text,                              -- first 200 characters
    details       jsonb NOT NULL DEFAULT '{}',       -- no secrets, passwords, tokens or free text
    prev_hash     bytea NOT NULL,
    hash          bytea NOT NULL,
    UNIQUE NULLS NOT DISTINCT (restaurant_id, chain_seq)
);

-- One row per chain: the restaurant's ID, or the nil UUID for the platform chain.
CREATE TABLE audit_chain_heads (
    chain_key  uuid PRIMARY KEY,
    last_seq   bigint NOT NULL,
    last_hash  bytea NOT NULL
);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['idempotency_keys', 'audit_events'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I'
            ' USING (restaurant_id = nullif(current_setting(''app.restaurant_id'', true), '''')::uuid)'
            ' WITH CHECK (restaurant_id = nullif(current_setting(''app.restaurant_id'', true), '''')::uuid)',
            t);
    END LOOP;
END $$;

-- The heads are keyed by chain, not restaurant_id; the same rule applies to chain_key.
ALTER TABLE audit_chain_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_chain_heads FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_chain_heads
    USING (chain_key = nullif(current_setting('app.restaurant_id', true), '')::uuid)
    WITH CHECK (chain_key = nullif(current_setting('app.restaurant_id', true), '')::uuid);

-- DML only, table by table (not ON ALL TABLES, which would include flyway_schema_history).
-- The audit log is append-only: no UPDATE or DELETE for either role (§12; a test checks it).
-- Purging old idempotency keys is a platform job, so only ${platformRole} may DELETE them.
GRANT SELECT, INSERT, UPDATE ON idempotency_keys TO ${appRole};
GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_keys TO ${platformRole};
GRANT SELECT, INSERT ON audit_events TO ${appRole}, ${platformRole};
GRANT SELECT, INSERT, UPDATE ON audit_chain_heads TO ${appRole}, ${platformRole};
