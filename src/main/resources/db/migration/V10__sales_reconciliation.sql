-- The reports feature's nightly rebuild (reports §3, §5). Tenant table, RLS forced (ADR-0005).

-- How far the rebuild has got for each restaurant.
CREATE TABLE sales_reconciliation (
    restaurant_id       uuid PRIMARY KEY REFERENCES restaurants (id),
    reconciled_through  date NOT NULL,                                       -- the restaurant's local yesterday at the last run
    last_run_at         timestamptz NOT NULL,
    last_mismatch_on    date                                                 -- the most recent day that had to be repaired
);

ALTER TABLE sales_reconciliation ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales_reconciliation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sales_reconciliation
    USING (restaurant_id = nullif(current_setting('app.restaurant_id', true), '')::uuid)
    WITH CHECK (restaurant_id = nullif(current_setting('app.restaurant_id', true), '')::uuid);

-- Each restaurant's days are rebuilt in its own transaction, as the app role: a day whose figures
-- differ is deleted and written again. The job (a platform transaction) only reads which
-- restaurants are due.
GRANT SELECT, INSERT, UPDATE ON sales_reconciliation TO ${appRole};
GRANT DELETE ON daily_sales, daily_sales_payment, daily_sales_table, daily_sales_item TO ${appRole};
GRANT SELECT ON sales_reconciliation TO ${platformRole};
