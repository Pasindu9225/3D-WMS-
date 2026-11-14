# Decisions log

| Date | Decision | Reason |
| --- | --- | --- |
| 2026-11-13 | MySQL 9.7 LTS (8.4 LTS also OK) instead of PostgreSQL | Owner preference; tenant isolation done in the app (SEC-24) |
| 2026-11-13 | English only UI, text in en.json | No Sinhala/Tamil needed |
| 2026-11-13 | Pricing per warehouse | Owner decision |
| 2026-11-13 | No ERP/accounting integration in v1 | Not needed by customers |
| 2026-11-13 | Phone camera scanning only | No hardware scanners |
| 2026-11-13 | No Redis; Caffeine + Bucket4j | Must run on low-spec computers (4 GB RAM) |
| 2026-11-13 | Modular monolith, Spring Boot 4.1 + React 19 | Simple for one developer |
| 2026-11-13 | Java 17 LTS instead of Java 21; Spring Boot 4.1 kept | Owner prefers stable tech; Boot 4.1 runs on Java 17 and still gets security patches |
| 2026-11-13 | MySQL 8.4 LTS (not 9.7) in its own container `wms-mysql`, port 127.0.0.1:3308 | More mature; never share the bakery BMS test container |
| 2026-11-13 | Own image `deploy/mysql/Dockerfile` (Debian 12 + Oracle APT MySQL 8.4) instead of `mysql:8.4` | Official 8.4 image needs x86-64-v2 CPU; server is a basic QEMU vCPU. 8.0-debian is EOL (Apr 2026) |
| 2026-11-13 | DB users `wms_flyway` (schema) and `wms_app` (data only); ledger UPDATE/DELETE blocked by triggers | SEC-27; MySQL db-level grants cannot exclude single tables |
| 2026-11-13 | Tenant via Hibernate `@TenantId` + `TenantContext`; no tenant = fail closed (-1), root (0) only via `callAsSystem` | Repos need no tenant parameter (`findByPublicId`); login/refresh lookups happen before the tenant is known |
| 2026-11-13 | Built-in roles and permissions are global tables; `user_role` carries tenant_id | Spec has no per-tenant custom roles; keeps seeding simple |
| 2026-11-13 | `login_attempt` stores SHA-256 of the email, not the email | Users sometimes type their password into the email field |
| 2026-11-13 | JWT `tid` = internal tenant ID; permissions NOT in the JWT, loaded with the session check (60 s cache) | No extra lookup per request; role changes apply within 60 s instead of 15 min |
| 2026-11-13 | Cookies `__Host-wms_at` (Path=/) and `__Secure-wms_rt` (Path=/api/v1/auth) | Browser-enforced Secure/no-Domain; refresh token never sent to normal API calls |
| 2026-11-13 | Lockout: 5 failures per account (15 min, doubling, max 24 h); 20 failures per IP per 15 min | Offices share one NAT IP, so the IP limit must be higher than the account limit |
| 2026-11-13 | Argon2id via BouncyCastle 1.86 (`bcprov-jdk18on`) | Spec names Argon2PasswordEncoder; library only, no new server |
| 2026-11-13 | Logout accepts either cookie; never fails | A user with an expired access token can still log out |
| 2026-11-13 | CSRF via Spring Security `csrf().spa()`: readable `XSRF-TOKEN` cookie (Secure, SameSite=Strict) + `X-XSRF-TOKEN` header; `GET /api/v1/auth/csrf` primes it | Standard double-submit for a cookie-auth SPA; failures return `CSRF_INVALID` so the app can refetch |
| 2026-11-13 | Login limit applies to `POST /auth/login` only; refresh counts toward the global per-IP limit | Each open tab refreshes every 15 min; counting refreshes as logins would lock out shared office IPs |
| 2026-11-13 | `/actuator/health` allowed only from 127.0.0.1 / ::1 (same port) | SEC-64 without a second management port; Nginx must not forward /actuator and must set forwarded headers |
| 2026-11-13 | JSON bodies capped at 1 MB; chunked bodies without Content-Length refused (411) | The cap must be checked before reading; browsers always send a length |
| 2026-11-13 | Bucket4j 8.21.0 (local buckets in Caffeine) | SEC-60 without Redis; per-JVM counts are fine for one server |
| 2026-11-13 | 8 built-in roles and 28 permissions seeded in V2 (matrix approved by owner) | Stable role definitions; later versions only use the permissions, no reseeding |
| 2026-11-13 | Invites are one-time links (72 h, token hashed, token in the URL #fragment) that the admin sends; no email server | Fragments never reach servers, logs or Referer; email comes with v2.0 sign-up |
| 2026-11-13 | First company and admin via `--wms.bootstrap.*` command line run, link printed to console once | No public sign-up until v2.0; refuses to run twice for the same email |
| 2026-11-13 | Email uniqueness enforced by the DB unique key (409 EMAIL_IN_USE) | The tenant filter hides other companies' emails; the key sees all and cannot race |
| 2026-11-13 | Removing or disabling an admin locks all active admins first | Two admins removing each other at once can never leave a company without an admin |
| 2026-11-13 | Frontend: Vite 7, React 19.1, React Router 7, TanStack Query 5, Tailwind 4, TypeScript 5.9, Vitest 4, ESLint 10 | Mature majors, not the newest (Vite 8, Router 8, TS 6/7 skipped); ESLint 9 is end-of-life so 10 is used |
| 2026-11-13 | Zustand, React Hook Form and Zod added when first used (task 8), not in the skeleton | No unused dependencies |
| 2026-11-13 | Dev server on port 5180 with /api proxied to :8080 | Same origin as production (cookies + CSRF need no CORS); 5173 is used by other projects on the dev PC |
| 2026-11-13 | API client: one shared refresh for parallel 401s; one CSRF re-fetch on CSRF_INVALID | Two parallel refreshes would trip refresh-token reuse detection and log the user out |
| 2026-11-13 | Theme set by an external `theme-init.js` before first paint | Inline scripts are blocked by the CSP |
| 2026-11-13 | Reverse proxies must keep the browser's Host header (Vite `changeOrigin: false`; Nginx `proxy_set_header Host $host`) | With a rewritten Host, Spring sees Origin ≠ host and rejects every POST as an invalid CORS request |
| 2026-11-13 | Signed-in state = the `/auth/me` query (TanStack Query); no Zustand yet | One source of truth; Zustand added when real client-only state appears |
| 2026-11-13 | Invite page reads the token from `#fragment` and clears it from the address bar at once | Keeps the one-time token out of history, screenshots and shared links |
| 2026-11-13 | Built-in roles shown as a read-only permission matrix | Roles are fixed (task 6); people get several roles instead of custom ones |
| 2026-11-13 | Dependency scan with OSV-Scanner (backend CycloneDX SBOM + npm lockfile) and `npm audit --audit-level=high`, not OWASP Dependency-Check | Dependency-Check needs an NVD API key or downloads the whole NVD database on every run (slow, rate-limited); OSV needs no key. Switch back if an NVD key is provided |
| 2026-11-13 | CI actions pinned to commit SHAs; versions about a month old | A moved or hijacked tag cannot run new code in CI; stable over newest |
| 2026-11-13 | `backend/mvnw` stored with the executable bit (git update-index --chmod=+x) | Commits from Windows drop it; Linux CI could not run the Maven wrapper |
| 2026-11-13 | npm `overrides`: minimatch ^11.0.1 | Fixes GHSA-g3hv-75wg-m249 (ReDoS) in ESLint's dependency chain; lint verified working; npm audit now 0 findings |
| 2026-11-13 | Behind a proxy, client IP from X-Forwarded-For trusted only from loopback | Spring's default trusts all private LAN ranges; an office PC could forge its IP (review F1) |
| 2026-11-13 | Sessions end 30 days after login; Argon2 at 19 MiB / 2 iterations; IPv6 counted per /64 | Review F3, F4, F7 |
| 2026-11-13 | `app_user.password_set_at` (V3) and a nightly cleanup (30-day retention, audit log excluded) | Review F6; cleanup also removes old client IPs (personal data) |
| 2026-11-13 | Suspended companies refused at login, refresh and session check | Review F8; the suspend screen itself comes in v2.0 |
| 2026-11-13 | v0.2 plan approved: tenant admins see all warehouses, others only assigned; first warehouse (and invites while only one exists) open to everyone; creator auto-assigned | One-warehouse pilot needs no setup; 404 (not 403) for warehouses you cannot see |
| 2026-11-13 | Layout drafts are one JSON document per warehouse; publish applies it to the zone/rack/level/bin tables (the published layout that stock and 3D use) | Bins referenced by stock stay stable; the floor never sees half-finished edits |
| 2026-11-13 | Bin labels with ZXing (QR) + OpenPDF, not bwip-js | bwip-js is a JavaScript library; the server is Java |
| 2026-11-13 | Warehouse create locks the company row to check the plan limit; archive = soft delete that frees a slot | No race past the limit; archived warehouses keep their history |
| 2026-11-13 | Assigning users to a warehouse needs `users:manage` | It decides who sees what: a user-management act, not a layout one |
| 2026-11-13 | Layout document in millimetres; rack position = its centre; rotation in 90° steps | No rounding between editor, server and 3D; rotating a rack does not move it |
| 2026-11-13 | Layout checks: errors (outline crosses itself, outside floor/zone, overlaps, duplicate codes, unknown type/zone, door not on wall) refuse the save; narrow aisles only warn (minimum per layout, default 1.2 m; back-to-back racks not flagged) | Planners may want tight aisles for hand picking; overlaps are never valid |
| 2026-11-13 | Draft saves carry `baseRevision`; a stale save gets 409 LAYOUT_CHANGED | Two planners editing at once cannot silently overwrite each other |
| 2026-11-13 | Validation errors list `{field, message}` also when Spring validates the whole method (constrained path variable) | Bug found in task 2: those endpoints (incl. PATCH /users/{id}) returned no field list |
| 2026-11-13 | Publish matches zones/racks by code, levels by number, bins by position: an existing bin keeps its id (and later its stock) when its rack moves; only its code changes | Stock and history must survive layout edits; labels are reprinted after a rename |
| 2026-11-13 | Bin capacity from the rack type: level weight / bins per level; volume = bin width x depth x level height. A hand-edited bin keeps its values on later publishes | Sensible defaults with room for real-world exceptions |
| 2026-11-13 | Publish parks changing bin codes on a temporary value first | Codes can move between bins without breaking the unique key mid-publish |
| 2026-11-13 | Measured: 100 racks / 5,000 bins first publish 3.9 s, republish 0.2 s, draft check 0.1 s (local MySQL) | IDENTITY keys mean one insert per new bin; fine at this size |
| 2026-11-13 | Labels: A4 3x8 (70x37 mm) sheets and 100x50 mm singles; QR (ZXing 3.5.4, ECC M) holds exactly the printed bin code; vector QR via OpenPDF 2.0.5 | Typed fallback matches the scan; crisp print, small files (5,000 labels < 3 MB) |
| 2026-11-14 | OpenPDF 2.0.5 (not 3.x / 2.1+); build fails if any runtime library targets newer than Java 17 (enforcer: enforceBytecodeVersion) | OpenPDF 2.1+ is compiled for Java 21; it compiled on a JDK 25 dev PC but broke CI on JDK 17 and would have broken the server |
| 2026-11-14 | react-konva 19.0.10 + Konva 10.7; editor state in a Zustand store; Konva only in the editor chunk (~100 KB gzip, lazy) | 19.2+ needs React 19.2/19.3; initial bundle unchanged (158 KB) |
| 2026-11-14 | Editor: corners snap to 0.1 m; doors/docks always snap onto the outer wall; labels upright and nudged inward | Server rules (on-wall, whole mm) hold by construction; readable on every wall |
| 2026-11-14 | Floor shapes: L/U/T presets keep the current bounding box; free outline drawn by clicks (Enter/double-click finish, Esc cancel), refused if edges cross; corner table in metres for measured buildings | Real warehouses are rarely rectangles; presets are fast, the table is exact, the server check stays the safety net |
