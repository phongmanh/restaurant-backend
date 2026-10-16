-- The tables feature (tables §5). Tenant tables, RLS forced (ADR-0005).
--
-- Like V5, each reference to a parent row also names the restaurant, as a composite foreign key on
-- (restaurant_id, id): foreign-key checks don't go through RLS, so this is what stops a session
-- filed under another restaurant's table. No device ID, IP or user agent is stored (§13 Q3).

CREATE TABLE dining_tables (
    id              uuid PRIMARY KEY,
    restaurant_id   uuid NOT NULL REFERENCES restaurants (id),
    number          text NOT NULL CHECK (char_length(number) BETWEEN 1 AND 20),  -- "5", "A-5", "Garden 2"
    area            text CHECK (char_length(area) BETWEEN 1 AND 50),
    seats           smallint CHECK (seats BETWEEN 1 AND 50),
    enabled         boolean NOT NULL DEFAULT true,
    qr_key_version  integer NOT NULL DEFAULT 1,               -- +1 on rotation; printed codes carry it (§2)
    deleted_at      timestamptz,                              -- soft delete: closed sessions keep referring to it
    version         integer NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    UNIQUE (restaurant_id, id)
);
CREATE UNIQUE INDEX dining_tables_number_uq ON dining_tables (restaurant_id, lower(number)) WHERE deleted_at IS NULL;

CREATE TABLE table_sessions (
    id                uuid PRIMARY KEY,
    restaurant_id     uuid NOT NULL,
    table_id          uuid NOT NULL,
    status            text NOT NULL CHECK (status IN ('open', 'closed')),
    opened_at         timestamptz NOT NULL,
    opened_by         uuid,                                   -- staff ID; NULL when a guest scanned
    last_activity_at  timestamptz NOT NULL,                   -- join, order, call (§3.5)
    closed_at         timestamptz,
    closed_by         uuid,                                   -- staff ID; NULL = system (§3.5)
    close_reason      text CHECK (close_reason IN ('paid', 'abandoned_empty', 'closed_by_staff')),
    version           integer NOT NULL DEFAULT 0,
    CHECK ((status = 'closed') = (closed_at IS NOT NULL)),
    UNIQUE (restaurant_id, id),
    FOREIGN KEY (restaurant_id, table_id) REFERENCES dining_tables (restaurant_id, id)
);
-- One open session per table (§3): two first scans at once make one session.
CREATE UNIQUE INDEX table_sessions_one_open_uq ON table_sessions (table_id) WHERE status = 'open';
CREATE INDEX table_sessions_open_ix ON table_sessions (restaurant_id, status, last_activity_at) WHERE status = 'open';
CREATE INDEX table_sessions_closed_ix ON table_sessions (restaurant_id, closed_at);
-- The auto-close job looks across restaurants for idle sessions (§3.5).
CREATE INDEX table_sessions_idle_ix ON table_sessions (last_activity_at) WHERE status = 'open';

CREATE TABLE waiter_calls (                                   -- FR-008
    id               uuid PRIMARY KEY,
    restaurant_id    uuid NOT NULL,
    session_id       uuid NOT NULL,
    kind             text NOT NULL CHECK (kind IN ('call_waiter', 'request_bill')),
    status           text NOT NULL CHECK (status IN ('open', 'acknowledged', 'closed_with_session')),
    created_at       timestamptz NOT NULL,
    acknowledged_at  timestamptz,
    acknowledged_by  uuid,
    FOREIGN KEY (restaurant_id, session_id) REFERENCES table_sessions (restaurant_id, id)
);
-- Repeated taps merge into one open call per kind (§4).
CREATE UNIQUE INDEX waiter_calls_one_open_uq ON waiter_calls (session_id, kind) WHERE status = 'open';
CREATE INDEX waiter_calls_open_ix ON waiter_calls (restaurant_id, created_at) WHERE status = 'open';
CREATE INDEX waiter_calls_session_ix ON waiter_calls (restaurant_id, session_id, created_at);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['dining_tables', 'table_sessions', 'waiter_calls'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I'
            ' USING (restaurant_id = nullif(current_setting(''app.restaurant_id'', true), '''')::uuid)'
            ' WITH CHECK (restaurant_id = nullif(current_setting(''app.restaurant_id'', true), '''')::uuid)',
            t);
    END LOOP;
END $$;

-- All tables work runs in the restaurant's own transaction, as the app role. Nothing is deleted:
-- tables are soft-deleted, and sessions and calls are kept with the bills they belong to (§5).
-- The auto-close job (a platform transaction) only finds which restaurants have idle sessions;
-- it closes them in each restaurant's transaction (§3.5).
GRANT SELECT, INSERT, UPDATE ON dining_tables, table_sessions, waiter_calls TO ${appRole};
GRANT SELECT ON table_sessions TO ${platformRole};
