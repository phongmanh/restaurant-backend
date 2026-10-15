# Feature design: menu

| Field | Value |
|---|---|
| Version | 0.2 Draft: CR-004 approved and the seven §14 questions decided by the owner on 2026-10-15; the whole design is approved at G3 |
| Date | 2026-10-15 |
| Package | `menu` (architecture §4) |
| Requirements | FR-002 guest menu, FR-020 edit, FR-021 sold out, FR-022 images (Should; TBD-5 decided 2026-10-15), FR-023 options (Should), FR-024 vi/en (Should); NFR-PERF-01 (guest reads), FR-042 |
| Builds on | architecture.md v1.2 §1 (driver 4), §4, §7, §9, §10; [core-and-auth](core-and-auth.md) 0.4; [restaurants](restaurants.md) 0.2 |
| Platform facts checked 2026-10-15 | Vercel's CDN doesn't cache a request that carries an `Authorization` header ([CDN cache docs](https://vercel.com/docs/caching/cdn-cache), "Cacheable response criteria"). A function's request or response body is at most 4.5 MB ([function limits](https://vercel.com/docs/functions/limitations), "Request body size") |
| Change request | [CR-004](../cr/CR-004-menu-read-and-image-limits.md), approved 2026-10-15: architecture v1.1, requirements v1.3 |
| Owner decisions | §14, all seven accepted 2026-10-15 |

## 1. Scope
**In:**
- Categories, items, options, translations (vi/en), display order, hiding, deleting and sold-out.
- Item images, a Should: designed here, built only if release 1 has room for it.
- The guest menu read, the staff menu read and the admin menu read.
- The `MenuCatalog` port that `orders` uses to validate and price order lines.

**Out:**
- Order-time errors such as `item_unavailable`: the `orders` design defines them, from what `MenuCatalog` returns.
- How the `MenuChanged` event fans out to clients: `realtime` design.
- Time-based menus (lunch or dinner menus, happy hours): not in the requirements.

## 2. How the guest menu read works
**In plain words:** at 19:00, 5,000 guests open the menu. The architecture planned to serve most of those from Vercel's edge cache, so they never reach our server. But Vercel's CDN never caches a request that carries an `Authorization` header, and every guest call carries the session token (FR-002 requires it). So the planned edge caching can't work as written. This section chooses what to do instead; CR-004 changes the architecture text to match.

**Recommended (§14 Q1): private revalidation by menu version.**
- Every change to a restaurant's menu increases its **menu version**, a counter in `menu_state` (§3), in the same transaction as the change.
- `GET /v1/guest/menu?lang=vi` (session token) answers `200` with the full menu and `ETag: "<version>-<lang>"`, and `Cache-Control: private, no-cache`: the app may keep it, but must ask again each time it shows the menu.
- The app's next load sends `If-None-Match: "<version>-<lang>"`. If the version hasn't changed, the answer is `304 Not Modified` with no body. That costs one small query and a few hundred bytes.
- When the version has changed, the body is built from the database. Each instance keeps the last built menus in memory, keyed by `(restaurant, version, lang)`. A key can never go stale, because any change makes a new version. So the cache needs no invalidation, and it doesn't break the "no in-memory state" rule (architecture §4) in any way that matters. It's capped (e.g. 200 entries, LRU) and lost on cold start.
- **Why this is enough:**
  - Architecture §9 sizes menu reads at 70 req/s at peak with ≤ 15 ms of work each.
  - A warm request is p95 ≈ 49 ms at the edge (S-1).
  - Most loads after the first are 304s.
  - The guest-read target is p95 ≤ 200 ms (NFR-PERF-01).
  - Cold starts hurt this the same way they hurt every guest call (R-12). Caching wouldn't fix them either, because the session check needs the function anyway.

**Alternative: a signed, cacheable menu URL.**
- `GET /v1/guest/menu` returns only `{"version", "url"}`.
- The app then fetches the URL without the token. The URL is signed with an HMAC of `restaurant|version|lang`, so it can't be guessed, and the CDN can cache it for a long time.
- It saves the server from building a full menu for each first load. But it's still two requests per load, and anyone who once had a session can read that menu version without one. That breaks FR-002's "no session → 401" for the menu content, so it needs a requirements change too.
- Worth adding later if a load test shows full menu builds are a hot spot. It's an additive endpoint.

Images are separate: they're public objects in storage, fetched without a token, so the storage's CDN caches them (§6).

## 3. Data model
All tenant tables: `restaurant_id` on every row, forced RLS, composite indexes led by `restaurant_id` (ADR-0005).

```sql
CREATE TABLE menu_state (                                   -- one row per restaurant, created at onboarding
  restaurant_id  uuid PRIMARY KEY,
  version        bigint NOT NULL DEFAULT 0,                 -- +1 in the same transaction as every menu change
  updated_at     timestamptz NOT NULL
);

CREATE TABLE menu_categories (
  id             uuid PRIMARY KEY,
  restaurant_id  uuid NOT NULL,
  name_vi        text NOT NULL CHECK (char_length(name_vi) BETWEEN 1 AND 100),
  name_en        text CHECK (char_length(name_en) BETWEEN 1 AND 100),
  display_order  integer NOT NULL,
  active         boolean NOT NULL DEFAULT true,             -- false = hidden from guests
  deleted_at     timestamptz,
  version        integer NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL,
  updated_at     timestamptz NOT NULL
);
CREATE UNIQUE INDEX menu_categories_name_uq ON menu_categories (restaurant_id, lower(name_vi)) WHERE deleted_at IS NULL;

CREATE TABLE menu_items (
  id              uuid PRIMARY KEY,
  restaurant_id   uuid NOT NULL,
  category_id     uuid NOT NULL REFERENCES menu_categories (id),
  name_vi         text NOT NULL CHECK (char_length(name_vi) BETWEEN 1 AND 100),
  name_en         text CHECK (char_length(name_en) BETWEEN 1 AND 100),
  description_vi  text CHECK (char_length(description_vi) <= 500),
  description_en  text CHECK (char_length(description_en) <= 500),
  price           bigint NOT NULL CHECK (price BETWEEN 0 AND 100000000),   -- whole đồng, VAT included (TBD-6)
  display_order   integer NOT NULL,
  active          boolean NOT NULL DEFAULT true,
  sold_out        boolean NOT NULL DEFAULT false,
  image_key       text,                                     -- storage key prefix; NULL = no image (§6)
  deleted_at      timestamptz,                              -- soft delete: past order lines keep referring to it
  version         integer NOT NULL DEFAULT 0,               -- not bumped by the sold-out toggle (§5.3)
  created_at      timestamptz NOT NULL,
  updated_at      timestamptz NOT NULL
);
CREATE UNIQUE INDEX menu_items_name_uq ON menu_items (restaurant_id, category_id, lower(name_vi)) WHERE deleted_at IS NULL;
CREATE INDEX menu_items_category_ix ON menu_items (restaurant_id, category_id, display_order) WHERE deleted_at IS NULL;

CREATE TABLE menu_option_groups (                           -- FR-023
  id              uuid PRIMARY KEY,
  restaurant_id   uuid NOT NULL,
  item_id         uuid NOT NULL REFERENCES menu_items (id),
  name_vi         text NOT NULL CHECK (char_length(name_vi) BETWEEN 1 AND 100),
  name_en         text CHECK (char_length(name_en) BETWEEN 1 AND 100),
  min_choices     smallint NOT NULL CHECK (min_choices >= 0),   -- 0 = optional, ≥ 1 = required
  max_choices     smallint NOT NULL CHECK (max_choices >= 1),   -- 1 = single choice
  display_order   integer NOT NULL,
  deleted_at      timestamptz,
  CHECK (min_choices <= max_choices)
);

CREATE TABLE menu_options (
  id              uuid PRIMARY KEY,
  restaurant_id   uuid NOT NULL,
  group_id        uuid NOT NULL REFERENCES menu_option_groups (id),
  name_vi         text NOT NULL CHECK (char_length(name_vi) BETWEEN 1 AND 100),
  name_en         text CHECK (char_length(name_en) BETWEEN 1 AND 100),
  price_delta     bigint NOT NULL DEFAULT 0 CHECK (price_delta BETWEEN 0 AND 100000000),  -- §14 Q5
  display_order   integer NOT NULL,
  active          boolean NOT NULL DEFAULT true,
  deleted_at      timestamptz
);
CREATE INDEX menu_option_groups_item_ix ON menu_option_groups (restaurant_id, item_id) WHERE deleted_at IS NULL;
CREATE INDEX menu_options_group_ix ON menu_options (restaurant_id, group_id) WHERE deleted_at IS NULL;
```

- **Translations** are two columns per text (vi required, en optional). FR-024 names exactly these two languages; a third would mean a translations table and a CR.
- **Soft delete everywhere.** Order lines keep the item and option IDs, the names and the prices as a snapshot (FR-020; `orders` design). A deleted row is gone from every menu view, and its name is free for a new item.
- **Event counter:** `menu_state.version` is the menu's own counter, separate from the event log's per-restaurant sequence (`realtime`).
- **Limits** (ASSUMPTION-4, plus §14 Q4):
  - ≤ 50 categories and ≤ 1,000 items per restaurant (not counting deleted ones);
  - ≤ 10 option groups per item, ≤ 20 options per group, ≤ 5,000 options per restaurant.

  Past a limit → `409 menu_limit_reached`. The last limit keeps the largest menu body near 1.5 MB, well under the 4.5 MB response cap.

## 4. Reads
### 4.1 Guest: `GET /v1/guest/menu?lang=vi|en`
- Session token, `allowedWhenSuspended`: yes (reading is allowed; ordering isn't, FR-043).
- `lang` defaults to the first of the restaurant's languages. A language the restaurant doesn't offer → `vi`.
- Response: `200` with `ETag` and `Cache-Control: private, no-cache`, or `304`.

```json
{
  "version": 42,
  "lang": "en",
  "categories": [
    {
      "id": "0192...", "name": "Noodles",
      "items": [
        {
          "id": "0192...", "name": "Beef phở", "description": "Rare beef, herbs",
          "price": { "amount": "65000", "currency": "VND" },
          "sold_out": false,
          "image": { "url": "https://<store>/menu/<restaurant>/<item>/<id>-800.jpg",
                     "thumb_url": "https://<store>/menu/<restaurant>/<item>/<id>-320.jpg" },
          "option_groups": [
            { "id": "0192...", "name": "Size", "min_choices": 1, "max_choices": 1,
              "options": [ { "id": "0192...", "name": "Large", "price_delta": { "amount": "10000", "currency": "VND" } } ] }
          ]
        }
      ]
    }
  ]
}
```

- Only active, non-deleted categories and items, and active options, in `display_order`. A sold-out item **is** listed, with `sold_out: true` (FR-002).
- **Text falls back per field** (FR-024): `name_en` empty → `name_vi`. The response doesn't say which fields fell back; the app doesn't need to know.
- **`ETag` = `"<version>-<lang>"`.** The version check is one primary-key read on `menu_state`. If it matches, the answer is 304 without building anything.
- Another restaurant's menu can't be asked for: the restaurant comes from the session (FR-002, FR-042).

### 4.2 Staff: `GET /v1/staff/menu`
- All roles: kitchen and waiters need it for sold-out and for ordering on a guest's behalf.
- Everything that isn't deleted, hidden items included, in both languages, with each row's `version` for `If-Match`, the `sold_out` flags and the image URLs.
- `ETag: "<version>"` and `If-None-Match` → 304, the same way as the guest read. `Cache-Control: private, no-cache`.
- `allowedWhenSuspended`: no (restaurants §6: staff of a suspended restaurant read only reports, the audit log and the profile).

### 4.3 Admin: `GET /v1/admin/restaurants/{restaurantId}/menu`
The same body as the staff read, read only (authorisation matrix: admins read any menu; they don't edit).

## 5. Writes (manager unless noted)
### 5.1 Categories
| Operation | Request | Rules |
|---|---|---|
| Create | `POST /v1/staff/menu/categories` `{"name_vi", "name_en"?, "active"?}` | Goes last in the order. Duplicate `name_vi` (case-insensitive) → `409 duplicate_name` |
| Edit | `PATCH /v1/staff/menu/categories/{categoryId}` + `If-Match` | Name, `active` |
| Delete | `DELETE /v1/staff/menu/categories/{categoryId}` + `If-Match` | Only if it has no undeleted items → otherwise `409 category_not_empty` (§14 Q7) |
| Reorder | `PUT /v1/staff/menu/categories/order` `{"category_ids": [...]}` | Must list every undeleted category exactly once → otherwise `400 validation_failed`. Sets `display_order` 0…n−1 |

### 5.2 Items
| Operation | Request | Rules |
|---|---|---|
| Create | `POST /v1/staff/menu/items` `{"category_id", "name_vi", "name_en"?, "description_vi"?, "description_en"?, "price", "active"?, "option_groups"?}` | Goes last in its category. `price` is a Money object; non-VND or a fraction → 400. Duplicate name in the category → `409 duplicate_name` |
| Edit | `PATCH /v1/staff/menu/items/{itemId}` + `If-Match` | Any field. A new `category_id` moves the item to the end of that category (the name check runs there). `option_groups`, when sent, **replaces the whole set**: groups and options with an `id` are kept and updated, ones without are created, and missing ones are soft-deleted |
| Delete | `DELETE /v1/staff/menu/items/{itemId}` + `If-Match` | Soft delete (FR-020) |
| Reorder | `PUT /v1/staff/menu/categories/{categoryId}/items/order` `{"item_ids": [...]}` | Every undeleted item of the category exactly once |

**Every write, in one transaction** (sold-out included):
- `menu_state.version += 1`;
- audit;
- a `MenuChanged {version}` domain event and its outbox row, so other staff screens and, optionally, open guest apps can refresh (`realtime` design).

**Audit actions:**
- `menu.category.created`, `menu.category.updated`, `menu.category.deleted`;
- `menu.item.created`, `menu.item.updated` (changed field names; for `price`, from → to, NFR-SEC-03 "price edits"), `menu.item.deleted`;
- `menu.reordered`, `menu.item.sold_out_changed`, `menu.item.image_changed`.

### 5.3 Sold out: `PUT /v1/staff/menu/items/{itemId}/sold-out` `{"sold_out": true | false}`
- Kitchen or manager (matrix).
- **Idempotent and without `If-Match`** (§14 Q6). Kitchen staff press it in the middle of service, so it must never fail because a manager is editing the item at the same moment. Setting the current value again changes nothing and is harmless (FR-021).
- It doesn't bump the item's `version`, so a manager's open edit doesn't get a 412 because of it. It does bump the menu version, so guests see it on their next load (FR-021).
- Orders check `sold_out` again at order time (FR-003, through `MenuCatalog`).
- Answer `200` with `{"item_id", "sold_out"}`.

### 5.4 Validation
| Field | Rule (errors → `400 validation_failed` with per-field codes) |
|---|---|
| `name_vi` | 1–100 characters, trimmed; required |
| `name_en` | 1–100 characters or absent (empty string = remove) |
| `description_vi`, `description_en` | ≤ 500 characters |
| `price` | Money, VND, whole number 0 – 100,000,000 |
| `option_groups` | ≤ 10; each `min_choices` 0 ≤ min ≤ max, `max_choices` ≥ 1 and ≤ the group's number of options; ≤ 20 options each; `price_delta` VND 0 – 100,000,000 |
| `category_id` | A category of this restaurant that isn't deleted → otherwise `404 not_found` |

Waiter or kitchen on any write except sold-out → `403`. An ID of another restaurant → `404`. A stale `If-Match` → `412`.

## 6. Images (FR-022, Should)
**In plain words:** the manager picks a photo in the staff app. The app shrinks it in the browser before sending, the server checks that it's really an image, makes two clean copies (a large one and a thumbnail), and puts them in file storage. Guests' phones load the pictures straight from the storage's own CDN, so they never go through our server.

- **Upload:** `PUT /v1/staff/menu/items/{itemId}/image`, manager only. Raw body, `Content-Type: image/jpeg`, `image/png` or `image/webp`.
  - **At most 4 MB** on this route. Vercel caps a function's body at 4.5 MB, so FR-022's 5 MB can't reach the API. FR-022 says 4 MB since CR-004 (requirements v1.3).
  - The staff app resizes to ≤ 2,048 px on the long edge before upload, so real photos are usually well under 1 MB.
  - It's the only route whose body limit isn't 64 KB.
- **Checks, in order:**
  1. size ≤ 4 MB (`413 payload_too_large`);
  2. magic bytes match a JPEG, PNG or WebP, whatever the header or file name says (`415 unsupported_media_type`);
  3. width × height read from the header ≤ 40 megapixels, before decoding, so a "decompression bomb" can't exhaust memory (`400 image_invalid`);
  4. it decodes (`400 image_invalid`).
- **Processing:** decode (ImageIO, plus TwelveMonkeys for WebP), resize to 800 px and 320 px on the long edge, and re-encode as JPEG at quality 80.
  - Re-encoding drops all metadata, including EXIF location (privacy), and anything hidden in the file.
  - It takes about 0.2–0.5 s on 1 vCPU. That's fine for a manager's action, and at most 2 run at once per instance (a semaphore, like Argon2id).
- **Storage:** two objects under a generated key, `menu/<restaurant_id>/<item_id>/<uuid v7>-800.jpg` and `-320.jpg`.
  - They're public-read, immutable (`Cache-Control: public, max-age=31536000, immutable`), and served from the storage domain, never the API domain (architecture §12).
  - `menu_items.image_key` is set, the menu version is bumped, and the change is audited.
  - The previous image's objects are deleted by the daily retention job 30 days later, and a deleted item's objects too.
- **Remove:** `DELETE /v1/staff/menu/items/{itemId}/image` → `204`.
- **Storage down** → `503 image_storage_unavailable`. The menu still works and shows no new image (architecture §10). The upload takes no `Idempotency-Key`: a retry just uploads again, and the newest one wins.
- **Provider:** an `ImageStore` port. The adapter is chosen before FR-022 is built (**TBD-13, new**; §14 Q3). Candidates:
  - Vercel Blob: same platform, but there is no official JVM SDK; its REST API is what the JS SDK uses.
  - An S3-compatible store such as Cloudflare R2: official S3 API through the AWS SDK for Kotlin, and no egress fees. Data residency would be subject to TBD-10, though menu images are public data.

## 7. `MenuCatalog` (for `orders`)
`MenuCatalog.price(restaurantId, lines)` runs inside the order's transaction (a menu adapter on the same `Transactor`). It reads the current rows, so a sold-out or hidden item is caught at order time, not from a cache.
- **Input per line:** `item_id`, `quantity`, `option_ids`.
- **Output:** for each line, the snapshot to store with the order:
  - `item_id`, `name_vi`, `name_en`, unit `price`;
  - each chosen option with its group name, option name and `price_delta`;
  - `unit_total` = price + sum of the deltas.
- **Errors, all collected rather than stopping at the first:**
  - unknown or deleted item → `unknown_item`;
  - hidden or sold-out item → `item_unavailable` with the item IDs (FR-003: nothing is ordered);
  - option not of that item, inactive or deleted → `unknown_option`;
  - group below `min_choices` → `option_required`; above `max_choices` → `too_many_options`.

  `orders` turns them into its response codes.
- **Performance:** one query per table with `id = ANY(?)`, at most 50 lines (ASSUMPTION-4).

## 8. Endpoints
All of these go into `core/http/Endpoints.kt` and the contract.

| Method and path | Who | If-Match | Allowed when suspended | Success |
|---|---|---|---|---|
| `GET /v1/guest/menu` | Guest session | — | Yes | 200 + ETag, or 304 |
| `GET /v1/staff/menu` | Staff, all roles | — | No | 200 + ETag, or 304 |
| `POST /v1/staff/menu/categories` | Manager | — | No | 201 |
| `PATCH /v1/staff/menu/categories/{categoryId}` | Manager | Yes | No | 200 |
| `DELETE /v1/staff/menu/categories/{categoryId}` | Manager | Yes | No | 204 |
| `PUT /v1/staff/menu/categories/order` | Manager | — | No | 204 |
| `POST /v1/staff/menu/items` | Manager | — | No | 201 |
| `GET /v1/staff/menu/items/{itemId}` | Staff, all roles | — | No | 200 + ETag |
| `PATCH /v1/staff/menu/items/{itemId}` | Manager | Yes | No | 200 |
| `DELETE /v1/staff/menu/items/{itemId}` | Manager | Yes | No | 204 |
| `PUT /v1/staff/menu/categories/{categoryId}/items/order` | Manager | — | No | 204 |
| `PUT /v1/staff/menu/items/{itemId}/sold-out` | Kitchen, manager | — | No | 200 |
| `PUT /v1/staff/menu/items/{itemId}/image` | Manager | — | No | 200 (FR-022, Should) |
| `DELETE /v1/staff/menu/items/{itemId}/image` | Manager | — | No | 204 (FR-022, Should) |
| `GET /v1/admin/restaurants/{restaurantId}/menu` | Admin | — | n/a | 200 |

A single category has no `GET`: the staff menu read returns every category with its `version`.

**New error codes:**

| Status | `code` | When |
|---|---|---|
| 400 | `image_invalid` | Not decodable, or over 40 megapixels |
| 409 | `duplicate_name` | A category or item name already used in the same scope |
| 409 | `category_not_empty` | Deleting a category that still has items |
| 409 | `menu_limit_reached` | A §3 limit would be passed |
| 503 | `image_storage_unavailable` | Storage unreachable or timed out (5 s) |

## 9. Code layout
```
<base>.menu
  domain/        Category, MenuItem, OptionGroup, Option, MenuLimits, OptionSelectionRules
  application/   MenuEditService, SoldOutService, MenuReadService (built-menu cache by version), ImageService;
                 MenuCatalog (port provided to orders), ImageStore (port; adapter per TBD-13)
  persistence/   MenuRepository (every method takes RestaurantId), MenuStateRepository
  http/          GuestMenuRoutes, StaffMenuRoutes, AdminMenuRoutes (paths from Endpoints.kt)
  image/         ImageProcessor (magic bytes, pixel cap, resize, JPEG re-encode)
```

## 10. Tests
| ID | Case | Req |
|---|---|---|
| IT-MENU-01 | Guest read: only active items and categories in display order; sold-out items listed with `sold_out: true`; prices as Money | FR-002 |
| IT-MENU-02 | Guest read without or with an expired session → 401; the menu is always the session's restaurant | FR-002, FR-042 |
| IT-MENU-03 | `If-None-Match` with the current version → 304 without a body; after any write (including sold-out) → 200 with a new ETag | FR-002, FR-021, NFR-PERF-01 |
| IT-MENU-04 | `lang=en`: missing `name_en` falls back to `name_vi` field by field; a language the restaurant doesn't offer → vi | FR-024 |
| IT-MENU-05 | Item create and edit: limits on name, description and price → 400 per field; duplicate name in the category → 409; same name in another category → OK | FR-020 |
| IT-MENU-06 | Stale `If-Match` → 412; missing → 428 | FR-020 |
| IT-MENU-07 | Waiter or kitchen editing → 403; kitchen toggling sold-out → 200; waiter toggling sold-out → 403 | FR-020, FR-021 |
| IT-MENU-08 | Item and category IDs of restaurant B on every menu route → 404 and B unchanged | FR-042, NFR-SEC-02 |
| IT-MENU-09 | Sold-out toggled twice → same state, menu version bumped once per real change; a manager's edit with the ETag read before the toggle still succeeds | FR-021 |
| IT-MENU-10 | Deleting an ordered item: hidden from both menu reads, the order line still has its snapshot (with `orders`) | FR-020 |
| IT-MENU-11 | Category with items → 409 `category_not_empty`; reorder with a missing or duplicate ID → 400 | FR-020 |
| IT-MENU-12 | Limits: 51st category, 1,001st item, 11th option group → 409 `menu_limit_reached` | FR-020, FR-023 |
| IT-MENU-13 | `MenuCatalog`: prices from the database, not the client; sold-out, hidden, unknown item and option errors all collected; required group missing → `option_required`; two choices in a single-choice group → `too_many_options` | FR-003, FR-023 |
| IT-MENU-14 | Image: a PNG renamed `.jpg` is accepted by content; a PDF → 415; 4 MB + 1 byte → 413; a 20,000 × 20,000 header → 400 without decoding; output is JPEG without EXIF; objects are public on the storage domain | FR-022 |
| IT-MENU-15 | Every menu write writes its audit entry and a `MenuChanged` event in the same transaction | NFR-SEC-03 |

## 11. Traceability
The RTM's design column for FR-002 and FR-020 to FR-024 points here; FR-042 and NFR-PERF-01 add this design.

## 12. Changes this design makes elsewhere
- **core-and-auth §2:** `Cache-Control: no-store` applies to every guest and staff response **except** the two menu reads, which send `private, no-cache` with an `ETag`.
- **core-and-auth §2:** the 64 KB body limit has one exception, the image upload route (4 MB).
- **restaurants §5 (onboarding):** also creates the restaurant's `menu_state` row through a `MenuSetup` port. That keeps menu out of the restaurants tables and restaurants out of the menu tables.

## 13. Risks
No new risk. Cold starts on a guest's first menu load (5–7 s on the free plan) can't be hidden by a cache, because the session check needs the function. That's R-12, and the paid-plan check before G5 covers this route too.

## 14. Owner decisions (2026-10-15)
The owner approved CR-004 and accepted all seven recommendations. They're recorded in STATUS.md (Decisions; Q-4 resolved; ASSUMPTION-13 confirmed; TBD-13 stays open until FR-022 is built).

1. The guest menu read stays behind the session, revalidated by menu version (`304` when unchanged), with a per-instance cache by version; no CDN cache (§2; CR-004).
2. Images ≤ 4 MB through the API, shrunk by the staff app first (§6; CR-004, FR-022 in requirements v1.3).
3. TBD-13, the image store provider, is decided before FR-022 is built; it doesn't block G3.
4. ASSUMPTION-13: ≤ 10 option groups per item, ≤ 20 options per group, ≤ 5,000 options per restaurant (§3).
5. Option prices only add (`price_delta` ≥ 0).
6. Sold-out takes no `If-Match` and never conflicts with a manager's edit (§5.3).

   The other menu writes without `If-Match`, by design: the two reorder calls replace a whole list and must name every member exactly once, so a stale list is refused with `400`; the image upload and delete are last-writer-wins (§6). Core-and-auth §5's `If-Match` rule covers edits of a versioned row's fields, which these aren't (noted at the G3 consistency pass).
7. Deleting a category that still has items → `409 category_not_empty` (§5.1).
