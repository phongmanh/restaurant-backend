-- The event log's write half (realtime §3, §4.1), built with the menu (step 5), its first writer.
-- Publishing to Ably, the sweep, the purge and the catch-up reads come with the realtime feature
-- (step 8); until then the outbox rows wait unsent.

-- The per-restaurant counter (restaurants §2 moved it here). Created on a restaurant's first event.
CREATE TABLE event_counters (
    restaurant_id  uuid PRIMARY KEY,
    last_seq       bigint NOT NULL
);

CREATE TABLE domain_events (
    restaurant_id      uuid NOT NULL,
    seq                bigint NOT NULL,
    id                 uuid NOT NULL UNIQUE,                    -- UUID v7; Ably message ID (idempotent publish)
    occurred_at        timestamptz NOT NULL,
    type               text NOT NULL,                           -- realtime §5
    aggregate_type     text NOT NULL CHECK (aggregate_type IN ('order', 'bill', 'table_session', 'waiter_call', 'menu')),
    aggregate_id       uuid NOT NULL,
    aggregate_version  integer,
    session_id         uuid,                                    -- set when guests of that session see it
    audience           text NOT NULL CHECK (audience IN ('staff', 'staff_and_session')),
    PRIMARY KEY (restaurant_id, seq),
    CHECK (audience = 'staff' OR session_id IS NOT NULL)
);
CREATE INDEX domain_events_session_ix ON domain_events (restaurant_id, session_id, seq) WHERE session_id IS NOT NULL;
CREATE INDEX domain_events_age_ix ON domain_events (occurred_at);

CREATE TABLE outbox (
    event_id         uuid PRIMARY KEY REFERENCES domain_events (id) ON DELETE CASCADE,
    restaurant_id    uuid NOT NULL,
    created_at       timestamptz NOT NULL,
    published_at     timestamptz,
    attempts         smallint NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL
);
CREATE INDEX outbox_pending_ix ON outbox (next_attempt_at) WHERE published_at IS NULL;
CREATE INDEX outbox_pending_restaurant_ix ON outbox (restaurant_id, next_attempt_at) WHERE published_at IS NULL;

-- Tenant tables: RLS forced, the same policy as V1's.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['event_counters', 'domain_events', 'outbox'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I'
            ' USING (restaurant_id = nullif(current_setting(''app.restaurant_id'', true), '''')::uuid)'
            ' WITH CHECK (restaurant_id = nullif(current_setting(''app.restaurant_id'', true), '''')::uuid)',
            t);
    END LOOP;
END $$;

-- Writers append in their own restaurant's transaction. Marking rows sent is UPDATE; the purge job
-- (step 8, platform role) deletes old events, and their outbox rows go with them.
GRANT SELECT, INSERT, UPDATE ON event_counters TO ${appRole}, ${platformRole};
GRANT SELECT, INSERT ON domain_events TO ${appRole}, ${platformRole};
GRANT SELECT, INSERT, UPDATE ON outbox TO ${appRole}, ${platformRole};
