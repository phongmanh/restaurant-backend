-- The menu feature (menu §3). Tenant tables, RLS forced (ADR-0005).
--
-- One change to §3's DDL: each reference to a parent row also names the restaurant, as a composite
-- foreign key on (restaurant_id, id). The services already look parents up by restaurant; with these
-- the database itself refuses an item filed under another restaurant's category, since foreign-key
-- checks don't go through RLS.

CREATE TABLE menu_state (                                    -- one row per restaurant, made at onboarding
    restaurant_id  uuid PRIMARY KEY REFERENCES restaurants (id),
    version        bigint NOT NULL DEFAULT 0,                -- +1 in the same transaction as every menu change
    updated_at     timestamptz NOT NULL
);

CREATE TABLE menu_categories (
    id             uuid PRIMARY KEY,
    restaurant_id  uuid NOT NULL,
    name_vi        text NOT NULL CHECK (char_length(name_vi) BETWEEN 1 AND 100),
    name_en        text CHECK (char_length(name_en) BETWEEN 1 AND 100),
    display_order  integer NOT NULL,
    active         boolean NOT NULL DEFAULT true,            -- false = hidden from guests
    deleted_at     timestamptz,
    version        integer NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    UNIQUE (restaurant_id, id)
);
CREATE UNIQUE INDEX menu_categories_name_uq ON menu_categories (restaurant_id, lower(name_vi)) WHERE deleted_at IS NULL;

CREATE TABLE menu_items (
    id              uuid PRIMARY KEY,
    restaurant_id   uuid NOT NULL,
    category_id     uuid NOT NULL,
    name_vi         text NOT NULL CHECK (char_length(name_vi) BETWEEN 1 AND 100),
    name_en         text CHECK (char_length(name_en) BETWEEN 1 AND 100),
    description_vi  text CHECK (char_length(description_vi) <= 500),
    description_en  text CHECK (char_length(description_en) <= 500),
    price           bigint NOT NULL CHECK (price BETWEEN 0 AND 100000000),   -- whole đồng, VAT included (TBD-6)
    display_order   integer NOT NULL,
    active          boolean NOT NULL DEFAULT true,
    sold_out        boolean NOT NULL DEFAULT false,
    image_key       text,                                    -- storage key prefix; NULL = no image (§6)
    deleted_at      timestamptz,                             -- soft delete: past order lines keep referring to it
    version         integer NOT NULL DEFAULT 0,              -- not bumped by the sold-out toggle (§5.3)
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    UNIQUE (restaurant_id, id),
    FOREIGN KEY (restaurant_id, category_id) REFERENCES menu_categories (restaurant_id, id)
);
CREATE UNIQUE INDEX menu_items_name_uq ON menu_items (restaurant_id, category_id, lower(name_vi)) WHERE deleted_at IS NULL;
CREATE INDEX menu_items_category_ix ON menu_items (restaurant_id, category_id, display_order) WHERE deleted_at IS NULL;

CREATE TABLE menu_option_groups (                            -- FR-023
    id              uuid PRIMARY KEY,
    restaurant_id   uuid NOT NULL,
    item_id         uuid NOT NULL,
    name_vi         text NOT NULL CHECK (char_length(name_vi) BETWEEN 1 AND 100),
    name_en         text CHECK (char_length(name_en) BETWEEN 1 AND 100),
    min_choices     smallint NOT NULL CHECK (min_choices >= 0),   -- 0 = optional, ≥ 1 = required
    max_choices     smallint NOT NULL CHECK (max_choices >= 1),   -- 1 = single choice
    display_order   integer NOT NULL,
    deleted_at      timestamptz,
    CHECK (min_choices <= max_choices),
    UNIQUE (restaurant_id, id),
    FOREIGN KEY (restaurant_id, item_id) REFERENCES menu_items (restaurant_id, id)
);

CREATE TABLE menu_options (
    id              uuid PRIMARY KEY,
    restaurant_id   uuid NOT NULL,
    group_id        uuid NOT NULL,
    name_vi         text NOT NULL CHECK (char_length(name_vi) BETWEEN 1 AND 100),
    name_en         text CHECK (char_length(name_en) BETWEEN 1 AND 100),
    price_delta     bigint NOT NULL DEFAULT 0 CHECK (price_delta BETWEEN 0 AND 100000000),  -- §14 Q5
    display_order   integer NOT NULL,
    active          boolean NOT NULL DEFAULT true,
    deleted_at      timestamptz,
    FOREIGN KEY (restaurant_id, group_id) REFERENCES menu_option_groups (restaurant_id, id)
);
CREATE INDEX menu_option_groups_item_ix ON menu_option_groups (restaurant_id, item_id) WHERE deleted_at IS NULL;
CREATE INDEX menu_options_group_ix ON menu_options (restaurant_id, group_id) WHERE deleted_at IS NULL;

-- Restaurants onboarded before this migration get their row now; later ones get it at onboarding
-- (§12, the MenuSetup port). Before RLS is forced, which would hold the migrator to it too.
INSERT INTO menu_state (restaurant_id, version, updated_at) SELECT id, 0, now() FROM restaurants;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['menu_state', 'menu_categories', 'menu_items', 'menu_option_groups', 'menu_options'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I'
            ' USING (restaurant_id = nullif(current_setting(''app.restaurant_id'', true), '''')::uuid)'
            ' WITH CHECK (restaurant_id = nullif(current_setting(''app.restaurant_id'', true), '''')::uuid)',
            t);
    END LOOP;
END $$;

-- Menu work runs in the restaurant's own transaction, as the app role. Nothing is ever deleted:
-- rows are soft-deleted, because order lines keep referring to them (§3). Onboarding, a platform
-- transaction, makes the restaurant's menu_state row.
GRANT SELECT, UPDATE ON menu_state TO ${appRole};
GRANT SELECT, INSERT ON menu_state TO ${platformRole};
GRANT SELECT, INSERT, UPDATE ON menu_categories, menu_items, menu_option_groups, menu_options TO ${appRole};
GRANT SELECT ON menu_categories, menu_items, menu_option_groups, menu_options TO ${platformRole};
