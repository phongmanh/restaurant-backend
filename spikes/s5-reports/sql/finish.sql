-- After the load: indexes, grants and RLS (same policy as S-2 and ADR-0005), then statistics.
-- scripts/s5.mjs fills daily_sales from the raw rows before this file runs, while the owner can
-- still write without setting a tenant.

CREATE INDEX table_sessions_closed_idx ON s5.table_sessions (restaurant_id, closed_at) WHERE status = 'closed';
CREATE INDEX orders_session_idx ON s5.orders (session_id);
CREATE INDEX order_lines_order_idx ON s5.order_lines (order_id);

GRANT USAGE ON SCHEMA s5 TO app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA s5 TO app;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['restaurants', 'menu_items', 'table_sessions', 'orders', 'order_lines',
                             'daily_sales', 'daily_sales_payment', 'daily_sales_table', 'daily_sales_item'] LOOP
        EXECUTE format('ALTER TABLE s5.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE s5.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON s5.%I'
            ' USING (restaurant_id = nullif(current_setting(''app.restaurant_id'', true), '''')::uuid)'
            ' WITH CHECK (restaurant_id = nullif(current_setting(''app.restaurant_id'', true), '''')::uuid)',
            t);
    END LOOP;
END
$$;

ANALYZE s5.restaurants, s5.menu_items, s5.table_sessions, s5.orders, s5.order_lines,
        s5.daily_sales, s5.daily_sales_payment, s5.daily_sales_table, s5.daily_sales_item;
