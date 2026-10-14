-- S-5 spike schema. Runs as `migrator` over the direct connection, in its own schema `s5` so the
-- S-2 tables in the same database are left alone. The role `app` exists already (S-2 Migrate.kt).
-- Re-running drops and rebuilds everything.

DROP SCHEMA IF EXISTS s5 CASCADE;
CREATE SCHEMA s5;

CREATE TABLE s5.restaurants (
    restaurant_id uuid PRIMARY KEY,
    name          text NOT NULL,
    time_zone     text NOT NULL
);

CREATE TABLE s5.menu_items (
    id            uuid PRIMARY KEY,
    restaurant_id uuid NOT NULL REFERENCES s5.restaurants,
    ord           int NOT NULL,           -- 1..120, used by the generator to pick items by popularity
    name          text NOT NULL,
    price_minor   bigint NOT NULL,        -- VND
    UNIQUE (restaurant_id, ord)
);

-- A table session is one visit and one bill (FR-013): closed with a payment method.
CREATE TABLE s5.table_sessions (
    id                    uuid PRIMARY KEY,
    restaurant_id         uuid NOT NULL REFERENCES s5.restaurants,
    table_no              int NOT NULL,
    status                text NOT NULL,  -- open | closed
    opened_at             timestamptz NOT NULL,
    closed_at             timestamptz,
    payment_method        text,           -- cash | card | bank_transfer
    amount_received_minor bigint
);

CREATE TABLE s5.orders (
    id            uuid PRIMARY KEY,
    restaurant_id uuid NOT NULL REFERENCES s5.restaurants,
    session_id    uuid NOT NULL REFERENCES s5.table_sessions,
    status        text NOT NULL,          -- served | cancelled | rejected (history is all finished)
    placed_at     timestamptz NOT NULL
);

CREATE TABLE s5.order_lines (
    id               uuid PRIMARY KEY,
    restaurant_id    uuid NOT NULL REFERENCES s5.restaurants,
    order_id         uuid NOT NULL REFERENCES s5.orders,
    item_id          uuid NOT NULL REFERENCES s5.menu_items,
    quantity         int NOT NULL,
    unit_price_minor bigint NOT NULL,     -- price snapshot (FR-003)
    status           text NOT NULL        -- served | rejected (FR-015)
);

-- daily_sales (architecture §4, §9): one row per restaurant and local day, plus one table per
-- breakdown FR-050 asks for. Written when a session closes, rebuilt from the raw rows nightly.
CREATE TABLE s5.daily_sales (
    restaurant_id uuid NOT NULL REFERENCES s5.restaurants,
    sales_date    date NOT NULL,          -- in the restaurant's time zone
    sessions      bigint NOT NULL,
    orders        bigint NOT NULL,
    gross_minor   bigint NOT NULL,
    PRIMARY KEY (restaurant_id, sales_date)
);

CREATE TABLE s5.daily_sales_payment (
    restaurant_id uuid NOT NULL REFERENCES s5.restaurants,
    sales_date    date NOT NULL,
    method        text NOT NULL,
    sessions      bigint NOT NULL,
    amount_minor  bigint NOT NULL,
    PRIMARY KEY (restaurant_id, sales_date, method)
);

CREATE TABLE s5.daily_sales_table (
    restaurant_id uuid NOT NULL REFERENCES s5.restaurants,
    sales_date    date NOT NULL,
    table_no      int NOT NULL,
    sessions      bigint NOT NULL,
    gross_minor   bigint NOT NULL,
    PRIMARY KEY (restaurant_id, sales_date, table_no)
);

CREATE TABLE s5.daily_sales_item (
    restaurant_id uuid NOT NULL REFERENCES s5.restaurants,
    sales_date    date NOT NULL,
    item_id       uuid NOT NULL,
    quantity      bigint NOT NULL,
    revenue_minor bigint NOT NULL,
    PRIMARY KEY (restaurant_id, sales_date, item_id)
);
