-- S-2 Q6: a harmless migration for CI to apply (V1 was applied from the laptop).
COMMENT ON TABLE orders IS 'S-2 spike: one row per placed order; RLS by restaurant_id';
