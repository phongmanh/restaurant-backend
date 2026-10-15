# Requirements traceability matrix: Restaurant ordering service

Every requirement traces forward to design, code, tests and the release. Every test traces back to a requirement. Update this file in the same PR as the change.

| Req ID | Summary | Priority | Design (architecture § / feature design) | Endpoints, tables, events | Code (module / package, PRs) | Tests (IDs) | Result | Release |
|---|---|---|---|---|---|---|---|---|
| FR-001 | Start or join a table session from the QR | M | architecture.md §7; ADR-0006; design/tables.md | `dining_tables`, `table_sessions`; `POST /v1/guest/sessions` | | AT-001 | | |
| FR-002 | Browse the menu | M | architecture.md §1, §9; design/menu.md | `menu_categories`, `menu_items`; `menu_state`; `GET /v1/guest/menu` (ETag per menu version) | | AT-002 | | |
| FR-003 | Place an order (idempotent, server-priced) | M | architecture.md §1, §10; design/orders.md | `orders`, `order_lines`, `bills`, `idempotency_keys`; `POST /v1/guest/orders`; `OrderPlaced` | | AT-003 | | |
| FR-004 | Order more rounds in the same session | M | architecture.md §4; design/orders.md | `orders`, `bills` | | AT-004 | | |
| FR-005 | Live order status for guests | M | ADR-0003; design/orders.md (reads, events); design/realtime.md | `domain_events`, `outbox`; `GET /v1/guest/orders`, `/v1/guest/orders/{id}`, `GET /v1/guest/events`, `POST /v1/guest/realtime-token` | | AT-005 | | |
| FR-006 | Guest cancels a `placed` order | M | architecture.md §10; design/orders.md | `orders`; `POST /v1/guest/orders/{id}/cancel`; `OrderStatusChanged` | | AT-006 | | |
| FR-007 | Running bill with price snapshot (VAT-inclusive prices) | M | architecture.md §5; design/orders.md | `order_lines`, `bills`; `GET /v1/guest/bill` | | AT-007 | | |
| FR-008 | Call waiter / request bill | S | ADR-0003; design/tables.md | `waiter_calls`; `/v1/guest/waiter-calls`, `/v1/staff/waiter-calls/*` | | | | |
| FR-010 | Staff sign-in and roles | M | architecture.md §7, ADR-0011; design/core-and-auth.md | `staff_accounts`, `staff_sessions`, `staff_refresh_tokens`, `staff_mfa_challenges`, `staff_link_tokens`; `/v1/auth/*` | | AT-010 | | |
| FR-011 | Live order feed for staff | M | ADR-0003; design/orders.md (list fallback, events); design/realtime.md | `domain_events`, `outbox`; `GET /v1/staff/orders`, `GET /v1/staff/events`, `POST /v1/staff/realtime-token` | | AT-011 | | |
| FR-012 | Order state machine with optimistic concurrency | M | architecture.md §7, §10; design/orders.md | `orders`; `POST /v1/staff/orders/{id}/status`; `OrderStatusChanged` | | AT-012 | | |
| FR-013 | Close session, record payment at the counter | M | architecture.md §7; ADR-0006; design/orders.md | `table_sessions`, `bills`, `bill_adjustments`; `/v1/staff/table-sessions/{id}/close`, `.../bill`, `.../bill/adjustments`; `BillClosed`, `TableSessionClosed` | | AT-013 | | |
| FR-014 | Waiter orders on a guest's behalf | S | architecture.md §7; design/orders.md | `orders`; `POST /v1/staff/table-sessions/{id}/orders` | | | | |
| FR-015 | Kitchen rejects a single item | S | architecture.md §4; design/orders.md | `order_lines`; `POST /v1/staff/orders/{id}/lines/{lineId}/reject`; `OrderLineRejected` | | | | |
| FR-016 | Waiter confirmation of guest orders | W (release 1; CR-006) | architecture.md §11; design/orders.md §15 Q1 (owner 2026-10-15: not in release 1; CR-006) | `restaurants` settings | | | | |
| FR-020 | Manage menu categories and items | M | architecture.md §4; design/menu.md | `menu_categories`, `menu_items` | | AT-020 | | |
| FR-021 | Toggle item sold out | M | architecture.md §4; design/menu.md | `menu_items`; `menu_items.sold_out`; `PUT /v1/staff/menu/items/{id}/sold-out` | | AT-021 | | |
| FR-022 | Menu item images | S (TBD-5) | architecture.md §6, §12; design/menu.md | object storage; `menu_items.image_key`; `PUT /v1/staff/menu/items/{id}/image`; ImageStore (TBD-13) | | | | |
| FR-023 | Item options and modifiers | S | architecture.md §4; design/menu.md | `menu_item_options`; `menu_option_groups`, `menu_options`; MenuCatalog | | | | |
| FR-024 | Menu translations vi/en | S | architecture.md §11; design/menu.md | `menu_items`; `*_vi`, `*_en` columns; `?lang=` | | | | |
| FR-030 | Manage tables | M | architecture.md §4, §7; design/tables.md | `dining_tables`; `/v1/staff/tables/*` | | AT-030 | | |
| FR-031 | Generate and rotate table QR codes | M | architecture.md §7; ADR-0006; design/tables.md | `dining_tables.qr_key_version`; QR key set; `/v1/staff/tables/{id}/qr-code/*` | | AT-031 | | |
| FR-032 | Table overview; open, move, close sessions | M | architecture.md §7; design/tables.md | `table_sessions`; `GET /v1/staff/tables`, `/v1/staff/table-sessions/{id}/move` | | AT-032 | | |
| FR-040 | Onboard a restaurant and its first manager | M | architecture.md §7, ADR-0011; design/restaurants.md | `restaurants`, `staff_members` | | AT-040 | | |
| FR-041 | Manage staff and roles | M | architecture.md §7, ADR-0011; design/restaurants.md | `staff_members`, `audit_events` | | AT-041 | | |
| FR-042 | Tenant isolation on every call | M | ADR-0005; design/core-and-auth.md; design/restaurants.md; design/menu.md; design/tables.md; design/orders.md; design/realtime.md; design/reports.md | all tenant tables (RLS) | | AT-042 | | |
| FR-043 | Suspend / reactivate a restaurant | S | architecture.md §7; design/restaurants.md; design/tables.md; design/orders.md; design/realtime.md | `restaurants` | | | | |
| FR-044 | Edit restaurant profile | M | architecture.md §11; design/restaurants.md | `restaurants` | | AT-044 | | |
| FR-050 | Daily sales report | M | architecture.md §1, §9 (Reports); ASSUMPTION-11 (day of closing); design/reports.md | `daily_sales`, `daily_sales_payment`, `daily_sales_table`, `daily_sales_item`, `sales_reconciliation`; `GET /v1/staff/reports/daily-sales` | | AT-050 | | |
| FR-051 | CSV export of sales lines | W (release 1; CR-007) | architecture.md §4; design/reports.md §13 Q3 (owner 2026-10-15: not in release 1; CR-007) | `order_lines` | | | | |
| NFR-PERF-01 | Guest reads p95 ≤ 200 ms, p99 ≤ 500 ms at peak | M | architecture.md §1, §9; design/menu.md | | | | | |
| NFR-PERF-02 | Writes p95 ≤ 300 ms, p99 ≤ 800 ms at peak | M | architecture.md §9; ADR-0004; design/orders.md §3.1 | | | | | |
| NFR-PERF-03 | Live-update delay ≤ 2 s p95 | M | ADR-0003; design/realtime.md | `domain_events`, `outbox`, `event_counters` | | | | |
| NFR-PERF-04 | 31-day report ≤ 3 s p95 | M | architecture.md §1, §9 (Reports), §17 S-5 (met: p95 10.4 ms); design/reports.md | `daily_sales`, `daily_sales_payment`, `daily_sales_table`, `daily_sales_item` | | | | |
| NFR-SCAL-01 | 300 req/s + 17,500 live clients; 3× in 12 months | M | architecture.md §9; ADR-0002 | | | | | |
| NFR-SCAL-02 | Fairness between restaurants | M | ADR-0008 | | | | | |
| NFR-AVAIL-01 | 99.9% success per 28 days | M | architecture.md §13 | | | | | |
| NFR-AVAIL-02 | Order flow SLO 99.9% | M | architecture.md §13; design/orders.md; design/realtime.md | | | | | |
| NFR-REL-01 | RPO ≤ 5 min, RTO ≤ 1 h | M | architecture.md §6, §13 | | | | | |
| NFR-REL-02 | No duplicates on retry, no lost orders | M | architecture.md §10; design/core-and-auth.md; design/orders.md | `idempotency_keys`, `outbox` | | | | |
| NFR-REL-03 | Live updates catch up with no gaps | M | ADR-0003; design/realtime.md | `domain_events`; `GET /v1/guest/events`, `GET /v1/staff/events` | | | | |
| NFR-SEC-01 | ASVS L2 + API Top 10 | M | architecture.md §12; design/core-and-auth.md | | | | | |
| NFR-SEC-02 | Tenant and table isolation tests | M | ADR-0005, ADR-0006; design/core-and-auth.md; design/restaurants.md; design/tables.md; design/menu.md; design/orders.md; design/realtime.md; design/reports.md | | | AT-042 | | |
| NFR-SEC-03 | Audit trail of staff actions | M | architecture.md §11; design/core-and-auth.md; design/restaurants.md; design/menu.md; design/tables.md; design/orders.md | `audit_events` | | AT-041 | | |
| NFR-SEC-04 | Abuse rate limits; admin MFA | M | ADR-0008, ADR-0011; design/core-and-auth.md; design/tables.md; design/orders.md; design/realtime.md | | | | | |
| NFR-PRIV-01 | Minimisation, retention job, staff data erasure | M | architecture.md §6, §11; design/core-and-auth.md; design/restaurants.md; design/tables.md; design/orders.md; design/realtime.md | | | | | |
| NFR-PRIV-02 | Data residency | M | ADR-0002, ADR-0004 | | | | | |
| NFR-COMPAT-01 | Additive changes within `/v1`; oldest client supported | M | architecture.md §5; ADR-0007; design/core-and-auth.md; design/orders.md §2 (unknown enum values); design/realtime.md §5 | | | | | |
| NFR-OBS-01 | Request IDs, RED metrics, SLO alerts | M | architecture.md §13; ADR-0009; design/core-and-auth.md; design/realtime.md §11; design/reports.md §9 | | | | | |
| NFR-OPS-01 | Zero-downtime deploys, rollback ≤ 10 min | M | architecture.md §8, §14 | | | | | |
| NFR-MAINT-01 | ktlint/detekt clean, coverage ≥ 80% | M | ADR-0001; architecture.md §14 | | | | | |
| NFR-I18N-01 | Error codes, VND, restaurant time zone | M | architecture.md §5, §11; design/core-and-auth.md §3; design/menu.md | | | AT-050 | | |

## Coverage checks
| Check | Required |
|---|---|
| Requirements without a design | 0 at G3 |
| Endpoints without a contract entry | 0 at G3 |
| Endpoints without the route-test matrix (401 / 400 / 404 / success) | 0 at G4 |
| Must requirements without a passing test | 0 at G5 |
| Tests without a requirement | 0 (trace them, or remove them) |
