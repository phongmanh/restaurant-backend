-- S-2 spike schema. Runs as `migrator` over the direct connection; the role `app` exists already
-- (Migrate.kt creates it with plain SQL, so it is not a member of Neon's neon_superuser).

CREATE TABLE restaurants (
    restaurant_id uuid PRIMARY KEY,
    name          text NOT NULL
);

CREATE TABLE orders (
    id            uuid PRIMARY KEY,
    restaurant_id uuid NOT NULL REFERENCES restaurants,
    status        text NOT NULL,
    total_minor   bigint NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX orders_restaurant_idx ON orders (restaurant_id, created_at);

CREATE TABLE order_lines (
    id            uuid PRIMARY KEY,
    restaurant_id uuid NOT NULL REFERENCES restaurants,
    order_id      uuid NOT NULL REFERENCES orders,
    item          text NOT NULL,
    quantity      int NOT NULL,
    price_minor   bigint NOT NULL
);
CREATE INDEX order_lines_order_idx ON order_lines (order_id);

CREATE TABLE events (
    id            uuid PRIMARY KEY,
    restaurant_id uuid NOT NULL REFERENCES restaurants,
    order_id      uuid NOT NULL REFERENCES orders,
    type          text NOT NULL,
    payload       jsonb NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX events_order_idx ON events (order_id);

CREATE TABLE outbox (
    id            uuid PRIMARY KEY,
    restaurant_id uuid NOT NULL REFERENCES restaurants,
    topic         text NOT NULL,
    payload       jsonb NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    published_at  timestamptz
);
CREATE INDEX outbox_unpublished_idx ON outbox (created_at) WHERE published_at IS NULL;

CREATE TABLE idempotency_keys (
    restaurant_id uuid NOT NULL REFERENCES restaurants,
    key           text NOT NULL,
    order_id      uuid NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (restaurant_id, key)
);

-- Two tenants with one order each, so the RLS checks have something to (not) see.
-- Seeded before RLS is switched on.
INSERT INTO restaurants (restaurant_id, name) VALUES
    ('00000000-0000-0000-0000-00000000000a', 'Restaurant A'),
    ('00000000-0000-0000-0000-00000000000b', 'Restaurant B');
INSERT INTO orders (id, restaurant_id, status, total_minor) VALUES
    ('00000000-0000-0000-0001-00000000000a', '00000000-0000-0000-0000-00000000000a', 'PLACED', 1000),
    ('00000000-0000-0000-0001-00000000000b', '00000000-0000-0000-0000-00000000000b', 'PLACED', 2000);

-- RLS on every tenant table, forced so the table owner is held to it too. The policy reads the
-- transaction's `app.restaurant_id` (set with set_config(..., true), the bindable form of SET LOCAL).
-- `missing_ok = true` and nullif() make an unset or reset variable compare to NULL, so a
-- transaction that forgot to set it sees 0 rows and can't write, instead of failing with an error.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['restaurants', 'orders', 'order_lines', 'events', 'outbox', 'idempotency_keys'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I'
            ' USING (restaurant_id = nullif(current_setting(''app.restaurant_id'', true), '''')::uuid)'
            ' WITH CHECK (restaurant_id = nullif(current_setting(''app.restaurant_id'', true), '''')::uuid)',
            t);
    END LOOP;
END $$;

-- `app` gets DML only, table by table (not ON ALL TABLES, which would include flyway_schema_history).
GRANT SELECT ON restaurants TO app;
GRANT SELECT, INSERT, UPDATE, DELETE ON orders, order_lines, events, outbox, idempotency_keys TO app;
