-- The reports feature's write half (reports §3–4), built with orders, its first writer (owner,
-- 2026-10-16). The daily aggregates are kept current by SalesLedger inside each close or
-- correction; the report query and the nightly rebuild (with its sales_reconciliation table)
-- come with reports in step 9. Tenant tables, RLS forced (ADR-0005).

CREATE TABLE daily_sales (
    restaurant_id       uuid NOT NULL REFERENCES restaurants (id),
    sales_date          date NOT NULL,                                       -- local date (reports §2)
    sessions            integer NOT NULL DEFAULT 0,                          -- bills closed with a payment (reports §13 Q2)
    orders              integer NOT NULL DEFAULT 0,
    gross               bigint NOT NULL DEFAULT 0,                           -- sum of those bills
    corrections         bigint NOT NULL DEFAULT 0,                           -- signed sum of corrections made that day
    correction_count    integer NOT NULL DEFAULT 0,
    PRIMARY KEY (restaurant_id, sales_date)
);

CREATE TABLE daily_sales_payment (
    restaurant_id   uuid NOT NULL REFERENCES restaurants (id),
    sales_date      date NOT NULL,
    method          text NOT NULL CHECK (method IN ('cash', 'card', 'bank_transfer')),
    sessions        integer NOT NULL DEFAULT 0,
    amount          bigint NOT NULL DEFAULT 0,                               -- bills paid with this method
    corrections     bigint NOT NULL DEFAULT 0,                               -- corrections booked to this method
    PRIMARY KEY (restaurant_id, sales_date, method)
);

CREATE TABLE daily_sales_table (
    restaurant_id   uuid NOT NULL REFERENCES restaurants (id),
    sales_date      date NOT NULL,
    table_id        uuid NOT NULL,
    table_number    text NOT NULL,                                           -- as it was at the last close that day
    sessions        integer NOT NULL,
    gross           bigint NOT NULL,
    PRIMARY KEY (restaurant_id, sales_date, table_id)
);

CREATE TABLE daily_sales_item (
    restaurant_id   uuid NOT NULL REFERENCES restaurants (id),
    sales_date      date NOT NULL,
    item_id         uuid NOT NULL,
    name_vi         text NOT NULL,                                           -- snapshot from the order lines
    name_en         text,
    quantity        integer NOT NULL,
    revenue         bigint NOT NULL,                                         -- line totals, options included
    PRIMARY KEY (restaurant_id, sales_date, item_id)
);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['daily_sales', 'daily_sales_payment', 'daily_sales_table', 'daily_sales_item'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I'
            ' USING (restaurant_id = nullif(current_setting(''app.restaurant_id'', true), '''')::uuid)'
            ' WITH CHECK (restaurant_id = nullif(current_setting(''app.restaurant_id'', true), '''')::uuid)',
            t);
    END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE ON daily_sales, daily_sales_payment, daily_sales_table, daily_sales_item TO ${appRole};
