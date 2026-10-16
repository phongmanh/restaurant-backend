-- The orders feature (orders §6). Tenant tables, RLS forced (ADR-0005). Money is bigint whole đồng.
--
-- Like V5 and V6, each reference to a parent row also names the restaurant, as a composite foreign
-- key on (restaurant_id, …): foreign-key checks don't go through RLS, so this is what stops an
-- order filed under another restaurant's bill.

-- One bill per table session, created with the session's first order (§3.1 step 5) or at close (§7.1).
-- The bill row is the session's lock and its running total.
CREATE TABLE bills (
    session_id         uuid PRIMARY KEY,
    restaurant_id      uuid NOT NULL,
    status             text NOT NULL CHECK (status IN ('open', 'closed')),
    order_count        integer NOT NULL DEFAULT 0 CHECK (order_count >= 0),  -- orders not cancelled or rejected
    total              bigint NOT NULL DEFAULT 0 CHECK (total >= 0),
    adjustments_total  bigint NOT NULL DEFAULT 0,                            -- §7.2; signed
    payment_method     text CHECK (payment_method IN ('cash', 'card', 'bank_transfer')),
    amount_received    bigint CHECK (amount_received >= 0),
    closed_at          timestamptz,
    closed_by          uuid,                                                 -- staff ID; NULL = system (tables §3.5)
    business_date      date,                                                 -- local day of the close (ASSUMPTION-11)
    version            integer NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL,
    CHECK ((status = 'closed') = (closed_at IS NOT NULL)),
    CHECK ((status = 'closed') = (business_date IS NOT NULL)),
    CHECK ((payment_method IS NULL) = (amount_received IS NULL)),
    CHECK (total + adjustments_total >= 0),
    UNIQUE (restaurant_id, session_id),
    FOREIGN KEY (restaurant_id, session_id) REFERENCES table_sessions (restaurant_id, id)
);
CREATE INDEX bills_closed_ix ON bills (restaurant_id, business_date, closed_at) WHERE status = 'closed';

CREATE TABLE orders (
    id                  uuid PRIMARY KEY,
    restaurant_id       uuid NOT NULL,
    session_id          uuid NOT NULL,
    status              text NOT NULL CHECK (status IN
                          ('placed', 'accepted', 'preparing', 'ready', 'served', 'rejected', 'cancelled')),
    placed_at           timestamptz NOT NULL,
    placed_by_staff     uuid,                                                -- FR-014; NULL = a guest
    total               bigint NOT NULL CHECK (total >= 0),                  -- active lines only
    status_changed_at   timestamptz NOT NULL,
    status_changed_by   uuid,                                                -- staff ID; NULL = guest or system
    reason_code         text CHECK (reason_code IN ('item_unavailable', 'kitchen_closed', 'duplicate_order',
                          'not_at_table', 'customer_request', 'closed_with_session', 'other')),
    reason_note         text CHECK (char_length(reason_note) <= 200),       -- staff free text; cleared after 30 days
    version             integer NOT NULL DEFAULT 0,
    CHECK ((status IN ('rejected', 'cancelled')) OR reason_code IS NULL),
    CHECK (status <> 'rejected' OR reason_code IS NOT NULL),
    UNIQUE (restaurant_id, id),
    FOREIGN KEY (restaurant_id, session_id) REFERENCES bills (restaurant_id, session_id)
);
CREATE INDEX orders_session_ix ON orders (restaurant_id, session_id, placed_at);
CREATE INDEX orders_active_ix ON orders (restaurant_id, placed_at, id)
    WHERE status IN ('placed', 'accepted', 'preparing', 'ready');
-- The retention job finds reason notes to clear (§6); cleared rows leave the index.
CREATE INDEX orders_notes_ix ON orders (restaurant_id, session_id) WHERE reason_note IS NOT NULL;

CREATE TABLE order_lines (
    id                uuid PRIMARY KEY,
    restaurant_id     uuid NOT NULL,
    order_id          uuid NOT NULL,
    line_no           smallint NOT NULL CHECK (line_no BETWEEN 1 AND 50),
    item_id           uuid NOT NULL,                                         -- no FK: the snapshot below is the record (menu §3)
    name_vi           text NOT NULL,
    name_en           text,
    options           jsonb NOT NULL DEFAULT '[]',                           -- [{option_id, group_name_vi, group_name_en, name_vi, name_en, price_delta}]
    unit_price        bigint NOT NULL CHECK (unit_price >= 0),
    unit_total        bigint NOT NULL CHECK (unit_total >= unit_price),      -- price + option deltas (menu §14 Q5)
    quantity          smallint NOT NULL CHECK (quantity BETWEEN 1 AND 20),
    line_total        bigint NOT NULL CHECK (line_total = unit_total * quantity),
    note              text CHECK (char_length(note) <= 200),                 -- guest free text; cleared after 30 days
    status            text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'rejected')),
    reason_code       text CHECK (reason_code IN ('item_unavailable', 'kitchen_closed', 'duplicate_order',
                        'not_at_table', 'customer_request', 'other')),
    reason_note       text CHECK (char_length(reason_note) <= 200),
    rejected_at       timestamptz,
    rejected_by       uuid,
    UNIQUE (order_id, line_no),
    CHECK ((status = 'rejected') = (rejected_at IS NOT NULL)),
    CHECK ((status = 'rejected') = (reason_code IS NOT NULL)),
    FOREIGN KEY (restaurant_id, order_id) REFERENCES orders (restaurant_id, id)
);
CREATE INDEX order_lines_order_ix ON order_lines (restaurant_id, order_id);
CREATE INDEX order_lines_notes_ix ON order_lines (restaurant_id, order_id) WHERE note IS NOT NULL OR reason_note IS NOT NULL;

-- Corrections to a closed bill (TBD-7, §7.2); the closed bill itself never changes.
CREATE TABLE bill_adjustments (
    id              uuid PRIMARY KEY,
    restaurant_id   uuid NOT NULL,
    session_id      uuid NOT NULL,
    amount          bigint NOT NULL CHECK (amount <> 0),                     -- signed: negative = refund or discount
    payment_method  text NOT NULL CHECK (payment_method IN ('cash', 'card', 'bank_transfer')),
    reason_code     text NOT NULL CHECK (reason_code IN ('wrong_item_billed', 'wrong_payment_method', 'goodwill', 'other')),
    note            text CHECK (char_length(note) <= 200),
    created_at      timestamptz NOT NULL,
    created_by      uuid NOT NULL,
    business_date   date NOT NULL,                                           -- local day of the correction, not of the bill
    FOREIGN KEY (restaurant_id, session_id) REFERENCES bills (restaurant_id, session_id)
);
CREATE INDEX bill_adjustments_session_ix ON bill_adjustments (restaurant_id, session_id, created_at);
CREATE INDEX bill_adjustments_day_ix ON bill_adjustments (restaurant_id, business_date);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['bills', 'orders', 'order_lines', 'bill_adjustments'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I'
            ' USING (restaurant_id = nullif(current_setting(''app.restaurant_id'', true), '''')::uuid)'
            ' WITH CHECK (restaurant_id = nullif(current_setting(''app.restaurant_id'', true), '''')::uuid)',
            t);
    END LOOP;
END $$;

-- All orders work runs in the restaurant's own transaction, as the app role. Nothing is deleted:
-- money rows are kept (TBD-4), corrections are new rows (§7.2). The retention job (a platform
-- transaction) only finds which restaurants have notes to clear; it clears them in each
-- restaurant's transaction (§6).
GRANT SELECT, INSERT, UPDATE ON bills, orders, order_lines TO ${appRole};
GRANT SELECT, INSERT ON bill_adjustments TO ${appRole};
GRANT SELECT ON bills, orders, order_lines TO ${platformRole};
