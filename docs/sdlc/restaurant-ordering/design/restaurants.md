# Feature design: restaurants

| Field | Value |
|---|---|
| Version | 0.2 Draft: the eight §15 questions decided by the owner on 2026-10-15; the whole design is approved at G3 |
| Date | 2026-10-15 |
| Package | `restaurants` (architecture §4) |
| Requirements | FR-040 onboard, FR-041 staff, FR-043 suspend, FR-044 profile; FR-042 and NFR-SEC-02 (isolation of these endpoints); NFR-SEC-03 (audit read); NFR-PRIV-01 (staff data export and erasure) |
| Builds on | architecture.md v1.2 §4, §7 (authorisation matrix), §11; ADR-0005, ADR-0011; [core-and-auth](core-and-auth.md) 0.2 (conventions, `staff_accounts`, links, audit) |
| Contract | `docs/api/openapi.yaml`: `/v1/staff/restaurant`, `/v1/staff/members`, `/v1/staff/audit-events`, `/v1/admin/*` |
| Owner decisions | §15, all eight recommendations accepted 2026-10-15 |

## 1. Scope
**In:**
- The restaurant (tenant): onboarding, profile, suspension and reactivation.
- Staff management by managers, and by platform admins for any restaurant.
- Platform-admin accounts: invite, deactivate, password reset, TOTP reset.
- Reading the audit log.
- Exporting and erasing a staff member's personal data on request.
- The `TenantStatus` and `RestaurantDirectory` ports that other features call.

**Out:**
- Deleting a restaurant (offboarding). Release 1 has suspension only. Deleting a tenant and its data after 90 days (requirements §5) is a later change.
- Waiter confirmation (FR-016): its setting is added in the `orders` design if TBD-8 puts it in release 1.
- How suspension affects guests: the `tables` and `orders` designs call `TenantStatus` (§6).
- Daily-sales reports (`reports` design).

## 2. Data model
`staff_accounts`, the link tokens and `audit_events` are defined in core-and-auth §9 and §12. This feature adds one table.

```sql
-- restaurants: the tenant registry. Global table without RLS (§15 Q2), like staff_accounts
CREATE TABLE restaurants (
  id                   uuid PRIMARY KEY,                    -- UUID v7; the tenant ID everywhere
  name                 text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
  address              text NOT NULL CHECK (char_length(address) BETWEEN 1 AND 300),
  time_zone            text NOT NULL DEFAULT 'Asia/Ho_Chi_Minh', -- IANA ID, checked by the service
  currency             text NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
  languages            text[] NOT NULL DEFAULT '{vi}',      -- 'vi' always included (FR-024 fallback)
  opening_hours        jsonb NOT NULL DEFAULT '{}',         -- §4.3
  owner_contact_name   text,                                -- personal data (requirements §5); admins only
  owner_contact_email  text,
  owner_contact_phone  text,
  status               text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended')),
  suspended_at         timestamptz,
  suspension_reason    text CHECK (char_length(suspension_reason) <= 500), -- admins only
  created_at           timestamptz NOT NULL,
  updated_at           timestamptz NOT NULL,
  version              integer NOT NULL DEFAULT 0,
  CHECK ('vi' = ANY (languages)),
  CHECK ((status = 'suspended') = (suspended_at IS NOT NULL))
);
CREATE INDEX restaurants_status_ix ON restaurants (status, name);
```

**What changes from architecture §4 and §10** (finalising table names, which §4 leaves to Phase 3):
- `staff_members` doesn't exist: the accounts are `staff_accounts` (core-and-auth §16 Q1). This feature manages them through the `StaffAccounts` port that `core` provides.
- The per-restaurant event counter (`event_seq`, architecture §10) moves out of `restaurants` into a table that `realtime` owns. Otherwise `realtime` would update a table of another feature, which architecture §4 forbids. The mechanism stays the same (`UPDATE ... SET last_seq = last_seq + 1 RETURNING`); the `realtime` design defines the table.

## 3. Who may do what
The authorisation matrix (architecture §7), mapped to this feature's operations. "Own" means the caller's restaurant; for staff it always comes from the `Caller`, never from the path.

| Operation | Kitchen | Waiter | Manager | Platform admin |
|---|---|---|---|---|
| Read restaurant profile | Own | Own | Own | Any |
| Edit profile (name, address, time zone, hours, languages) | — | — | Own | Any |
| Edit owner contact | — | — | — | Any |
| Onboard / suspend / reactivate a restaurant | — | — | — | Yes |
| List / read staff | — | — | Own | Any restaurant |
| Invite staff (waiter, kitchen, manager) | — | — | Own | Any restaurant |
| Change role, name or phone; deactivate; reactivate | — | — | Own, with the last-manager rule | Any restaurant; the last-manager rule doesn't apply |
| Re-issue an invitation link | — | — | Own | Any restaurant |
| Issue a password-reset link | — | — | Own waiters and kitchen | Any restaurant's managers, waiters and kitchen; other admins |
| Platform admins: list, invite, deactivate, reset TOTP | — | — | — | Yes, not on themselves |
| Read the audit log | — | — | Own restaurant, without IP and user agent (§15 Q7) | Any restaurant, and the platform chain |
| Export / erase a staff member's personal data | — | — | — | Yes |

A role without the operation → `403 forbidden`. Another restaurant's staff ID → `404 not_found`.

Admin routes and a token of the wrong group (§15 Q1): a valid **staff** token on `/v1/admin/*` → `404 not_found`, as FR-040 asks. A missing or invalid token → 401, as on every group.

## 4. Restaurant profile
### 4.1 Read
`GET /v1/staff/restaurant` (all roles) → `200` with `ETag`:

```json
{
  "id": "0192...", "name": "Phở Hà Nội", "address": "12 Lý Thường Kiệt, Hoàn Kiếm, Hà Nội",
  "time_zone": "Asia/Ho_Chi_Minh", "currency": "VND", "languages": ["vi", "en"],
  "opening_hours": { "mon": [{ "opens": "10:00", "closes": "14:00" }, { "opens": "17:00", "closes": "23:30" }] },
  "status": "active", "version": 4
}
```

The admin view (`GET /v1/admin/restaurants/{restaurantId}`) adds `owner_contact`, `suspended_at`, `suspension_reason` and `created_at`.

### 4.2 Edit
`PATCH /v1/staff/restaurant` (manager) and `PATCH /v1/admin/restaurants/{restaurantId}` (admin) take `If-Match`. The body contains only the fields to change; `owner_contact` is accepted on the admin route only.
- **Validation:**
  - `name` 1–100 characters; `address` 1–300 characters;
  - `time_zone` must be an IANA region ID that the JDK knows (`ZoneId.of`, region IDs only, so `UTC+7` → 400);
  - `languages` ⊆ {`vi`, `en`}, with `vi` included;
  - `opening_hours` per §4.3.

  Failures → `400 validation_failed` with per-field errors.
- Concurrent edit → 412; missing `If-Match` → 428 (core-and-auth §5).
- Audit `restaurant.profile_updated`, listing the changed field names (not their values).
- **A time zone change** applies from the next request. Which local day an earlier bill counts on is up to the `reports` design: the S-5 design point fixed the local day at the time the bill was closed.

### 4.3 Opening hours
- A week of days `mon` … `sun`. Each day is a list of at most 3 intervals `{"opens": "HH:MM", "closes": "HH:MM"}` in the restaurant's local time.
- `closes` ≤ `opens` means the interval ends after midnight (e.g. `18:00`–`02:00`).
- Intervals of one day may not overlap; a missing or empty day means closed.
- **Display only in release 1** (§15 Q5): nothing is blocked outside opening hours. Guests order when a session is open, and staff decide when that is.

## 5. Onboarding (FR-040)
`POST /v1/admin/restaurants`, platform admin, **`Idempotency-Key` required**.

```json
{
  "name": "Phở Hà Nội", "address": "…", "time_zone": "Asia/Ho_Chi_Minh", "languages": ["vi", "en"],
  "owner_contact": { "name": "…", "email": "…", "phone": "+84…" },
  "first_manager": { "email": "manager@example.com", "display_name": "Lan" }
}
```

In one platform transaction (core-and-auth §7):
1. Insert the restaurant. `time_zone` defaults to `Asia/Ho_Chi_Minh` and `languages` to `["vi"]`.
2. Create the manager's account (`status = invited`, `role = manager`) through `StaffAccounts`. An email that already has an account → `409 email_in_use` (§15 Q8), and nothing is created.
3. Issue an invitation link (72 h, core-and-auth §8.5).
4. Audit `restaurant.onboarded` and `staff.invited` in the **new restaurant's** chain, so its managers can see how it was set up.

→ `201`, `Location: /v1/admin/restaurants/{id}`, body `{"restaurant": {...}, "first_manager": {...}, "invitation": {"link": "https://<staff app>/invite#<token>", "expires_at": "..."}}`.

**The link isn't stored for replays.** A retry with the same key gets the restaurant back, but core-and-auth §4 never stores one-time secrets. So the replay **issues a fresh invitation link**, which replaces the first one. The admin never saw the first answer, so nothing usable is lost. This is the "one-time secrets in idempotent responses" rule added to core-and-auth §4.

**The first platform admin** can't be invited through the API, because there's nobody to invite them. A one-off operator command (Phase 4, like the last-admin TOTP reset in architecture §7) creates an invited admin and prints the link once.

## 6. Suspension (FR-043)
`POST /v1/admin/restaurants/{restaurantId}/suspend` `{"reason"}` and `POST .../reactivate`.
- Both are naturally idempotent: suspending a suspended restaurant changes nothing and returns `200` with the current state. They take no `If-Match`.
- Audit `restaurant.suspended` (with the reason) and `restaurant.reactivated`, in the restaurant's chain.
- No data is deleted, and no session or token is revoked: reactivation simply restores everything.

**What a suspended restaurant can still do** (§15 Q4):

| Who | Allowed | Everything else |
|---|---|---|
| Staff | Sign in, refresh, sign out, `GET /v1/auth/me`, `GET /v1/staff/restaurant`, and for managers reading reports and the audit log | `403 restaurant_suspended` |
| Guests | Read their session's orders and bill | Opening or joining a session, ordering, calling a waiter → `403 restaurant_suspended` (`tables` and `orders` designs) |
| Live updates | — | No new subscription tokens; existing subscriptions run out with their token |

**How it's enforced.** Each entry in the endpoint registry (`core/http/Endpoints.kt`, core-and-auth §16) carries the flag `allowedWhenSuspended`. The staff and guest authentication providers read the restaurant's status in the same query that resolves the caller (core-and-auth §6, extended by a join to `restaurants`), and refuse endpoints without the flag. The check is central, so a new endpoint can't forget it, and it costs no extra query.

**Change to core-and-auth §8.1:** a suspended restaurant's staff **can** sign in (FR-043 says so). The sign-in step that answered `403 restaurant_suspended` is removed. The restriction is per endpoint, as above.

**Ports for other features:**
- `TenantStatus.of(restaurantId)` returns `active` or `suspended`. Guest paths use it where the caller query doesn't already have it.
- `RestaurantDirectory.profile(restaurantId)` returns the name, time zone and languages, for menus, guest views and reports.

## 7. Staff management (FR-041)
Manager routes are under `/v1/staff/members`. Admins have the same operations under `/v1/admin/restaurants/{restaurantId}/members` (§3).

| Operation | Request | Result |
|---|---|---|
| List | `GET /v1/staff/members?status=&role=&limit=&cursor=` | Page of members sorted by `display_name`, then `id`. Anonymised accounts aren't listed |
| Read | `GET /v1/staff/members/{staffId}` | `200` + `ETag` |
| Invite | `POST /v1/staff/members` `{"email", "display_name", "phone"?, "role"}` | `201` `{"member", "invitation": {"link", "expires_at"}}` |
| Edit | `PATCH /v1/staff/members/{staffId}` + `If-Match` `{"display_name"?, "phone"?, "role"?}` | `200` + new `ETag` |
| Deactivate | `POST /v1/staff/members/{staffId}/deactivate` | `200` member |
| Reactivate | `POST /v1/staff/members/{staffId}/reactivate` | `200` member |
| Re-issue invitation | `POST /v1/staff/members/{staffId}/invitation` | `201` `{"link", "expires_at"}`; the old link stops working |
| Issue password reset | `POST /v1/staff/members/{staffId}/password-reset` | `201` `{"link", "expires_at"}` (24 h) |

**A member** looks like `{"id", "email", "display_name", "phone"?, "role", "status": "invited" | "active" | "deactivated", "created_at", "deactivated_at"?, "version"}`.

**Rules:**
- **Validation:**
  - `email` is a valid address of at most 254 characters, stored lower-cased;
  - `display_name` 1–100 characters;
  - `phone` E.164, at most 16 characters;
  - `role` is `manager`, `waiter` or `kitchen`.
- **Email in use:** an email that already has an account anywhere → `409 email_in_use` (ASSUMPTION-3: one restaurant per person; §15 Q8). The email can't be changed later: to change it, deactivate the account and invite again.
- **Last manager:**
  - A manager can't demote or deactivate the **last active manager** of the restaurant, themselves included → `409 last_manager`.
  - Changes to staff of one restaurant are serialised with `pg_advisory_xact_lock` on the restaurant ID, so two managers demoting each other at the same moment can't leave the restaurant with none.
  - Platform admins aren't bound by the rule (a support case, e.g. the only manager has left); it's audited.
- **Effects on sign-in:** a role change and a deactivation revoke all the member's sessions (core-and-auth §8.3), so the next call is refused or sees the new role.
- **Reactivation** restores an account that hasn't been anonymised yet (30 days after deactivation). The member signs in with their old password. Reactivating an `active` member is a no-op, and an anonymised one → `404`.
- **Invitation re-issue** is only for `invited` members, and a password reset only for `active` ones → otherwise `409 member_state_conflict`.
- **Who issues a reset link** (architecture §7): a manager for waiters and kitchen; a platform admin for managers (§15 Q3). A manager asking for another manager's reset → `403 forbidden`.
- **Links** are returned once and never stored in plain text (core-and-auth §8.5). These operations don't take an `Idempotency-Key`. A manager whose response was lost re-issues the link, which voids the earlier one.
- **Limits** (§15 Q6, ASSUMPTION-12): at most 100 `invited` + `active` accounts per restaurant (`409 member_limit_reached`); at most 30 invitation and reset links per restaurant per hour (`429`).
- **Audit:** `staff.invited`, `staff.invitation_reissued`, `staff.updated` (changed field names; `role` with from → to), `staff.deactivated`, `staff.reactivated`, `staff.password_reset_issued`. The target is the member.

Editing a member doesn't let a manager set or see a password, a TOTP secret or a link token, and the list never shows them.

## 8. Platform admins
- **`GET /v1/admin/admins`:** list the platform admins.
- **`POST /v1/admin/admins`** `{"email", "display_name"}` → `201` with an invitation link. The new admin enrols TOTP when accepting it (core-and-auth §8.5).
- **`POST /v1/admin/admins/{staffId}/deactivate`.** An admin can't deactivate themselves (`409 self_action_not_allowed`). Deactivating the last active admin → `409 last_admin`. Changes are serialised with an advisory lock on a fixed key.
- **`POST /v1/admin/admins/{staffId}/password-reset`** → `201` link; another admin only.
- **`POST /v1/admin/admins/{staffId}/totp-reset`** (another admin only) handles a lost authenticator:
  - revoke the admin's sessions, clear the password hash and the TOTP secret, set `status = invited`, and issue a fresh invitation link (`201`);
  - the admin then sets a password and enrols TOTP through the normal invitation flow;
  - audit `admin.totp_reset`;
  - the last admin's own lost authenticator is the operator command in architecture §7.

Audit: `admin.invited`, `admin.deactivated`, `admin.password_reset_issued`, `admin.totp_reset`, all in the platform chain.

## 9. Audit log read
- **`GET /v1/staff/audit-events`** (manager, own restaurant) and **`GET /v1/admin/audit-events?restaurant_id=`** (admin; `restaurant_id=platform` for the platform chain).
- Filters: `from`, `to` (default the last 7 days, at most 92 days per query), `action` (a prefix, e.g. `staff.`), `actor_id`, `target_id`; cursor paging newest first.
- **An entry:** `{"id", "occurred_at", "action", "actor": {"type", "id", "display_name"}, "target": {"type", "id"}?, "request_id", "details"}`. The admin view adds `ip` and `user_agent` (§15 Q7).
  - `display_name` comes from `staff_accounts`. It shows "Deleted user" once the account is anonymised; the ID stays.
- **Indexes:** `audit_events (restaurant_id, occurred_at DESC, id DESC)`, which the cursor follows.
- Reading the audit log isn't itself audited for managers, but it is for admins (`admin.audit_read`, with the restaurant), because admins read across tenants.

## 10. Staff data export and erasure (NFR-PRIV-01)
Platform admins handle requests from staff members, because the request goes to the operator, not to the restaurant.
- **`GET /v1/admin/staff-accounts/{staffId}/export`** → `200` JSON with:
  - the account's personal fields (no password hash, TOTP secret or token hashes);
  - its role history from the audit log;
  - the audit entries where it is the actor.

  Audit `staff.data_exported`.
- **`POST /v1/admin/staff-accounts/{staffId}/erase`:**
  - deactivates the account (revoking its sessions) and anonymises it at once, the same way as the daily retention job (core-and-auth §9);
  - the audit log keeps the pseudonymous ID (requirements §5);
  - audit `staff.erased`;
  - erasing the last active manager of an active restaurant is allowed, and the response warns `"last_manager_removed": true`, so the admin can invite a new one.
- The legal deadline for answering a request is part of TBD-4 and the legal review.

## 11. Endpoints
All of these go into `core/http/Endpoints.kt` and the contract.

| Method and path | Who | Idempotency / If-Match | Allowed when suspended | Success |
|---|---|---|---|---|
| `GET /v1/staff/restaurant` | Staff, all roles | — | Yes | 200 + ETag |
| `PATCH /v1/staff/restaurant` | Manager | If-Match | No | 200 + ETag |
| `GET /v1/staff/members` | Manager | — | No | 200 page |
| `POST /v1/staff/members` | Manager | — | No | 201 |
| `GET /v1/staff/members/{staffId}` | Manager | — | No | 200 + ETag |
| `PATCH /v1/staff/members/{staffId}` | Manager | If-Match | No | 200 + ETag |
| `POST /v1/staff/members/{staffId}/deactivate` | Manager | — | No | 200 |
| `POST /v1/staff/members/{staffId}/reactivate` | Manager | — | No | 200 |
| `POST /v1/staff/members/{staffId}/invitation` | Manager | — | No | 201 |
| `POST /v1/staff/members/{staffId}/password-reset` | Manager | — | No | 201 |
| `GET /v1/staff/audit-events` | Manager | — | Yes | 200 page |
| `GET /v1/admin/restaurants` | Admin | — | n/a | 200 page (`?status=`, `?q=` name prefix) |
| `POST /v1/admin/restaurants` | Admin | Idempotency-Key | n/a | 201 |
| `GET /v1/admin/restaurants/{restaurantId}` | Admin | — | n/a | 200 + ETag |
| `PATCH /v1/admin/restaurants/{restaurantId}` | Admin | If-Match | n/a | 200 + ETag |
| `POST /v1/admin/restaurants/{restaurantId}/suspend` | Admin | — | n/a | 200 |
| `POST /v1/admin/restaurants/{restaurantId}/reactivate` | Admin | — | n/a | 200 |
| `GET`, `POST /v1/admin/restaurants/{restaurantId}/members` and the six member operations below it | Admin | as the staff routes | n/a | as the staff routes |
| `GET`, `POST /v1/admin/admins` | Admin | — | n/a | 200 page / 201 |
| `POST /v1/admin/admins/{staffId}/deactivate`, `/password-reset`, `/totp-reset` | Admin | — | n/a | 200 / 201 / 201 |
| `GET /v1/admin/audit-events` | Admin | — | n/a | 200 page |
| `GET /v1/admin/staff-accounts/{staffId}/export` | Admin | — | n/a | 200 |
| `POST /v1/admin/staff-accounts/{staffId}/erase` | Admin | — | n/a | 200 |

**New error codes** for the catalogue:

| Status | `code` | When |
|---|---|---|
| 409 | `email_in_use` | An account with that email exists |
| 409 | `last_manager` | The change would leave the restaurant without an active manager |
| 409 | `last_admin` | The change would leave no active platform admin |
| 409 | `self_action_not_allowed` | An admin deactivating or resetting themselves |
| 409 | `member_state_conflict` | Re-issuing an invitation for a non-invited member, or a reset for a non-active one |
| 409 | `member_limit_reached` | 100 invited + active accounts in the restaurant |

## 12. Code layout
```
<base>.restaurants
  domain/        Restaurant, OpeningHours, RestaurantStatus, StaffRules (last manager, who may reset whom)
  application/   RestaurantService (profile), OnboardingService, SuspensionService, StaffService,
                 AdminAccountService, AuditReadService, PrivacyService; TenantStatus and RestaurantDirectory (ports it provides)
  persistence/   RestaurantRepository (always by id), AuditQueryRepository
  http/          StaffRestaurantRoutes, StaffMemberRoutes, AdminRoutes (paths from Endpoints.kt)
```
Staff accounts are read and changed only through `core`'s `StaffAccounts` port. Every method takes the `RestaurantId`, except the platform-admin ones.

## 13. Tests
| ID | Case | Req |
|---|---|---|
| IT-REST-01 | Onboarding creates an empty restaurant, an invited manager and a link; accepting it and signing in works; the new restaurant sees none of A's data | FR-040, FR-042 |
| IT-REST-02 | Onboarding retried with the same key → one restaurant; the replay returns a new link and the first link → `link_invalid` | FR-040, NFR-REL-02 |
| IT-REST-03 | A manager's token on `/v1/admin/restaurants` → 404; no token → 401 | FR-040 |
| IT-REST-04 | Profile edit: valid → 200 + new ETag; bad time zone, `UTC+7`, languages without `vi`, overlapping hours → 400; stale If-Match → 412; waiter → 403 | FR-044 |
| IT-REST-05 | Invite → link → sign-in with the role; duplicate email → 409 `email_in_use`; waiter or kitchen inviting → 403 | FR-041 |
| IT-REST-06 | Role change and deactivation revoke sessions (the next call → 401) and are audited | FR-041, NFR-SEC-03 |
| IT-REST-07 | Last manager can't demote or deactivate themselves → 409; two managers demoting each other at once leave one manager (concurrency test) | FR-041 |
| IT-REST-08 | Staff ID of restaurant B on every `/v1/staff/members/*` route → 404, and B is unchanged | FR-042, NFR-SEC-02 |
| IT-REST-09 | Manager issuing a reset for another manager → 403; admin issuing it → 201 | FR-041 |
| IT-REST-10 | Suspension: staff sign in; each endpoint in the registry without `allowedWhenSuspended` → 403 `restaurant_suspended`; reports and the audit log still readable; reactivation restores everything | FR-043 |
| IT-REST-11 | Registry check: every endpoint declares `allowedWhenSuspended` explicitly (no default) | FR-043 |
| IT-REST-12 | Admins: can't deactivate or reset themselves; last admin → 409; TOTP reset → the admin re-enrols through the link | NFR-SEC-04 |
| IT-REST-13 | Audit read: a manager sees only their restaurant and no IP or user agent; an admin sees IPs and the platform chain; an admin read is itself audited | NFR-SEC-03 |
| IT-REST-14 | Export contains the personal fields and none of the secrets; erase anonymises at once and keeps audit IDs | NFR-PRIV-01 |
| IT-REST-15 | Limits: the 101st invitation → 409 `member_limit_reached`; the 31st link in an hour → 429 | NFR-SEC-04 |

## 14. Changes this design makes to core-and-auth
Core-and-auth is still a draft, so these went straight into it (its versions 0.3 and 0.4):
- **§4:** one-time secrets (links, tokens) are never stored in an idempotent response. A replay of an operation that returns one issues a fresh one, which replaces the earlier one.
- **§6:** the caller query also joins `restaurants` for its status; the endpoint registry carries `allowedWhenSuspended`.
- **§8.1:** a suspended restaurant's staff can sign in (FR-043). The `403 restaurant_suspended` at sign-in is removed.
- **§6 and IT-AUTH-05:** a valid staff token on `/v1/admin/*` → 404 (applied in core-and-auth 0.4 after §15 Q1).

## 15. Owner decisions (2026-10-15)
The owner accepted all eight recommendations. They're recorded in STATUS.md (Decisions; Q-3 resolved; ASSUMPTION-12 confirmed).

1. A valid staff token on `/v1/admin/*` → `404`; a missing or invalid token → 401 (§3; core-and-auth §6, IT-AUTH-05).
2. `restaurants` is a global table without RLS; the caller query reads its status (§2). The tenant data keeps forced RLS.
3. Only a platform admin resets a manager's password; managers reset waiters and kitchen (§7).
4. A suspended restaurant works as the §6 table says.
5. Opening hours are display only in release 1 (§4.3).
6. ASSUMPTION-12: at most 100 invited + active staff accounts per restaurant; at most 30 invitation or reset links per restaurant per hour (§7).
7. Managers see the audit log without IP and user agent; admins see everything (§9).
8. An email already in use → `409 email_in_use` (§5, §7).

## 16. Traceability
The RTM's design column for FR-040, FR-041, FR-043 and FR-044 points here, and FR-042, NFR-SEC-02, NFR-SEC-03 and NFR-PRIV-01 add this design. The IT-REST IDs go into the RTM's tests column in Phase 4.
