-- FR-022 menu images (menu §6; TBD-13 decided 2026-11-07: Vercel Blob). An item's photo is two
-- public objects in the image store; menu_items.image_key (V5) holds their public URL without the
-- `-800.jpg` / `-320.jpg` ending. A replaced or removed photo, and a deleted item's, stays in the
-- store for 30 days, since guests' phones may still show a menu that names it; then the daily
-- retention job deletes its objects and its row here.

CREATE TABLE menu_image_retirements (
    restaurant_id  uuid        NOT NULL REFERENCES restaurants (id),
    image_key      text        NOT NULL,
    delete_after   timestamptz NOT NULL,
    PRIMARY KEY (restaurant_id, image_key)
);

-- The retention job finds the restaurants with images due.
CREATE INDEX menu_image_retirements_due_ix ON menu_image_retirements (delete_after);

ALTER TABLE menu_image_retirements ENABLE ROW LEVEL SECURITY;
ALTER TABLE menu_image_retirements FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON menu_image_retirements
    USING (restaurant_id = nullif(current_setting('app.restaurant_id', true), '')::uuid)
    WITH CHECK (restaurant_id = nullif(current_setting('app.restaurant_id', true), '')::uuid);

-- Menu work and each restaurant's retention batch run in the restaurant's own transaction, as the
-- app role. The job's platform transaction only finds the restaurants with images due.
GRANT SELECT, INSERT, DELETE ON menu_image_retirements TO ${appRole};
GRANT SELECT (restaurant_id, delete_after) ON menu_image_retirements TO ${platformRole};
