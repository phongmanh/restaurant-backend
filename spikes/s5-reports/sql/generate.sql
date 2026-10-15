-- S-5 synthetic data: one year of closed bills for one large and one small restaurant.
-- Generated inside PostgreSQL, so nothing is sent over the network. `{{LAST_DAY}}` is replaced by
-- scripts/s5.mjs (default 2026-10-13); the year is the 365 local days ending on it.
--
-- Large restaurant: 40 tables, ≈ 270 bills on weekdays and ≈ 380 at weekends (≈ 300 a day, the
-- NFR-PERF-04 restaurant). Small: 10 tables, ≈ 30 a day. Per bill ≈ 2 orders, per order ≈ 3 lines
-- (ASSUMPTION-9's 3 lines per order). 2% of orders cancelled, 1% rejected, 1% of lines rejected.
-- Lunch 11:00–14:00 (40%), dinner 17:00–22:30, 30–150 min each, so some dinners close after
-- midnight local time and count on the next day: that tests the time-zone day boundary.
--
-- Rows are inserted in time order, as production would write them, and get UUID v7 IDs from
-- their own timestamps (PostgreSQL 18 uuidv7(shift)). `OFFSET 0` stops the planner from inlining
-- the subqueries, which would evaluate each random() more than once per row.

SELECT setseed(0.5);

INSERT INTO s5.restaurants (restaurant_id, name, time_zone) VALUES
    ('00000000-0000-0000-0005-00000000000a', 'Large restaurant', 'Asia/Ho_Chi_Minh'),
    ('00000000-0000-0000-0005-00000000000b', 'Small restaurant', 'Asia/Ho_Chi_Minh');

INSERT INTO s5.menu_items (id, restaurant_id, ord, name, price_minor)
SELECT gen_random_uuid(), r.restaurant_id, i, 'Item ' || i, (20 + floor(random() * 29)::int * 10) * 1000
FROM s5.restaurants r, generate_series(1, 120) i;

INSERT INTO s5.table_sessions (id, restaurant_id, table_no, status, opened_at, closed_at, payment_method)
SELECT uuidv7(x.opened_at - now()), x.restaurant_id, x.table_no, 'closed', x.opened_at, x.opened_at + x.duration,
       CASE WHEN x.m < 0.5 THEN 'cash' WHEN x.m < 0.8 THEN 'card' ELSE 'bank_transfer' END
FROM (
    SELECT r.restaurant_id,
           1 + floor(random() * r.tables)::int AS table_no,
           (d.day + CASE WHEN random() < 0.4 THEN time '11:00' + random() * interval '3 hours'
                         ELSE time '17:00' + random() * interval '5 hours 30 minutes' END)
               AT TIME ZONE 'Asia/Ho_Chi_Minh' AS opened_at,
           interval '30 minutes' + random() * interval '120 minutes' AS duration,
           random() AS m
    FROM (VALUES ('00000000-0000-0000-0005-00000000000a'::uuid, 40, 270, 380),
                 ('00000000-0000-0000-0005-00000000000b'::uuid, 10, 27, 38)) r(restaurant_id, tables, weekday, weekend)
    CROSS JOIN (SELECT g::date AS day
                FROM generate_series(date '{{LAST_DAY}}' - 364, date '{{LAST_DAY}}', interval '1 day') g) d
    CROSS JOIN LATERAL generate_series(1, round(
        CASE WHEN extract(isodow FROM d.day) >= 6 THEN r.weekend ELSE r.weekday END * (0.9 + random() * 0.2))::int) n
    OFFSET 0
) x
ORDER BY x.opened_at;

INSERT INTO s5.orders (id, restaurant_id, session_id, status, placed_at)
SELECT uuidv7(p.placed_at - now()), p.restaurant_id, p.session_id,
       CASE WHEN p.st < 0.02 THEN 'cancelled' WHEN p.st < 0.03 THEN 'rejected' ELSE 'served' END,
       p.placed_at
FROM (
    SELECT s.restaurant_id, s.id AS session_id,
           s.opened_at + random() * (s.closed_at - s.opened_at) * 0.8 AS placed_at,
           random() AS st
    FROM s5.table_sessions s
    CROSS JOIN LATERAL generate_series(1,
        1 + (random() < 0.6)::int + (random() < 0.3)::int + (random() < 0.1)::int + 0 * s.table_no) g
    OFFSET 0
) p
ORDER BY p.placed_at;

INSERT INTO s5.order_lines (id, restaurant_id, order_id, item_id, quantity, unit_price_minor, status)
SELECT uuidv7(p.placed_at - now()), p.restaurant_id, p.order_id, m.id, p.quantity, m.price_minor,
       CASE WHEN p.st < 0.01 THEN 'rejected' ELSE 'served' END
FROM (
    SELECT o.restaurant_id, o.id AS order_id, o.placed_at,
           1 + floor(120 * power(random(), 2))::int AS ord,              -- popular items first
           1 + (random() < 0.3)::int + (random() < 0.1)::int AS quantity,
           random() AS st
    FROM s5.orders o
    CROSS JOIN LATERAL generate_series(1,
        1 + (random() < 0.8)::int + (random() < 0.6)::int + (random() < 0.4)::int + (random() < 0.2)::int
          + 0 * extract(epoch FROM o.placed_at)::bigint) g
    OFFSET 0
) p
JOIN s5.menu_items m ON m.restaurant_id = p.restaurant_id AND m.ord = p.ord
ORDER BY p.placed_at;
