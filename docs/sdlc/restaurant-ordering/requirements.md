# Requirements: Restaurant ordering service

| Version | Status | Owner | Approvers |
|---|---|---|---|
| 1.2 | Approved (G1 2026-10-13; CR-002 2026-10-14; CR-003 2026-10-14) | Manh Nguyen (product owner) | Manh Nguyen (product owner, tech lead) |

**Version history**

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-13 | Baselined at G1 |
| 1.1 | 2026-10-14 | [CR-002](cr/CR-002-built-in-staff-accounts.md): staff and admins sign in with accounts built into the service (email and password; TOTP for admins) instead of an external identity provider. Changed: §2 staff app and admin console, FR-010, FR-040, FR-041, §5 staff account, §6 identity-provider row removed, TBD-1 resolved |
| 1.2 | 2026-10-14 | [CR-003](cr/CR-003-record-resolved-items.md): the owner's resolved decisions written in, and the decree citation corrected. Changed: §2 customer mobile app and staff app, FR-050 (a bill counts on the day it was closed), NFR-COMPAT-01, §7 (Decree 356/2025), §9 (TBD-2 and TBD-3 resolved, ASSUMPTION-11 added) |

## 1. Brief
- **Problem:** In a busy restaurant, guests wait for a waiter to take their order and to bring the bill. Orders taken by hand get lost or misread on the way to the kitchen. Restaurant owners have no simple, shared view of what was ordered and sold. Small and mid-sized restaurants can't run their own ordering system, so they need one they can rent.
- **Solution in one line:** A multi-restaurant (SaaS) backend. Guests scan the QR code on their table and order from a mobile app or the web app. The order goes straight to the kitchen and staff screens, and staff move it through to served and paid.
- **Goals and KPIs** (agreed by the product owner at G1):
  - KPI-1: ≥ 60% of orders in pilot restaurants are placed by guests themselves (QR), not keyed in by staff, 3 months after go-live.
  - KPI-2: a placed order appears on the kitchen screen within 2 s for 99% of orders (see NFR-PERF-03).
  - KPI-3: < 1% of orders are cancelled or corrected because the table or an item was wrong.
  - KPI-4: onboard a new restaurant (menu + tables + staff + printed QR codes) in ≤ 1 working day without developer help.
- **In scope (release 1):**
  - Guest ordering by table QR, from a mobile app or web app, with no guest account.
  - Live order flow for the kitchen and waiters.
  - Menu management.
  - Table and QR-code management.
  - Table sessions and bills, settled at the counter.
  - Daily sales reports.
  - Restaurant (tenant) onboarding, and staff accounts with roles.
- **Out of scope (release 1):** online payment (bills are paid at the counter), guest accounts and loyalty, reservations, delivery and take-away, inventory and stock, kitchen-printer integration, multi-branch chains under one tenant, and the client apps themselves. This service is the backend only. Each client has its own project.
- **Constraints:**
  - Kotlin + Ktor backend (mandated). The ktor-sdlc default stack applies unless an ADR says otherwise.
  - **Deployment on Vercel** (mandated by the stakeholder, who has deployed a Ktor service there before). Phase 2 must confirm the runtime model, especially for live updates (risk R-01).
  - Vietnam's personal-data rules apply (§7).
  - Prices and bills are in VND.
  - Team size, budget and launch date: TBD-9.
- **Top risks:** see the [STATUS risk register](STATUS.md#risks). R-01 Vercel runtime limits for live updates · R-02 QR codes copied and used away from the table · R-03 data leaking across restaurants · R-04 data localisation.

### Roles
| Role | Who | How they reach the service |
|---|---|---|
| Guest | Anyone at a table, anonymous | Customer mobile app or customer web app, after scanning the table QR |
| Waiter | Restaurant staff | Staff app |
| Kitchen | Restaurant kitchen staff | Staff app (kitchen screen) |
| Manager | Restaurant owner or manager; manages menu, tables, staff, reports | Staff app |
| Platform admin | Operator of the SaaS service | Admin console |

Each staff member belongs to exactly one restaurant in release 1 (ASSUMPTION-3).

## 2. API consumers
| Consumer | Owner | Authenticates with | Expected traffic (avg / peak) | Oldest version to support | How changes are announced |
|---|---|---|---|---|---|
| Customer mobile app (Android and iOS) | Manh Nguyen (team TBD) | Table-session token from scanning the QR (anonymous) | Part of the guest load in NFR-SCAL-01 | 1.0.0 at launch; old versions supported for 12 months | Release notes; `Deprecation`/`Sunset` headers; forced-update minimum version |
| Customer web app (browser) | Manh Nguyen | Table-session token (how the browser holds it is decided in Phase 2: cookie via BFF or bearer) | Part of the guest load in NFR-SCAL-01 | Always the latest deployed version | Deployed together with the API |
| Staff app (web on tablets and phones) | Manh Nguyen | Staff access token issued by the service after sign-in with email and password (CR-002) | ≈ 3–6 open screens per restaurant during service | Latest only: web only in release 1 (no store app) | Release notes; deprecation headers |
| Admin console | Manh Nguyen | Staff access token with the platform-admin role; MFA (authenticator-app code, TOTP) required at sign-in | Low (< 1 req/s) | Latest | Internal |

Peak sizing (ASSUMPTION-1, confirmed at G1): 500 restaurants × 25 tables × 60% occupied at dinner peak × 2 guest devices per table ≈ **15,000 concurrent guest devices**, plus ≈ 2,500 staff screens. Request traffic ≈ **300 req/s** at peak, not counting live-update connections.

## 3. Functional requirements
Priorities are MoSCoW (M Must, S Should, C Could, W Won't this release). "Another restaurant's resource" means any resource of a tenant other than the caller's. "Another table's resource" means a resource of a table session the guest did not join.

### 3.1 Guest ordering (table session)
| ID | Story | Priority | Acceptance criteria (Given / When / Then) |
|---|---|---|---|
| FR-001 | As a guest, I want to scan my table's QR code and get the table's menu and session, so that I can order without waiting for a waiter | M | Given a table with a current QR token and no open session, when a guest presents the token, then a table session is opened for that table and the guest receives a session token scoped to that session.<br>Given the table already has an open session, when another guest presents the token, then they join the same session (one open session per table) and see the same orders and bill.<br>Invalid input: a malformed, forged or rotated (old) QR token → rejected; no session is created or revealed.<br>Table disabled, or restaurant suspended → rejected with a stable error code clients can show.<br>Retry: presenting the same token twice from the same device returns the same session, never a second one.<br>Limits: token presentations are rate-limited per device and per table (NFR-SEC-04). |
| FR-002 | As a guest, I want to browse the restaurant's menu by category with prices and availability, so that I can choose what to order | M | Given a valid session, when the guest asks for the menu, then they get the restaurant's active categories and items with name, description, price (VND) and an "available / sold out" flag, in the menu's display order.<br>Hidden (inactive) items are not returned.<br>Unauthorised: no session or an expired session → 401. A session can only read its own restaurant's menu.<br>The menu is cacheable and revalidated, so repeat loads are cheap (NFR-PERF-01). |
| FR-003 | As a guest, I want to place an order of items and quantities, with an optional note per item, so that the kitchen prepares it for my table | M | Given an open session and available items, when the guest submits items with quantities (1–20 each), then an order is created for the table, priced by the server at the current menu prices (client prices ignored), with status `placed`. It appears in the kitchen feed (FR-011) and on the session bill (FR-007).<br>Invalid input: an empty order, > 50 lines (ASSUMPTION-4), quantity out of range, a note > 200 characters, or an unknown item → 400 with per-field errors.<br>An item that is sold out or hidden → rejected naming the items, and nothing is ordered (the whole order is accepted or none of it).<br>Session closed meanwhile → rejected; nothing is ordered.<br>Retry: the same `Idempotency-Key` with the same body returns the original order, never a duplicate. The same key with a different body → 422.<br>Another table's session → 404.<br>Limits: orders per session are rate-limited (e.g. ≤ 10 per minute, ASSUMPTION-4). |
| FR-004 | As a guest, I want to order more rounds during the same visit, so that everything ends up on one bill | M | Given an open session with earlier orders, when any guest in the session places another order, then it is added to the same session and bill, and earlier orders are unchanged. |
| FR-005 | As a guest, I want to see the live status of my table's orders, so that I know what is coming | M | Given orders in the session, when their status changes (FR-012), then every guest device in the session sees the new status within NFR-PERF-03, without reloading.<br>If live updates are unavailable, the client can still read the current status on request (fallback), and reconnecting shows every change that was missed.<br>Guests never see other tables' orders (another table's session → 404). |
| FR-006 | As a guest, I want to cancel an order the kitchen hasn't accepted yet, so that I can fix a mistake | M | Given an order in `placed`, when a guest of the same session cancels it, then it becomes `cancelled`, leaves the bill and the kitchen feed shows it as cancelled.<br>Order already `accepted` or later → 409 with a code telling the guest to ask staff.<br>Concurrent edit: cancellation and kitchen acceptance at the same moment → exactly one wins; the loser gets 409 or 412.<br>Retry of a cancellation that already succeeded → same result, no error. |
| FR-007 | As a guest, I want to see my table's running bill, so that I know what we will pay | M | Given a session, when a guest asks for the bill, then they get every non-cancelled, non-rejected order line with quantity, unit price at the time of ordering, line total and the session total, in VND.<br>Later menu price changes don't change lines already ordered (price snapshot).<br>Menu prices include VAT, so the bill shows no separate VAT line (TBD-6, resolved at G1). No service charge in release 1 (ASSUMPTION-7). |
| FR-008 | As a guest, I want to call a waiter or ask for the bill from the app, so that I don't have to wave | S | Given an open session, when a guest sends a "call waiter" or "request bill" request, then waiters of that restaurant see it live, with the table number, until a waiter acknowledges it.<br>Repeats within 60 s are merged into one request (rate limit). |

### 3.2 Staff and kitchen
| ID | Story | Priority | Acceptance criteria (Given / When / Then) |
|---|---|---|---|
| FR-010 | As a staff member, I want to sign in with my own account and get only my role's permissions, so that the restaurant controls who does what | M | Given an active staff account with a role (manager, waiter or kitchen) in one restaurant, when they sign in with their email and password, then their calls are authorised by role and restricted to their restaurant.<br>Wrong password or unknown email → the same 401, with no hint which one was wrong.<br>Unauthenticated, expired, forged or wrong-audience token → 401.<br>A role calling an operation it lacks (e.g. kitchen editing the menu) → 403.<br>A deactivated staff account is refused on its next call, at the latest when its access token expires (≤ 15 min, ASSUMPTION-5). |
| FR-011 | As kitchen or waiter staff, I want a live feed of my restaurant's orders, so that I can start preparing at once | M | Given a signed-in waiter, kitchen or manager, when a guest places, cancels or changes an order, then it appears in the feed within NFR-PERF-03, with the table number, items, quantities, notes and time.<br>The feed contains only the caller's restaurant.<br>After a disconnect, the feed resumes without missing or duplicating any order (NFR-REL-03).<br>The current open orders can always be read as a list (fallback), filtered by status and paginated. |
| FR-012 | As kitchen or waiter staff, I want to move an order through its stages, so that everyone sees its progress | M | Given an order, when staff with the right role change its status, then only these transitions are allowed: `placed → accepted → preparing → ready → served`, `placed → rejected` (reason required) and `placed → cancelled` (FR-006, FR-032).<br>Any other transition → 409.<br>Concurrent edit: two staff updating the same order version → exactly one succeeds; the other gets 412 and must re-read.<br>Every change records who made it and when (audit, NFR-SEC-03), and is pushed to the guests (FR-005) and staff (FR-011).<br>An order of another restaurant → 404. |
| FR-013 | As a waiter or manager, I want to close the table's session when the guests have paid at the counter, so that the bill is final and the table is free | M | Given an open session, when staff close it and record the payment method (cash, card or bank transfer, recorded manually) and the amount received, then the bill is final, the session is closed, guest session tokens stop working and the next QR scan opens a new session.<br>Orders not yet `served` or `cancelled` → 409 unless staff confirm closing anyway (they are recorded as `cancelled` with a reason).<br>Closing twice (retry) → same result.<br>A closed bill can't be changed. Corrections are a manager action that is audited (TBD-7). |
| FR-014 | As a waiter, I want to place an order for a table on a guest's behalf, so that guests without a phone can still order | S | Same rules as FR-003. The order is marked as placed by staff, with the staff ID. |
| FR-015 | As kitchen staff, I want to reject a single item of an accepted order (e.g. it just ran out), so that the guest is told and isn't billed for it | S | Given an accepted order, when kitchen staff reject one line with a reason, then that line leaves the bill and the guests see it within NFR-PERF-03. |
| FR-016 | As a manager, I want waiters to confirm guest orders before the kitchen sees them, so that orders from someone who isn't at the table are stopped | C | Configurable per restaurant. When on, guest orders wait in `pending_confirmation` until a waiter confirms or rejects them. Decision: TBD-8 (depends on how R-02 is handled). |

### 3.3 Menu
| ID | Story | Priority | Acceptance criteria (Given / When / Then) |
|---|---|---|---|
| FR-020 | As a manager, I want to create, edit, reorder, hide and delete menu categories and items, so that the menu matches what we sell | M | Given a manager, when they create or edit an item (name ≤ 100 chars, description ≤ 500 chars, price in whole VND from 0 to 100,000,000, category, display order, active flag), then guests see the change on their next menu load.<br>Invalid input → 400 with per-field errors. Duplicate item name within a category → 409.<br>Concurrent edits → the stale update gets 412.<br>Deleting an item already ordered keeps its name and price on past orders and bills (snapshot); it is hidden from the menu.<br>Waiter or kitchen role → 403. Another restaurant's item → 404.<br>Limits: ≤ 50 categories and ≤ 1,000 items per restaurant (ASSUMPTION-4). |
| FR-021 | As kitchen staff or a manager, I want to mark an item sold out or available again with one action, so that guests can't order what we've run out of | M | Given an item, when kitchen or manager staff toggle "sold out", then new orders containing it are rejected (FR-003) and guests see it as sold out on their next menu load. Repeating the same toggle is harmless. |
| FR-022 | As a manager, I want to add a photo to a menu item, so that guests can see the dish | S | Given an image (JPEG, PNG or WebP, ≤ 5 MB, checked by content, not by extension), when the manager uploads it, then guests see it with the item. Other file types or larger files → 415 or 413. Images are stored outside the database. Must or Should: TBD-5. |
| FR-023 | As a manager, I want items to have options (e.g. size, spice level, extra toppings) with price adjustments, so that guests can customise dishes | S | Given an item with option groups (single or multiple choice, required or optional), when a guest orders it, then the chosen options are validated and priced by the server and shown to the kitchen. A missing required option or an unknown option → 400. |
| FR-024 | As a manager, I want menu item names and descriptions in Vietnamese and English, so that foreign guests can order | S | A guest's client asks for `vi` or `en`. A missing translation falls back to Vietnamese. |

### 3.4 Tables and QR codes
| ID | Story | Priority | Acceptance criteria (Given / When / Then) |
|---|---|---|---|
| FR-030 | As a manager, I want to create, edit, disable and delete tables (number or name, area, seats), so that the system matches my floor plan | M | Table number is unique within the restaurant (duplicate → 409). A table with an open session can't be deleted or disabled (409).<br>Waiter or kitchen role → 403. Another restaurant's table → 404.<br>Limit: ≤ 300 tables per restaurant (ASSUMPTION-4). |
| FR-031 | As a manager, I want to get a printable QR code for each table and rotate it when needed, so that only the codes on my tables work | M | Given a table, when a manager requests its QR code, then they get a URL or token that identifies only this restaurant and table and can't be guessed or forged.<br>When the manager rotates it, the old code stops opening or joining sessions at once. Guests already in an open session are not affected.<br>The same QR code works from both the mobile app and the web app (one URL that opens the app if installed and the web app otherwise; link format decided in Phase 2/3). |
| FR-032 | As a waiter or manager, I want to see all tables with their session state, and open, move or close sessions, so that I run the floor | M | Given a restaurant, when staff list tables, then each table shows free or occupied, its session start time, its order count and its running total.<br>Staff can open a session for a table without a scan, move an open session to a free table (its orders and bill move with it; moving to an occupied table → 409), and close it (FR-013).<br>Concurrent moves → only one succeeds. |

### 3.5 Restaurants (tenants), staff and access
| ID | Story | Priority | Acceptance criteria (Given / When / Then) |
|---|---|---|---|
| FR-040 | As a platform admin, I want to onboard a restaurant with its profile and first manager, so that a new customer can start using the service | M | Given a platform admin, when they create a restaurant (name, address, time zone, default Asia/Ho_Chi_Minh, currency VND) and invite its first manager by email address, then the restaurant exists, empty and isolated from all others, the admin receives a one-time invitation link (valid 72 h) to pass on, and the manager can set a password through it and sign in.<br>Non-platform-admin → 403 (404 for the admin route group, decided in Phase 3). Retry with the same `Idempotency-Key` → no duplicate restaurant. |
| FR-041 | As a manager, I want to invite, change the role of, and deactivate staff in my restaurant, so that I control access | M | Given a manager, when they invite staff by email address with a role, then the manager receives a one-time invitation link (valid 72 h) to pass on, and the person can set a password through it and sign in with that role in this restaurant only.<br>A manager can't change their own role or deactivate themselves if they are the last manager (409).<br>Waiter or kitchen role → 403. Staff of another restaurant → 404.<br>Every change is audited (NFR-SEC-03). |
| FR-042 | As a restaurant, I want my data to be invisible to every other restaurant, so that I can trust the service | M | For every endpoint: a caller (staff or guest session) of restaurant A who uses an ID belonging to restaurant B gets 404, and nothing of B is changed. Verified by the authorisation-matrix tests (NFR-SEC-02). |
| FR-043 | As a platform admin, I want to suspend and reactivate a restaurant, so that I can handle non-payment or abuse | S | A suspended restaurant: guests can't open sessions or order; staff can sign in and read reports only; no data is deleted. |
| FR-044 | As a manager, I want to edit my restaurant's profile (name, address, time zone, opening hours, languages), so that guests and reports show the right details | M | Invalid time zone or values → 400. Concurrent edits → 412. Waiter or kitchen role → 403. |

### 3.6 Reports
| ID | Story | Priority | Acceptance criteria (Given / When / Then) |
|---|---|---|---|
| FR-050 | As a manager, I want daily sales reports, so that I know how the restaurant is doing | M | Given closed bills, when a manager asks for a date range (≤ 93 days), then they get, per day in the restaurant's time zone: number of sessions, orders, gross sales, sales per payment method, top items by quantity and revenue, and sales per table.<br>Only closed, non-cancelled lines are counted.<br>A bill counts on the restaurant's local day on which it was closed, even if the guests sat down the day before.<br>Range > 93 days or end before start → 400. Waiter or kitchen role → 403.<br>Reports finish within NFR-PERF-04. |
| FR-051 | As a manager, I want to export sales lines as CSV, so that I can give them to my accountant | C | A date range ≤ 93 days, produced asynchronously if large. |

### 3.7 Won't have in release 1
Online payment and payment gateways · guest accounts, order history across visits, loyalty · reservations · delivery and take-away · inventory · kitchen printers · chains with several branches per tenant · invoices issued to the tax authority (e-invoice). Each needs its own requirements change (CR) later.

## 4. Non-functional requirements
The product owner confirmed these numbers at G1 (2026-10-13). "Peak" is the dinner peak load profile in the test plan (≈ 300 req/s and 15,000 concurrent guest devices).

| ID | Requirement | Target and condition | Verified by |
|---|---|---|---|
| NFR-PERF-01 | Latency of guest reads (menu, session, orders, bill) | p95 ≤ 200 ms, p99 ≤ 500 ms at peak, measured at the service edge | Load test (k6) on staging |
| NFR-PERF-02 | Latency of writes (place, cancel, status change, close session) | p95 ≤ 300 ms, p99 ≤ 800 ms at peak | Load test on staging |
| NFR-PERF-03 | Live-update delay | A new order or status change reaches connected staff and guest clients within 2 s at p95 (5 s at p99) after the write is acknowledged, at peak | Load test with live clients measuring end-to-end delay |
| NFR-PERF-04 | Report latency | A 31-day report for a restaurant with 300 bills/day returns in ≤ 3 s at p95 | Load test on staging with production-sized data |
| NFR-SCAL-01 | Throughput and connections | At launch: 300 req/s and 15,000 concurrent live guest clients plus 2,500 staff screens. 3× within 12 months by adding instances or capacity only, with no redesign | Load test; capacity model (Phase 2) |
| NFR-SCAL-02 | Fairness between restaurants | One restaurant's traffic (including abuse) can't push other restaurants beyond the PERF targets. Per-restaurant and per-session rate limits | Load test with one noisy tenant |
| NFR-AVAIL-01 | Availability SLO | 99.9% of valid requests succeed (non-5xx) per rolling 28 days, measured 24×7 | SLO dashboard |
| NFR-AVAIL-02 | Order flow SLO | 99.9% of order placements succeed and are visible to the kitchen within NFR-PERF-03 per 28 days | SLO dashboard |
| NFR-REL-01 | Recovery | RPO ≤ 5 min, RTO ≤ 1 h for the database and the service | Restore drill on staging |
| NFR-REL-02 | Retries and failures | No duplicate order, session or restaurant when a client retries; no acknowledged order lost if an instance dies mid-request | Resilience tests (repeated `Idempotency-Key`; instance kill under load) |
| NFR-REL-03 | Live updates never lose orders | When live delivery fails or a client reconnects, the client catches up on every change since its last one, with no gaps or duplicates; the kitchen can always fall back to reading the list | Resilience test: drop connections under load and compare the received events with the database |
| NFR-SEC-01 | Security level | OWASP ASVS L2 and the OWASP API Security Top 10 | ASVS checklist, DAST (ZAP), authorisation tests |
| NFR-SEC-02 | Tenant and table isolation | Zero cross-restaurant or cross-session reads or writes. Every endpoint has tests with another restaurant's staff, another table's session and each role | Authorisation-matrix tests in CI and Phase 5 |
| NFR-SEC-03 | Audit trail | Staff actions that change money, access or the menu (status changes, rejections, session closes, price edits, staff and role changes, QR rotation, tenant changes) are recorded: who, what, when, from where, request ID. Kept for TBD-4 (proposed 1 year), tamper-evident, separate from application logs | Audit tests; review |
| NFR-SEC-04 | Abuse limits | Rate limits per device, table session, staff user and restaurant on QR scans, orders and waiter calls. Admin console needs MFA | Tests at the limits; abuse-case tests |
| NFR-PRIV-01 | Privacy | Collect only the fields in §5. Retention per §5, enforced by a scheduled deletion/anonymisation job. Staff personal data can be exported and erased on request within the legal deadline (TBD-4). Personal data never appears in logs | Privacy review; retention-job tests; log review |
| NFR-PRIV-02 | Data residency | Data, backups, logs and traces stay in the regions agreed after the legal review (TBD-10) | Platform configuration review |
| NFR-COMPAT-01 | Client compatibility | Every supported client version keeps working. Only additive changes within `/v1`. A breaking change needs `/v2` and a deprecation period ≥ the customer mobile app's support window (12 months) | Contract tests against the oldest supported client's DTOs; OpenAPI breaking-change check in CI |
| NFR-OBS-01 | Observability | Request ID end to end (returned to clients, logged, traced); RED metrics per endpoint; business metrics (orders placed, feed delay, sessions open); a burn-rate alert per SLO, each with a runbook | Observability review (Phase 2/6) |
| NFR-OPS-01 | Deployability | Zero-downtime deploys; rollback in ≤ 10 min; database migrations are backward compatible with the previous release | Deployment and rollback rehearsal on staging |
| NFR-MAINT-01 | Maintainability | ktlint and detekt clean; line coverage ≥ 80% in services and domain; every endpoint has its route-test matrix | CI |
| NFR-I18N-01 | Localisation | API error responses carry stable codes that clients localise (vi, en). Money in VND (no minor unit). Dates and reports in the restaurant's time zone; timestamps in UTC on the wire | Contract review; report tests across time zones |

## 5. Data inventory
Retention values are proposals (TBD-4) pending the legal review.

| Data | Classification | Purpose | Stored where | Shared with | Retention | Legal basis |
|---|---|---|---|---|---|---|
| Staff account: name, email, phone (optional), role, restaurant, password hash (Argon2id), TOTP secret for admins (encrypted), refresh-token hashes | Personal (credentials are secrets) | Sign-in, access control, audit | Service database | — | While active + 30 days after deactivation, then anonymised (audit keeps the pseudonymous ID) | Contract with the restaurant / staff member's consent (TBD-10) |
| Restaurant profile: name, address, time zone, opening hours; owner contact name, email, phone | Internal; owner contact is personal | Operating the tenant, billing contact | Service database | — | While the tenant exists + 90 days | Contract |
| Menu: categories, items, prices, options, translations, images | Public | Shown to guests | Database; images in object storage | Guests | While the tenant exists | Contract |
| Tables and QR tokens | Internal (tokens are secrets) | Linking guests to a table | Database (store only a hash or a key ID of the signing secret, decided in Phase 2) | Printed on the table | While the table exists | Contract |
| Table session: session ID, device token, open/close times, table | Personal (pseudonymous, linkable to a device) | Grouping a visit's orders and bill | Database | — | 90 days, then device data removed (session kept with the bill) | Legitimate interest / performance of the service (TBD-10) |
| Orders and bill lines: items, quantities, price snapshot, status history, staff IDs, payment method and amount | Internal (financial records) | Kitchen flow, bill, reports, accounting | Database | The restaurant's manager | TBD-4: accounting retention under Vietnamese law (possibly 5–10 years) | Legal obligation (accounting) |
| Free-text item notes | Possibly personal (guests may type names, allergies) | Kitchen instructions | Database | Kitchen staff | Cleared 30 days after the session closes | Performance of the service |
| Access and application logs: IP address, user agent, request ID | Personal | Operations, security | Logging platform | Platform provider (processor) | 30 days | Legitimate interest (security) |
| Audit log | Personal (staff IDs, IPs) | Accountability, security | Separate audit store | — | TBD-4 (proposed 1 year) | Legitimate interest / legal obligation |

No guest names, phone numbers, emails, payment cards or locations are collected. Guests are anonymous.

## 6. Integrations
| System | Owner | Protocol and contract | Environments (sandbox?) | Limits and SLA | If it's down |
|---|---|---|---|---|---|
| Vercel (hosting platform) | Vercel | Platform runtime; limits on execution time, concurrency and long-lived connections to confirm in Phase 2 (R-01) | Preview and production deployments | Vercel plan SLA (TBD) | Service down → NFR-AVAIL budget; guests order via staff |
| Managed PostgreSQL (provider chosen in Phase 2) | Provider | PostgreSQL wire protocol | Per environment | Connection limits matter with serverless instances (pooling, Phase 2) | Service returns 503; no orders accepted; recovery per NFR-REL-01 |
| Object storage for menu images (FR-022, provider in Phase 2) | Provider | HTTPS object API; images served from a CDN | Per environment | Upload size ≤ 5 MB | Menus show without images; uploads fail with 503 |
| Live-update transport (if the platform needs a managed real-time service; Phase 2 ADR) | Provider TBD | e.g. SSE/WebSocket, or a hosted pub/sub | Per environment | Connection and message quotas vs NFR-SCAL-01 | Clients fall back to reading the list (NFR-REL-03) |

## 7. Compliance
- **Regulations:** Vietnam's Personal Data Protection Law (in force 1 January 2026) and Decree 356/2025/ND-CP on personal data protection (which replaced Decree 13/2023/ND-CP on 1 January 2026), plus implementing guidance. Vietnam's accounting rules for keeping sales records (retention, TBD-4). No PCI-DSS scope, because no payment card data is handled. GDPR doesn't apply unless restaurants outside Vietnam are onboarded (that would be a CR).
- **Data residency:** TBD-10. Legal review must say whether data about Vietnamese staff and transactions must be stored in Vietnam (Cybersecurity Law / Decree 53/2022) or may be transferred abroad with a transfer impact assessment. This decides the Vercel and database regions.
- **Audit requirements:** NFR-SEC-03. Who did what to orders, bills, menu prices, staff access, QR codes and tenants.
- **ASVS level:** **L2**, because the service holds staff personal data and financial records for many restaurants, and a cross-tenant leak would hit every customer.

## 8. Acceptance outline
One `AT-` scenario per Must FR, in [test-plan.md](test-plan.md) §5.

## 9. Assumptions and open items
- ASSUMPTION-1: Peak sizing: 500 restaurants × 25 tables × 60% occupancy × 2 devices ≈ 15,000 guest devices; ≈ 300 req/s; growth 3× in 12 months. Confirmed at G1.
- ASSUMPTION-2: All NFR targets in §4 (latency, SLOs, RPO/RTO, coverage). Confirmed at G1.
- ASSUMPTION-3: One staff member works in one restaurant; one restaurant is one branch.
- ASSUMPTION-4: Size limits: ≤ 50 lines per order, quantity 1–20, note ≤ 200 chars, ≤ 10 orders/min per session, ≤ 1,000 items and ≤ 300 tables per restaurant.
- ASSUMPTION-5: Staff access tokens live ≤ 15 min.
- ASSUMPTION-6: Anyone holding a table's current QR code can join its open session (the code is the only proof of presence). R-02 covers abuse; FR-016 and QR rotation are the mitigations.
- ASSUMPTION-7: No service charge on the bill in release 1. Adding one later is a CR.
- ASSUMPTION-11: In the FR-050 report a bill counts on the restaurant's local day on which it was closed (paid), even if the guests sat down before midnight. Confirmed 2026-10-14 (S-5); written into FR-050 by CR-003.
- ~~TBD-1~~: Resolved by CR-002 (2026-10-14): built-in staff accounts, no external identity provider.
- ~~TBD-2~~: Resolved by CR-003 (owner's decision 2026-10-14): customer mobile app on Android and iOS; staff app web only in release 1.
- ~~TBD-3~~: Resolved by CR-003 (owner's decision 2026-10-14): old customer mobile app versions are supported for 12 months.
- TBD-4: Retention periods (accounting records, audit log, staff data) and the erasure deadline. Legal review.
- TBD-5: FR-022 menu images: Must or Should.
- ~~TBD-6~~: Resolved at G1: menu prices include VAT; no separate VAT line.
- TBD-7: How a manager corrects a closed bill (void and reissue, or an adjustment line).
- TBD-8: FR-016 waiter confirmation: build in release 1 or not.
- TBD-9: Team, budget and target launch date.
- TBD-10: Data residency and legal basis per data type (legal review).

## 10. Sign-off
| Role | Name | Decision | Date |
|---|---|---|---|
| Product owner | Manh Nguyen | Approved | 2026-10-13 |
| Tech lead | Manh Nguyen | Approved | 2026-10-13 |
| Product owner (v1.1, CR-002) | Manh Nguyen | Approved | 2026-10-14 |
