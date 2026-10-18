# ASVS 5.0 Level 2 checklist and ZAP scan (ST-11)

> v0.2, 2026-10-18 · Phase 5 step 6a · Reviewed by the owner 2026-10-18 (Q-21) · Test-plan §4 ST-11 (NFR-SEC-01) · Builds on [security-review.md](security-review.md)

**In plain words:** OWASP's Application Security Verification Standard (ASVS) is a list of checks a secure application should pass, and NFR-SEC-01 asks for its Level 2. This document goes through every Level 1 and 2 requirement of version 5.0.0 (253 of them) and says, for each, whether the service meets it and how we know. ZAP is a free scanner that attacks the running service from outside, the way an attacker's tools would; it ran against staging with the contract as its map. Most requirements are met. The checklist found one real defect (D-2: the database connection doesn't check the server's certificate) and six smaller gaps, each with a suggested fix for you to choose. ZAP found no real High; one defect it found (a header sent twice) is fixed in this step.

## 1. Scope and method
- **Standard:** [OWASP ASVS 5.0.0](https://github.com/OWASP/ASVS/releases/tag/v5.0.0_release), its flat JSON export (CC BY-SA 4.0). Level 1 and 2: 253 requirements; Level 3 is out of scope.
- **Evidence:** the code at this branch, the designs, CI's tests (IT-…), the staging system tests (AT-…, ST-…) and the ZAP scan in §2. A verdict names its evidence; where a claim rests on a probe made for this checklist, it says so.
- **Statuses:** **Met**; **Partly** (part is in place, the gap is named); **Gap** (not in place); **Deviation** (a documented decision goes against the requirement, and needs your acceptance); **Platform** (Vercel, Neon or Ably provides it or limits it); **Apps** (a requirement on the staff, admin or customer apps, not built yet); **N/A** (the feature doesn't exist here, e.g. GraphQL, OAuth, WebRTC).
- **What it doesn't cover:** the apps (Apps rows wait for them), provider accounts and their settings (security-review.md §4), and the image upload (FR-022 waits for TBD-13; its rows are re-checked then).

## 2. ZAP scan
**Result: no High finding stands.** ZAP 2.17.0 (`./gradlew zapScan`, deploy/README.md "ZAP scan") ran against deployment `restaurant-staging-74er7may0` (step 3's build) on 2026-10-18 in two runs: **13,411 requests** in about 21 minutes, inside the run cap of 10,000 each (test-plan §9).

| Run | Profile | Token | Paths | Requests | Rules that ran |
|---|---|---|---|---|---|
| `tn2dej` | setup | admin, Z's manager | Z onboarded | 16 | |
| | guest | Z's guest session | 10 | 851 | all 11 |
| | staff | Z's manager | 19 | 4,302 (cap) | 8 of 11: not 40008, 40034, 40035 |
| | floor | Z's manager | 23 | 2,879 | all 11 |
| `tn2e0r` | setup and clean-up | admin | Z onboarded; `TRACE` probe | 28 | |
| | admin | platform admin | 20 | 4,500 (cap) | 8 of 11: not 40008, 40034, 40035 |
| | anonymous | none | 77 imported, 8 attacked | 835 | all 11 |

Active rules, at low strength: path traversal (6), CRLF (40003), parameter tampering (40008), SQL injection (40018) and its PostgreSQL time-based form (40022), `.env` and hidden files (40034, 40035), server-side code (90019), OS command (90020), cloud metadata (90034) and template injection (90035); every passive rule. 40034 and 40035 check the site, not a path, so the guest, floor and anonymous profiles covered them. Parameter tampering didn't reach the staff and admin paths before their caps; the other rules covered every path the token could reach. The rules left out and why: `ZapPlan.ACTIVE_RULES`.

| Risk | Alert | Where | Verdict |
|---|---|---|---|
| High (confidence Low) | Path Traversal (6) | floor: `POST /v1/staff/tables`, field `number`, value `tables` | **False positive.** ZAP sent the path's own last segment as the table's number and got a `201` that looked like the original. `number` is a free-text label, stored and returned as JSON; the service opens no file by a name from input (V5.3.2) |
| Low | Strict-Transport-Security Multiple Header Entries (10035) | staff, anonymous: the unbuilt image routes (`404`) | **Defect, fixed in this step.** Every problem response carried `Strict-Transport-Security` and `nosniff` twice: it passes through `onCallRespond` twice. `ResponseHeaders` now adds each once, and `HttpConventionsTest` checks for exactly one value. Not a security weakness (the value is the same), but it breaks RFC 6797 |
| Informational | Re-examine Cache-control Directives (10015) | all token profiles | As intended: `no-store`, and `private, no-cache` on the menu reads (CR-004) |
| Informational | Sensitive Information in URL (10024) | `GET /v1/staff/orders?session_id=…` | Not sensitive: a session ID is an identifier; access rests on the token, and another restaurant's session ID answers `404` |
| Informational | Retrieved from Cache (10050) | anonymous: `GET`s answered `401` | Not cached: the header was `Age: 0`, Vercel's edge passing a fresh response through |
| Informational | Authentication Request Identified (10111) | `POST /v1/auth/sign-in` | Information only |

No alert for SQL, command, code or template injection, CRLF, path traversal (but the one above), leaked files, cloud metadata or error disclosure. `TRACE /v1/auth/me` answered `405`.

**Side effects on staging:** two restaurants named `Nhà hàng Z <run> (ZAP)`. The second is suspended (the admin profile's own suspend call), and the first, with its junk tables, menu entries and invited members, stays like AT-040's restaurant C. The admin profile also invited 7 admins with ZAP's test values; the clean-up deactivated them.

## 3. Findings
| ID | Requirements | What | Severity | Suggested fix |
|---|---|---|---|---|
| **D-2** (F-1) | V12.3.2, V12.3.4 | The database URLs use `sslmode=require`. In pgjdbc that encrypts the connection but checks neither the server's certificate nor its name, so a machine between Vercel and Neon could pose as the database and read every query, passwords' hashes included | **S1** (a security issue, test-plan §8) | Use `sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory` (the JDK's trust store, which holds Neon's public CA) in `generateSecrets` and the three URLs; `JdbcTarget` refuses a weaker mode for any host but `localhost`. You update the URLs in Vercel and `.env.staging` |
| F-2 | V3.4.3, V3.4.5, V3.4.6 | API responses carry no `Content-Security-Policy` or `Referrer-Policy` | Low | Add `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'` and `Referrer-Policy: no-referrer` to `ResponseHeaders`, with a test |
| F-3 | V15.1.1, V15.1.2, V15.2.1 | No dependency vulnerability scan, no SBOM, no time frame to fix a vulnerable library (security-review.md §4 left it for later) | Medium | A CycloneDX SBOM and an OSV-Scanner check in CI that fails on a High or Critical; time frames: Critical 7 days, High 30 days, others with the next release; Dependabot for version updates |
| F-4 | V7.1.2, V7.4.5, V7.5.2 | No written rule for parallel sessions (today: one per device, no limit); a person can't see or end their other sessions, and an admin can end someone's sessions only by changing the account | Low | Either a CR that writes the rule and adds `GET /v1/auth/sessions`, `DELETE /v1/auth/sessions/{id}` and an admin "sign out everywhere" (additive), or accept for release 1 |
| F-5 | V6.1.2, V6.2.11 | Passwords are checked against the email but not against context words (the product's name, the restaurant's name, role names) | Low | A CR to core-and-auth §8.6 and a short list in `PasswordPolicy`, plus the restaurant's name |
| F-6 | V11.1.1 | Keys rotate by ID and the runbook says how, but no policy says how long each key lives and who may hold it | Low | A "Key policy" section in deploy/README.md, from §5's inventory |
| F-7 | V16.4.3, V16.1.1 | On Hobby, Vercel keeps runtime logs for an hour and log drains need a paid plan, so logs never reach a separate system | Medium for production; none on staging | Production: a log drain to Grafana Cloud (Loki), with Vercel Pro (ADR-0002's production plan). A residual risk on the free plans |

The **Partly** rows that aren't findings are accepted as they stand unless you say otherwise: V2.4.1 (staff writes have no per-call limit), V6.2.12 (the common list rather than a full breach corpus), V13.2.1, V13.2.4–5 and V13.3.1 (what the free plans offer), V14.1.x (waits for TBD-10), V16.3.2–3 (refused calls are logged as request lines, not audit events).

**The owner's decisions (Q-21, 2026-10-18):** D-2 is fixed now; F-2, F-3 and F-6 are fixed in step 6a's follow-up PR; F-4 and F-5 are accepted as gaps for release 1; F-7 stays a residual risk on the free plans (R-19).

## 4. Deviations accepted by the owner
ASVS allows a requirement to be relaxed only with a documented reason and compensating controls. These three are decisions already in the designs, and the owner accepted each as documented here (Q-21, 2026-10-18):

| Requirement | Decision | Reason and compensating controls |
|---|---|---|
| V6.3.3 (MFA at L2) | Restaurant staff sign in with a password only; admins add TOTP (requirements §2, CR-002) | Staff use shared restaurant devices during service. Controls: per-IP limits, per-account lock, 24 h sessions, revocation that takes effect on the next call, and staff can reach one restaurant only |
| V6.4.1 (initial secrets short-lived) | Invitation links last 72 h, reset links 24 h (core-and-auth §8.5) | The link is returned to the inviting manager or admin, who passes it on; a 10-minute link would rarely survive that. Controls: 256-bit, single use, stored hashed, replaced by a new link, voided when access ends |
| V3.3.3 (`__Host-` cookie) | The refresh cookie is `__Secure-` with `Path=/api/v1/auth` (core-and-auth §8.3) | `__Host-` requires `Path=/`; the narrow path keeps the cookie off every call but refresh and sign-out |

And one platform limit: V4.1.2, Vercel redirects plain HTTP to HTTPS for every project and can't be told not to.

## 5. Inventories
### 5.1 Cryptographic inventory (V11.1.2)
| Key or secret | Algorithm | Where it lives | Used for | Rotation |
|---|---|---|---|---|
| `STAFF_JWT_KEYS` | ECDSA P-256 (ES256), key set by `kid` | Vercel env | Signing and checking staff and admin access tokens | New `kid` signs, old ones verify until their tokens expire (15 min) |
| `GUEST_JWT_KEYS` | ES256, key set | Vercel env | Guest session tokens (ADR-0006) | As above |
| `QR_SIGNING_KEYS` | ES256, numeric key IDs | Vercel env | The printed QR codes (tables §2) | A new key ID signs new codes; a table's code also dies when its `qr_key_version` moves (`rotate`, `rotate-all`) |
| `TOTP_ENCRYPTION_KEYS` | AES-256-GCM, key set | Vercel env | Admins' TOTP secrets at rest (`SecretBox`) | New key ID encrypts; old ones decrypt |
| `EMAIL_HMAC_KEY` | HMAC-SHA-256 | Vercel env | The audit event of a sign-in with an unknown email: it records the email's HMAC, so repeated tries can be linked without storing an address that may be someone else's | A new key; older events keep the old HMAC |
| `CRON_SECRET` | 256-bit random | Vercel env (Vercel Cron sends it) | `/internal/jobs/*` | Replace in Vercel |
| Database passwords (`app`, `app_platform`, `migrator`) | 192-bit random | Vercel env; `migrator` in CI only | PostgreSQL roles | deploy/README.md "Rotating a secret" |
| `ABLY_API_KEY` | Ably key (publish, subscribe) | Vercel env | Minting channel-scoped Ably tokens | In Ably's dashboard |
| `UPSTASH_REDIS_REST_TOKEN` | Upstash token | Vercel env | Rate-limit counters | In Upstash's console |
| `OTEL_EXPORTER_OTLP_HEADERS` | Grafana Cloud token | Vercel env | Pushing metrics | In Grafana Cloud |
| `VERCEL_AUTOMATION_BYPASS_SECRET` | Vercel secret | Owner's machine | Staging tools past Deployment Protection | In Vercel |
| Passwords | Argon2id (m=19 MiB, t=2, p=1), PHC strings | `staff_accounts` | Staff and admin passwords | Re-hashed at sign-in when parameters change |
| Refresh tokens, links, MFA challenges | 256-bit random, SHA-256 at rest | PostgreSQL | Sessions and one-time links | Single use |
| Audit chain | SHA-256 hash chain | `audit_events` | Tamper evidence (ST-13) | Not a key |

### 5.2 Log inventory (V16.1.1)
| Log | What | Where | Kept | Who reads it |
|---|---|---|---|---|
| Application log | One JSON line per request (status, method, path, `request_id`) and errors; no personal data, secrets or tokens (architecture §11) | Vercel runtime logs | 1 h on Hobby; longer on Pro, with a drain (F-7) | The project's members |
| Audit log | Security and admin events with actor, target, IP and request ID, hash-chained (core-and-auth §12) | PostgreSQL `audit_events` | 1 year (TBD-4), deleted only through a `SECURITY DEFINER` function (core-and-auth §12) | Managers (their restaurant), admins |
| Metrics | Request counts and durations, domain meters (architecture §13) | Grafana Cloud (OTLP) | 14 days on the free plan | The owner |

## 6. Verdicts by status

| Status | Requirements |
|---|---|
| Met | 140 |
| Partly | 17 |
| Gap | 11 |
| Deviation | 3 |
| Platform | 8 |
| Apps | 8 |
| N/A | 66 |
| **All L1 and L2** | **253** |

## 7. The checklist
Each L1 and L2 requirement of ASVS 5.0.0 by its ID, with a short label of ours (the standard's own wording is at the link above), the verdict and the evidence. L3 requirements are out of scope (NFR-SEC-01 sets L2).

### V1 Encoding and Sanitization
| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V1.1.1 | 2 | Decode once, before validation | Met | Ktor decodes the URL and kotlinx.serialization the JSON body once, before the `*Inputs` validators run; nothing decodes again later |
| V1.1.2 | 2 | Encode at the interpreter | Met | SQL through bound parameters (`Jdbc.bind`), JSON through kotlinx.serialization, logs through the Logstash JSON encoder |
| V1.2.1 | 1 | Context-aware output encoding | Met | Every body is JSON written by kotlinx.serialization; no HTML or XML responses. Header values come from code, not input |
| V1.2.2 | 1 | Safe URL building | Met | The only URLs built are QR and invitation/reset links: a configured `https` base + a base64url token (`QrCodes`, `LinkService`) |
| V1.2.3 | 1 | JSON built by a serialiser | Met | kotlinx.serialization everywhere; no JSON string concatenation (the audit chain's `CanonicalJson` serialises too) |
| V1.2.4 | 1 | Parameterised queries | Met | Exposed and `Jdbc.bind` bind every value; the two `LIKE` filters escape `%`/`_` with `ESCAPE '\'` (`RestaurantRepository`, `AuditQueryRepository`). ZAP rules 40018/40022 (ST-11) |
| V1.2.5 | 1 | No OS command injection | Met | The service runs no OS commands. ZAP rule 90020 (ST-11) |
| V1.2.6 | 2 | LDAP injection | N/A | No LDAP |
| V1.2.7 | 2 | XPath injection | N/A | No XML or XPath |
| V1.2.8 | 2 | LaTeX injection | N/A | No LaTeX |
| V1.2.9 | 2 | Regex metacharacters | Met | No regex is built from input; `LIKE` patterns are escaped (V1.2.4) |
| V1.3.1 | 1 | WYSIWYG HTML sanitising | N/A | No HTML input; notes and names are plain text the apps render as text (V3.2.2) |
| V1.3.2 | 1 | No eval or dynamic code | Met | No script engines, reflection on input or expression languages. ZAP rules 90019/90035 (ST-11) |
| V1.3.3 | 2 | Sanitise for dangerous contexts | Met | Inputs have length, pattern and range limits (`*Inputs`, contract schemas); none reaches a shell, template or interpreter |
| V1.3.4 | 2 | SVG sanitising | N/A | No uploads in release 1 yet (FR-022 waits for TBD-13); re-check with the image upload |
| V1.3.5 | 2 | Markdown/CSS/XSL content | N/A | None accepted |
| V1.3.6 | 2 | SSRF | Met | Outbound calls go only to configured hosts (Neon, Upstash, Ably, the OTLP endpoint); no URL comes from a request. The menu image upload (TBD-13) must keep it so |
| V1.3.7 | 2 | Template injection | Met | No server-side templates. ZAP rule 90035 (ST-11) |
| V1.3.8 | 2 | JNDI injection | Met | No JNDI lookups; Logback has no JNDI appender configured, and log messages carry no input (architecture §11) |
| V1.3.9 | 2 | Memcache injection | N/A | No memcache; Upstash is called over its REST API with JSON bodies |
| V1.3.10 | 2 | Format strings | Met | No format string comes from input (only `Rfc3339.format` on `Instant`s) |
| V1.3.11 | 2 | SMTP/IMAP injection | N/A | The service sends no email in release 1: links are handed over by hand |
| V1.4.1 | 2 | Memory-safe code | Met | JVM (Kotlin); no native code of our own |
| V1.4.2 | 2 | Integer overflow | Met | Bounded inputs (quantity 1–20, money as whole đồng with schema limits) and `BIGINT` money; page limits ≤ 200 |
| V1.4.3 | 2 | Freed memory | Met | JVM garbage collection; pools and meters are released on stop (`DatabasePools`, `MetricsTest`) |
| V1.5.1 | 1 | XML parsers | N/A | No XML accepted; a non-JSON body gets `415` |
| V1.5.2 | 2 | Safe deserialisation | Met | kotlinx.serialization into fixed classes, no polymorphic or Java deserialisation of input |

### V2 Validation and Business Logic
| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V2.1.1 | 1 | Documented input rules | Met | The contract's schemas (formats, lengths, patterns, ranges) and each design's input section |
| V2.1.2 | 2 | Documented combined-data rules | Met | Designs: e.g. options within an item's groups (menu §5), payment against the bill (orders §7), report date ranges (reports §6) |
| V2.1.3 | 2 | Documented business limits | Met | Per-restaurant and global limits: menu §3 (counts), core-and-auth §11 and each design's rate limits, orders §3 (1–50 lines, quantity 1–20) |
| V2.2.1 | 1 | Positive validation of all input | Met | Every route validates its body and query with allow-lists, patterns and ranges (`*Inputs.kt`); unknown fields are ignored, never bound |
| V2.2.2 | 1 | Validation at the service | Met | All validation is in the service; the apps' checks are for usability only |
| V2.2.3 | 2 | Combined data checked | Met | Service rules (orders, bills, menu options, report ranges), tested in the IT suites |
| V2.3.1 | 1 | Steps in order | Met | State machines with checks and versions: order statuses (orders §2), invitation then password then TOTP (core-and-auth §8.5), MFA challenge before tokens |
| V2.3.2 | 2 | Business limits enforced | Met | Menu counts under the `menu_state` lock, per-restaurant limits, quantities; concurrency tests (menu §3) |
| V2.3.3 | 2 | Transactions | Met | Each operation is one transaction with its audit and outbox rows (NFR-SEC-03, ADR-0003); a transaction runs once (`defaultMaxAttempts = 1`) |
| V2.3.4 | 2 | No double booking | Met | One open session per table (partial unique index), the lock order of orders §3.1, `If-Match` versions, advisory locks for cross-row rules |
| V2.4.1 | 2 | Anti-automation | Partly | Sign-in, MFA, links, refresh, password change, guest orders and waiter calls, report reads are rate limited (ADR-0008, ST-14). Other staff writes have no per-call limit, only the WAF's per-IP rule on `/v1/auth/` |

### V3 Web Frontend Security
| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V3.2.1 | 1 | No rendering in the wrong context | Met | Every response is JSON with `X-Content-Type-Options: nosniff`; nothing is meant to render in a browser |
| V3.2.2 | 1 | Text rendered as text | Apps | The web apps must render names and notes with `textContent` (not built yet) |
| V3.3.1 | 1 | Cookies `Secure` and prefixed | Met | The refresh cookie is `__Secure-rt-staff`/`__Secure-rt-admin`; `Secure` (`RefreshCookies`) |
| V3.3.2 | 2 | `SameSite` set | Met | `SameSite=Strict` (core-and-auth §8.3) |
| V3.3.3 | 2 | `__Host-` prefix | Deviation | `__Host-` needs `Path=/`; the cookie is scoped to `/api/v1/auth` so it reaches only the auth calls (core-and-auth §8.3). `__Secure-` with a narrow path instead |
| V3.3.4 | 2 | `HttpOnly`, token only in `Set-Cookie` | Met | `HttpOnly`; the refresh token never appears in a body |
| V3.4.1 | 1 | HSTS, 1 year, subdomains | Partly | The service sends `max-age=63072000; includeSubDomains` on every response (`Http.kt`); ZAP found it twice on problem responses (rule 10035), fixed in this step and tested once per response. Vercel's own errors and redirects carry Vercel's header |
| V3.4.2 | 1 | CORS allow-list | Met | Ktor `CORS` with the configured origins only, no credentials (`Http.kt`) |
| V3.4.3 | 2 | Content-Security-Policy | Gap | Not sent. For a JSON API, `default-src 'none'; frame-ancestors 'none'; base-uri 'none'` is enough (finding F-2) |
| V3.4.4 | 2 | `nosniff` everywhere | Met | Every service response (`Http.kt`) |
| V3.4.5 | 2 | Referrer-Policy | Gap | Not sent; `no-referrer` (finding F-2). The web apps set their own |
| V3.4.6 | 2 | CSP `frame-ancestors` | Gap | Not sent (finding F-2) |
| V3.5.1 | 1 | CSRF without preflight reliance | Met | Every state change but refresh and sign-out takes a bearer token in `Authorization`, which a cross-site request can't add |
| V3.5.2 | 1 | Preflight can't be skipped | Met | Cookie-authenticated `/v1/auth/*` POSTs require `Content-Type: application/json` (not CORS-safelisted, so a preflight happens and the allow-list refuses), plus `SameSite=Strict` |
| V3.5.3 | 1 | Safe methods don't change state | Met | No `GET` changes anything (contract; reading a QR code doesn't rotate it) |
| V3.5.4 | 2 | Separate hostnames | Met | The API, the customer web app and the staff/admin apps are separate Vercel projects; the staff apps reach the API through a same-origin rewrite (core-and-auth §8.3) |
| V3.5.5 | 2 | `postMessage` origin checks | Apps | Web apps (not built) |
| V3.7.1 | 2 | Supported client technologies | Apps | Web apps (not built) |
| V3.7.2 | 2 | Redirect allow-list | Met | The service sends no redirects |

### V4 API and Web Service
| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V4.1.1 | 1 | Content-Type matches body | Met | `application/json; charset=UTF-8` or `application/problem+json` (JSON's media types define no charset; it is UTF-8). Checked on every staging response by `ContractCheck` |
| V4.1.2 | 2 | No HTTP→HTTPS redirect for APIs | Platform | Vercel answers plain HTTP with a `308` to HTTPS for every project and can't be turned off. Clients use `https://` URLs only |
| V4.1.3 | 2 | Intermediary headers can't be spoofed | Met | The client IP is Vercel's `x-real-ip`, which the edge sets. ST-14 sends a spoofed `X-Real-IP` and `X-Forwarded-For` and is still limited (Q-20 rerun) |
| V4.2.1 | 2 | Request smuggling | Platform | Vercel's edge terminates HTTP and forwards to the function; the service never sees raw framing |
| V4.3.1 | 2 | GraphQL cost limits | N/A | No GraphQL |
| V4.3.2 | 2 | GraphQL introspection | N/A | No GraphQL |
| V4.4.1 | 1 | WSS only | Platform | Live updates go through Ably, whose SDKs connect over TLS only (ADR-0003) |
| V4.4.2 | 2 | WebSocket Origin check | Platform | Ably's connection, authorised by a token scoped to one channel, not by origin |
| V4.4.3 | 2 | Dedicated realtime tokens | Met | Ably tokens minted per staff member or guest session, one channel each, short-lived (realtime §8) |
| V4.4.4 | 2 | Realtime token from the HTTPS session | Met | `POST /v1/staff/realtime-token` and `/v1/guest/realtime-token` need the caller's access or session token |

### V5 File Handling
| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V5.1.1 | 2 | Upload rules documented | Met | CR-004 and menu §6: types, 4.5 MB limit, re-encoding. Built with TBD-13 |
| V5.2.1 | 1 | Size limits | Met | Bodies are at most 64 KB (`RequestBodyLimit`); the image route isn't built |
| V5.2.2 | 1 | File type and content checks | N/A | No uploads yet (FR-022 waits for TBD-13); menu §6 plans re-encoding |
| V5.2.3 | 2 | Archives | N/A | No archives accepted |
| V5.3.1 | 1 | Uploaded files never executed | N/A | No uploads yet; images will be served from object storage, not the function |
| V5.3.2 | 1 | File paths from trusted data | Met | The service opens no file by a name from input (only its classpath resources). ZAP rule 6 (ST-11) |
| V5.4.1 | 2 | Download file names | N/A | No file downloads; the staff data export is JSON |
| V5.4.2 | 2 | File names in headers | N/A | None sent |
| V5.4.3 | 2 | Antivirus on uploads | N/A | No uploads yet; re-check with TBD-13 |

### V6 Authentication
| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V6.1.1 | 1 | Documented anti-brute-force | Met | Architecture §7, core-and-auth §8.1 and §11: per-IP limits, per-account lock (10 in 15 min, 15 min), lock not extended while locked, WAF backstop |
| V6.1.2 | 2 | Context-specific word list | Gap | Only the email and its local part are refused (core-and-auth §8.6); no list of the product's and restaurants' names (finding F-6) |
| V6.1.3 | 2 | All auth pathways documented | Met | Staff/admin password (+ admin TOTP), refresh, links, guest QR scan: core-and-auth §8, tables §3 |
| V6.2.1 | 1 | Minimum 8 characters | Met | `PasswordPolicy.MIN_LENGTH = 8` |
| V6.2.2 | 1 | Users can change password | Met | `POST /v1/auth/password` (core-and-auth §8.7) |
| V6.2.3 | 1 | Change needs current and new | Met | `current_password` verified with Argon2id (§8.7) |
| V6.2.4 | 1 | Top-3000 list | Met | 100k-most-used list (SecLists NCSC), 47,324 entries of 8–64 code points (`common-passwords.txt`) |
| V6.2.5 | 1 | No composition rules | Met | Any character, no composition rules (`PasswordPolicy`) |
| V6.2.6 | 1 | Masked password fields | Apps | Web apps (not built) |
| V6.2.7 | 1 | Paste and password managers | Apps | Web apps (not built); the service sets no limits on it |
| V6.2.8 | 1 | Password checked as received | Met | NFC normalisation only, no truncation or case change (§8.6) |
| V6.2.9 | 2 | 64 characters allowed | Met | `MAX_LENGTH = 64` |
| V6.2.10 | 2 | No forced rotation | Met | Passwords don't expire |
| V6.2.11 | 2 | Context words used | Gap | As V6.1.2 (finding F-6) |
| V6.2.12 | 2 | Breached-password check | Partly | The common list holds the passwords most seen in breaches; no check against a full breach corpus (e.g. HIBP's k-anonymity API), which would add an outbound dependency |
| V6.3.1 | 1 | Brute-force controls built | Met | IT-AUTH-02/13 in CI; ST-14 on staging (Upstash, WAF) |
| V6.3.2 | 1 | No default accounts | Met | The first admin comes from the operator command's invitation (restaurants §5); the seed's accounts exist on staging only |
| V6.3.3 | 2 | MFA or combined factors | Deviation | Admins: password + TOTP. Restaurant staff: password only, by requirement (requirements §2, CR-002), on shared restaurant devices. Mitigations: rate limits, lock, 24 h sessions, revocation on the next call. Needs the owner's documented acceptance (Q-21) |
| V6.3.4 | 2 | No undocumented pathways | Met | `endpoint()` authenticates by path group; IT-CORE-10 fails on a route outside the registry or contract |
| V6.4.1 | 1 | Initial secrets short-lived | Deviation | Invitation and reset links: 256-bit random, single use, SHA-256 at rest; they last 72 h and 24 h (core-and-auth §8.5). The link is returned to the inviting manager or admin, who passes it on, which a 10-minute link would rarely survive; shorter needs email delivery (Q-21) |
| V6.4.2 | 1 | No hints or secret questions | Met | None |
| V6.4.3 | 2 | Reset doesn't bypass MFA | Met | A reset keeps the admin's TOTP; signing in still asks for it (core-and-auth §8.5) |
| V6.4.4 | 2 | Lost MFA: proofing as at enrolment | Met | A TOTP reset is done by another admin (or the operator command for the last one), then re-enrolment through a new link, as at onboarding (restaurants §8) |
| V6.5.1 | 2 | TOTP used once | Met | The matched step must exceed `totp_last_step` (core-and-auth §8.2); ST-14 shows a used challenge refused |
| V6.5.2 | 2 | Lookup secrets hashed | N/A | No lookup (recovery) codes |
| V6.5.3 | 2 | TOTP seeds from a CSPRNG | Met | `SecureRandom`, 160 bits, AES-256-GCM at rest (`SecretBox`) |
| V6.5.4 | 2 | Lookup/OOB code entropy | N/A | No lookup or out-of-band codes |
| V6.5.5 | 2 | TOTP lifetime | Met | 30 s steps, ±1 step; the MFA challenge lasts 5 min. No out-of-band codes |
| V6.6.1 | 2 | SMS/phone OTP | N/A | Not offered |
| V6.6.2 | 2 | OOB bound to the request | N/A | No out-of-band authentication |
| V6.6.3 | 2 | OOB brute force | N/A | No out-of-band authentication |
| V6.8.1 | 2 | Multiple IdPs | N/A | No external identity provider (ADR-0011) |
| V6.8.2 | 2 | Signed assertions validated | Met | Our own ES256 JWTs: signature verified before any claim is read (`AccessTokens`, `GuestTokens`; S-4's 19 cases) |
| V6.8.3 | 2 | SAML replay | N/A | No SAML |
| V6.8.4 | 2 | IdP authentication strength | N/A | No external IdP; the admin routes check our own `amr` contains `otp` |

### V7 Session Management
| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V7.1.1 | 2 | Timeouts documented | Met | Architecture §7: access 15 min, refresh 12 h (idle), session 24 h (absolute); guest sessions end with the table session |
| V7.1.2 | 2 | Concurrent sessions documented | Gap | Not documented: a staff member can hold any number of sessions (one per device). Write the rule (finding F-4) |
| V7.1.3 | 2 | Federated sessions | N/A | No SSO or federation |
| V7.2.1 | 1 | Tokens verified by the backend | Met | Every call: signature, then the session row in PostgreSQL (core-and-auth §6) |
| V7.2.2 | 1 | Dynamic tokens | Met | Signed per sign-in; no static API keys for users |
| V7.2.3 | 1 | Reference tokens 128+ bits | Met | Refresh tokens and links: 256 bits from `SecureRandom`, stored as SHA-256 (`SecureTokens`) |
| V7.2.4 | 1 | New session on sign-in | Met | Each sign-in creates a session and a token family; refresh rotates the token |
| V7.3.1 | 2 | Inactivity timeout | Met | A refresh token lasts 12 h; unused, the session can't continue past it |
| V7.3.2 | 2 | Absolute lifetime | Met | 24 h after sign-in (`expires_at`) |
| V7.4.1 | 1 | Termination is final | Met | The per-call session lookup makes a revoked session fail on its next call, not 15 min later (core-and-auth §6) |
| V7.4.2 | 1 | Disabled user's sessions end | Met | Deactivation and erasure revoke every session in the same transaction (restaurants §7, §10) |
| V7.4.3 | 2 | Other sessions end after a factor change | Met | A password change or reset revokes all sessions, this one included; a TOTP reset does too (core-and-auth §8.5, §8.7; restaurants §8) |
| V7.4.4 | 2 | Visible sign-out | Apps | Apps (not built); `POST /v1/auth/sign-out` exists |
| V7.4.5 | 2 | Admins can end a user's sessions | Partly | Only by deactivating, a role change or a reset; no 'sign out everywhere' without changing the account (finding F-4) |
| V7.5.1 | 2 | Re-auth before sensitive changes | Met | A password change needs the current password; staff can't change their own email; TOTP changes go through another admin and a new link |
| V7.5.2 | 2 | Users see and end their sessions | Gap | No session list or per-session sign-out (finding F-4) |
| V7.6.1 | 2 | RP/IdP session lifetimes | N/A | No federation |
| V7.6.2 | 2 | Sessions need an explicit action | Met | A sign-in, or a guest's scan of the table's code |

### V8 Authorization
| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V8.1.1 | 1 | Function and data rules documented | Met | Architecture §7's matrix; each design maps it to its endpoints |
| V8.1.2 | 2 | Field-level rules documented | Met | Per operation: e.g. who may set a role, prices, the owner contact (restaurants §4.2), bill adjustments (orders §7) |
| V8.2.1 | 1 | Function-level access | Met | Role checks in services (`requireRole`, `RestaurantScope`); StaffAccessTest (AT), IT suites |
| V8.2.2 | 1 | Object-level access (IDOR/BOLA) | Met | Every query filters by `RestaurantId` and forced RLS; another restaurant's or session's ID → 404 (IT-CORE-07, IsolationTest/AT-042 on staging) |
| V8.2.3 | 2 | Field-level access (BOPLA) | Met | Each operation has its own body class with only the fields that role may set; unknown fields are ignored, never bound |
| V8.3.1 | 1 | Enforced in the service | Met | All checks server-side; apps only hide screens |
| V8.4.1 | 2 | Cross-tenant controls | Met | RLS forced on every tenant table with one policy (IT-CORE-06), repository filters, tenant transactions (ADR-0005) |

### V9 Self-contained Tokens
| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V9.1.1 | 1 | Signature checked first | Met | java-jwt verifies before claims are used (`AccessTokens`, `GuestTokens`, `QrCodes`) |
| V9.1.2 | 1 | Algorithm allow-list | Met | ES256 only; `none` and HMAC refused (S-4) |
| V9.1.3 | 1 | Keys from trusted config | Met | Keys come from the environment by `kid`; `jku`, `x5u` and `jwk` headers are ignored |
| V9.2.1 | 1 | `exp`/`nbf` checked | Met | `exp` with 30 s leeway (core-and-auth §6) |
| V9.2.2 | 2 | Right token type | Met | Separate key sets and audiences for staff, admin and guest tokens; an MFA challenge isn't an access token (ST-14) |
| V9.2.3 | 2 | `aud` checked | Met | Each route group accepts its audiences only; a staff token on `/v1/admin` → 404 |
| V9.2.4 | 2 | Audience in every token | Met | `aud` = `staff`, `admin` or the guest audience; guest tokens use their own key set |

### V10 OAuth and OIDC
**N/A** for all 29: The service is neither an OAuth client, resource server for third parties, authorisation server nor OIDC provider: first-party apps sign in with built-in accounts (ADR-0011). The nearest controls are V7 and V9: refresh-token rotation with reuse detection, absolute expiry and revocation (core-and-auth §8.3).

V10.1.1, V10.1.2, V10.2.1, V10.2.2, V10.3.1, V10.3.2, V10.3.3, V10.3.4, V10.4.1, V10.4.2, V10.4.3, V10.4.4, V10.4.5, V10.4.6, V10.4.7, V10.4.8, V10.4.9, V10.4.10, V10.4.11, V10.5.1, V10.5.2, V10.5.3, V10.5.4, V10.5.5, V10.6.1, V10.6.2, V10.7.1, V10.7.2, V10.7.3.

### V11 Cryptography
| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V11.1.1 | 2 | Key management policy | Partly | Keys rotate by `kid`/key ID (`EcKeySet`, `SecretBox`) and deploy/README.md has 'Rotating a secret'; no written policy on lifetimes and who may hold each key (see the inventory below; finding F-7) |
| V11.1.2 | 2 | Cryptographic inventory | Met | This document's 'Cryptographic inventory' |
| V11.2.1 | 2 | Validated libraries | Met | Bouncy Castle (Argon2id), the JDK (AES-GCM, ECDSA, SHA-256, `SecureRandom`), java-jwt |
| V11.2.2 | 2 | Crypto agility | Met | Key IDs on JWTs, QR codes and TOTP ciphertexts; PHC strings let Argon2id parameters rise with re-hash at sign-in |
| V11.2.3 | 2 | 128-bit security | Met | P-256, AES-256, SHA-256, 256-bit tokens |
| V11.3.1 | 1 | No ECB or weak padding | Met | AES-GCM only |
| V11.3.2 | 1 | Approved ciphers | Met | AES-256-GCM |
| V11.3.3 | 2 | Authenticated encryption | Met | GCM tag checked on decrypt (`SecretBox`) |
| V11.4.1 | 1 | Approved hashes | Met | SHA-256 for tokens and the audit chain; no MD5 or SHA-1 |
| V11.4.2 | 2 | Password hashing | Met | Argon2id m=19 MiB, t=2, p=1 (OWASP), re-hash when parameters change (architecture §7) |
| V11.4.3 | 2 | Collision-resistant hashes | Met | SHA-256 audit hash chain (core-and-auth §12) |
| V11.4.4 | 2 | Keys from passwords | N/A | No key is derived from a password |
| V11.5.1 | 2 | CSPRNG, 128+ bits | Met | `SecureRandom`, 256-bit tokens; UUID v7 IDs are identifiers, never secrets (authorisation never rests on an ID) |
| V11.6.1 | 2 | Approved key generation and signatures | Met | JDK EC key generation (P-256), ECDSA signatures |

### V12 Secure Communication
| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V12.1.1 | 1 | TLS 1.2/1.3 only | Platform | Vercel's edge terminates TLS (TLS 1.2 and 1.3) |
| V12.1.2 | 2 | Strong cipher suites | Platform | Vercel's edge |
| V12.1.3 | 2 | mTLS client certificates | N/A | None used |
| V12.2.1 | 1 | TLS for all client traffic | Met | HTTPS only; Vercel redirects plain HTTP (V4.1.2) |
| V12.2.2 | 1 | Publicly trusted certificates | Platform | Vercel-issued certificates for `*.vercel.app`; a custom domain gets one too |
| V12.3.1 | 2 | TLS for every outbound connection | Partly | Upstash, Ably and the OTLP endpoint over HTTPS; PostgreSQL over TLS (`sslmode=require`), but see V12.3.2 |
| V12.3.2 | 2 | TLS clients validate certificates | Gap | The database URLs use `sslmode=require`, which in pgjdbc encrypts but doesn't check the server's certificate or name (finding F-1). The HTTPS clients validate (JDK defaults) |
| V12.3.3 | 2 | TLS between internal services | Met | No internal HTTP services; the function talks only to the providers above |
| V12.3.4 | 2 | Trusted certificates internally | Partly | As V12.3.2 for the database; the rest use publicly trusted CAs |

### V13 Configuration
| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V13.1.1 | 2 | Communication needs documented | Met | Architecture §8 (deployment) and deploy/README.md 'What the service needs' |
| V13.2.1 | 2 | Backend auth without static credentials | Partly | The database roles, Upstash and Ably use long-lived passwords or keys (the free plans offer nothing else); each is its own, rotatable (deploy/README.md) |
| V13.2.2 | 2 | Least privilege | Met | `app` (RLS, no DDL), `app_platform` (BYPASSRLS, admin work only), `migrator` (DDL, CI only); the Ably key publishes and subscribes only, and the tokens it mints narrow it to one channel |
| V13.2.3 | 2 | No default credentials | Met | Every secret is generated (`generateSecrets`) |
| V13.2.4 | 2 | Outbound allow-list | Partly | The hosts are fixed by configuration; Vercel functions have no egress firewall, so nothing at the network layer enforces it |
| V13.2.5 | 2 | Server allow-list | Partly | As V13.2.4 |
| V13.3.1 | 2 | Secrets management | Partly | Vercel's encrypted environment variables in production, a git-ignored owner-only file locally; no vault (free plans). No secret in source or the image |
| V13.3.2 | 2 | Least-privilege access to secrets | Met | Only the owner can read the Vercel project's variables; the auto-mode check stops Claude writing them |
| V13.4.1 | 1 | No source control metadata | Met | The image holds the jar only (`Dockerfile.vercel`). ZAP rules 40034/40035 (ST-11) |
| V13.4.2 | 2 | Debug off in production | Met | Ktor development mode off; no debug endpoints; logs at INFO |
| V13.4.3 | 2 | No directory listings | Met | No static file serving |
| V13.4.4 | 2 | No TRACE | Met | Only the contract's methods are routed: `TRACE /v1/auth/me` answered `405` on staging (ST-11, run 2's setup) |
| V13.4.5 | 2 | No exposed docs or monitoring | Met | Only `GET /health` (status only); metrics are pushed (OTLP), the contract isn't served |

### V14 Data Protection
| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V14.1.1 | 2 | Sensitive data classified | Partly | Architecture §11 and requirements §5 list the personal data and secrets; protection levels per type wait for TBD-10's legal review |
| V14.1.2 | 2 | Protection requirements per level | Partly | Retention (core-and-auth §10, CR-005), logging rules (architecture §11), RLS; formal levels with TBD-10 |
| V14.2.1 | 1 | No secrets in URLs | Met | Tokens travel in headers or bodies; link and QR tokens are POSTed (`/v1/auth/links/inspect` is a POST so the token never sits in a URL). The QR code's own URL holds its token, by design: it is the table's printed credential |
| V14.2.2 | 2 | No caching of sensitive data | Met | `Cache-Control: no-store` on every `/v1/` response but the menu reads (`private, no-cache`); Vercel's CDN never caches a request with `Authorization` |
| V14.2.3 | 2 | No data to trackers | Met | The service calls no analytics or trackers |
| V14.2.4 | 2 | Controls per protection level | Partly | Built: no personal data in logs (`LogPrivacyTest`, ST-15), RLS, erasure (ST-15); the per-level matrix waits for TBD-10 |
| V14.3.1 | 1 | Client data cleared on sign-out | Apps | Apps (not built); the service clears the refresh cookie |
| V14.3.2 | 2 | Anti-caching headers | Met | As V14.2.2 |
| V14.3.3 | 2 | No sensitive data in browser storage | Apps | Apps (not built); the refresh token is an HttpOnly cookie |

### V15 Secure Coding and Architecture
| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V15.1.1 | 1 | Remediation time frames | Gap | No written time frames for vulnerable dependencies (finding F-3) |
| V15.1.2 | 2 | SBOM | Gap | No SBOM; versions are pinned in `gradle/libs.versions.toml` from Maven Central (finding F-3) |
| V15.1.3 | 2 | Resource-heavy functions documented | Met | Argon2id (4 slots, 2 s wait → 503), reports (10 s statement timeout, 31 days), architecture §9–10 |
| V15.2.1 | 1 | Components within time frames | Gap | Unknown: no dependency scan has run (security-review.md §4; finding F-3) |
| V15.2.2 | 2 | Availability defences | Met | Argon2id slots, statement timeouts, 64 KB bodies, page limits ≤ 200, rate limits |
| V15.2.3 | 2 | No extraneous functionality | Met | Test fakes live in the test source sets; the seed and ZAP tools in `systemTest`, never in the jar |
| V15.3.1 | 1 | Only required fields returned | Met | Explicit response classes per operation; no password hashes, token hashes or TOTP secrets leave the service (contract check on every response) |
| V15.3.2 | 2 | Outbound calls don't follow redirects | Met | The JDK `HttpClient` (Upstash) never follows redirects by default; the Ably and OTLP clients call fixed endpoints |
| V15.3.3 | 2 | Mass assignment | Met | One body class per operation; unknown fields ignored, never bound |
| V15.3.4 | 2 | Client IP from a trusted source | Met | `x-real-ip` from Vercel's edge (`requestMeta`); ST-14 shows a spoofed header changes nothing |
| V15.3.5 | 2 | Strict types | Met | Kotlin's static types; inputs parsed into typed values |
| V15.3.6 | 2 | Prototype pollution | N/A | No JavaScript in the service |
| V15.3.7 | 2 | HTTP parameter pollution | Met | Ktor keeps query, body and headers apart; a repeated query parameter's first value is used and a repeated JSON key's last, each parsed in one place only (probe, 2026-10-18) |

### V16 Security Logging and Error Handling
| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V16.1.1 | 2 | Log inventory | Partly | Architecture §11 and §13 (application logs to Vercel, the audit log in PostgreSQL, metrics to Grafana); how long each is kept is in this document's inventory, not in §13 |
| V16.2.1 | 2 | When/where/who/what | Met | JSON lines with time, logger, `request_id`; audit events with actor, action, target, IP and request ID (core-and-auth §12) |
| V16.2.2 | 2 | UTC timestamps | Met | Logstash encoder timestamps with offset; audit `occurred_at` in UTC |
| V16.2.3 | 2 | Logs only where documented | Met | stdout (Vercel), the audit table, OTLP metrics |
| V16.2.4 | 2 | Machine-readable logs | Met | One JSON object per line (`logback.xml`); `deploy/staging/log-review.py` reads them |
| V16.2.5 | 2 | Sensitive data kept out of logs | Met | No personal data, secrets or tokens in logs; SQL values kept out (`LogPrivacyTest`, ST-15's review) |
| V16.3.1 | 2 | Authentication events logged | Met | Audit events for sign-in success and failure, MFA, refresh reuse, sign-out, password changes, links (core-and-auth §12) |
| V16.3.2 | 2 | Failed authorisation logged | Partly | Each `403` and `404` appears as a request line with its status and request ID, but without the caller; they aren't audit events |
| V16.3.3 | 2 | Bypass attempts logged | Partly | Refresh-token reuse and account locks are audited; rate-limited (`429`) and invalid input (`400`) only as request lines |
| V16.3.4 | 2 | Unexpected errors logged | Met | 500s logged with the exception, values stripped (`ExposedTransactor.translate`) |
| V16.4.1 | 2 | Log injection | Met | The JSON encoder escapes every value |
| V16.4.2 | 2 | Logs protected | Met | Vercel's logs are read-only to the project's members; the audit log can't be updated or deleted by the service's roles and is hash-chained (ST-13) |
| V16.4.3 | 2 | Logs sent to a separate system | Platform | On Hobby, Vercel keeps runtime logs for 1 hour and log drains need a paid plan. The audit log is in the database. Production needs a drain to Grafana Cloud (R-17 to be raised) |
| V16.5.1 | 2 | Generic error messages | Met | problem+json with a stable `code`, never a stack trace (core-and-auth §3); ZAP's passive rules (application error disclosure, 90022) found none in about 14,000 requests (ST-11) |
| V16.5.2 | 2 | Degrades safely when a dependency fails | Met | Upstash down → limits fail open, the WAF still applies; Ably down → the outbox catches up; database down → 503 (ADR-0003, ADR-0008) |
| V16.5.3 | 2 | Fails closed | Met | Errors abort the transaction; auth fails to 401. The one fail-open (rate limits when Redis is down) is deliberate and backed by the WAF |

### V17 WebRTC
**N/A** for all 7: No WebRTC, TURN or media servers.

V17.1.1, V17.2.1, V17.2.2, V17.2.3, V17.2.4, V17.3.1, V17.3.2.
