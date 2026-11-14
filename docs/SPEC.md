# 3D Warehouse System — Claude Code Build Spec

Spec date: 13 Nov 2026 · Owner: Pasindu

## How to use this spec

This is the single source of truth for building the 3D Warehouse System. Save it in the repo as `docs/SPEC.md` and tell Claude Code to read it, plus `CLAUDE.md`, at the start of every session.

**Rules for Claude Code**

1. Build one version at a time, in order (v0.1 → v2.0). Do not start a version until the previous one meets its exit criteria.
2. Inside a version, do one task per session. Show a plan first; write code only after the plan is approved.
3. Every task ends with tests passing and the security checklist items for that task ticked.
4. Never weaken a security rule in section "Security requirements" to make something easier. If a rule blocks you, stop and ask.
5. Never add a heavy dependency (new server, message queue, big library) without asking. The system must run on low-spec computers.
6. Keep English as the only UI language, but put all UI text in one `en.json` file so it is easy to change later.
7. When something in this spec is unclear or wrong, write the question in `docs/questions.md` and ask, do not guess.

## Decisions

These decisions are fixed for this build. Change them only by updating this table.

| Topic | Decision | Effect on the build |
| --- | --- | --- |
| Database | MySQL 8.4 LTS, own container `wms-mysql` | Mature LTS line; separate from the bakery BMS container. MySQL has no row level security, so tenant isolation is done in the app (see Security). |
| Language | English only | No translation work. All UI text still lives in one `en.json` file. |
| Pricing | Per warehouse, per month | The system counts active warehouses per tenant; plan limits block creating more warehouses than paid for. |
| ERP / accounting links | None in v1 | No integration module until v2. CSV import/export covers data entry. |
| Scanning | Phone camera only | Browser camera scanning in the PWA (zxing-js). Manual code entry as backup. No Bluetooth scanner support needed. |
| Pilot customer | Not found yet | See "Finding a pilot customer" below. Build v0.1 to v0.4 meanwhile; a pilot is needed before v1.0 is called done. |
| Hardware target | Low-spec computers | Server and browser budgets below. No Redis, no Kafka, no Elasticsearch, no Kubernetes. |

**Low-spec targets**

| Part | Minimum it must run on | Budget |
| --- | --- | --- |
| Server (whole stack: Java + MySQL + Nginx) | 2 CPU cores, 4 GB RAM, 20 GB disk | App JVM heap max 512 MB; MySQL buffer pool 512 MB; total under 1.5 GB RAM at idle |
| Admin PC browser | Dual-core laptop, 4 GB RAM, integrated graphics, Chrome or Edge | 3D view at 30+ fps with 5,000 bins; first page load under 3 s on 10 Mbps |
| Worker phone | Android phone with 2 GB RAM, Chrome | Scan screen opens under 2 s; JS for worker app under 250 KB gzipped |

**Finding a pilot customer** (answer to open question 1)

- Start with people you already know: your bakery customer's suppliers and distributors, family business contacts, university lecturers in logistics.
- Target small warehouses that use Excel today: hardware wholesalers, pharmacy distributors, spare parts shops, small e-commerce sellers.
- Offer the pilot free for 3 to 6 months in exchange for weekly feedback and a testimonial.
- One pilot with one warehouse and 20 to 200 racks is enough to prove v1.0.

## Version plan at a glance

The MVP (v0.1 to v1.0) takes about 20 weeks part-time; the full SaaS release lands around week 34. Weeks are estimates for one developer working with Claude Code; re-plan after v0.1.

| Version | Weeks (estimate) | Scope |
| --- | --- | --- |
| v0.1 Foundation | 1–3 | Repo, CI, MySQL, secure login, users and roles, audit log |
| v0.2 Layout builder | 4–7 | Floor, zones, rack templates, racks, auto bins, QR labels |
| v0.3 3D view | 8–10 | Instanced 3D bins, search and fly-to, Lite 2D mode, live updates |
| v0.4 Inbound and stock | 11–14 | Products, POs, phone receiving, putaway, stock ledger, transfers |
| v1.0 MVP and pilot | 15–20 | Orders, picking, packing, dispatch, dashboard, HTTPS deploy |
| **Gate** | — | Pilot runs 2 weeks with no stock mismatch |
| v1.1 Counts and expiry | 21–24 | Batch and expiry, FEFO, cycle counts, returns, quarantine |
| v1.2 Smarter picking | 25–28 | Batch picking, 3D routes, replenishment, rules editor, heat map |
| v2.0 SaaS release | 29–34 | Sign-up, per-warehouse billing, public API, on-prem installer |

v1.1 and later start only after the pilot gate passes; until a pilot customer is found, keep building v0.1 to v0.4.

## Tech stack and project structure

One Spring Boot backend (modular monolith) and one React frontend that serves both the admin web app and the worker PWA. Nothing else needs to run except MySQL and Nginx.

| Layer | Choice | Low-spec note |
| --- | --- | --- |
| Language | Java 17 LTS | Stable LTS by owner choice; no virtual threads (platform thread pool is enough at this scale) |
| Backend | Spring Boot 4.1: Web, Data JPA, Security, Validation, WebSocket, Actuator | Spring Boot 4 split into small modules, so include only the starters we need |
| ORM | Hibernate 7 (via JPA) | No native SQL with string concatenation, ever |
| Database | MySQL 8.4 LTS, InnoDB, utf8mb4 | `innodb_buffer_pool_size=512M`, `max_connections=50` |
| Migrations | Flyway | Runs with a separate DB user that can change schema |
| Cache | Caffeine (in memory) | Replaces Redis |
| Rate limiting | Bucket4j with Caffeine backend | Replaces Redis-based limiter from the security document |
| JWT | Nimbus JOSE (bundled with Spring Security) | RS256 keys loaded from files |
| Mapping | MapStruct; Java records for DTOs | No Lombok on DTOs, records are enough |
| API docs | springdoc-openapi | Disabled in production |
| Frontend | React 19, TypeScript strict, Vite | Code-split by route |
| 3D | three.js + React Three Fiber 9 + drei | Loaded only on 3D pages |
| 2D floor editor | react-konva | Light canvas library |
| UI | Tailwind CSS + shadcn/ui, light and dark theme | No big component framework |
| Data and state | TanStack Query, Zustand |  |
| Forms | React Hook Form + Zod | Zod is UX only; the server validates again |
| Barcode | @zxing/browser (camera) + `bwip-js` on server for label PDFs |  |
| PDF / Excel export | OpenPDF, Apache POI (streaming SXSSF) | Stream large exports, never build them in memory |
| Tests | JUnit 5, Testcontainers (MySQL), ArchUnit, Vitest, Playwright |  |
| Deploy | One Linux VPS or one office PC: Nginx + Java jar + MySQL; Docker Compose optional | Systemd service files provided |

**Low-spec rules (Claude Code must follow)**

- Always paginate lists (default 25, max 100 rows). No endpoint returns an unbounded list.
- Every foreign key and every `tenant_id` column is indexed; check slow queries with `EXPLAIN`.
- Use DTO projections for list screens, not full entities; avoid N+1 queries (`@EntityGraph` or fetch joins).
- 3D: instanced meshes, no shadows, no post-processing, device pixel ratio capped at 1.5, render on demand only.
- A "Lite mode" toggle shows a 2D top-down floor map instead of 3D for very weak PCs.
- Frontend bundle budgets checked in CI: admin initial JS under 400 KB gzipped, worker app under 250 KB.
- Images resized on upload to max 1024 px; thumbnails 200 px.

**Repository structure**

```
warehouse/
  CLAUDE.md
  docs/            SPEC.md, questions.md, decisions.md, security-checklist.md
  backend/
    src/main/java/lk/wms/
      common/      config, security, errors, tenant, audit, storage
      auth/        login, tokens, sessions, users, roles
      tenant/      companies, plans, warehouse limits
      layout/      warehouses, zones, racks, levels, bins, labels
      catalog/     products, uom, batches, partners
      inbound/     purchase orders, receipts, putaway
      inventory/   stock, movements, transfers, adjustments, counts
      outbound/    sales orders, picks, packing, dispatch
      task/        worker tasks
      report/      dashboard, exports
    src/main/resources/db/migration/   Flyway SQL files
  frontend/
    src/app/       routes (admin/*, worker/*)
    src/features/  one folder per backend module
    src/three/     3D scene, instanced bins, camera
    src/lib/       api client, auth, scanner, i18n (en.json)
  deploy/          nginx.conf, systemd units, docker-compose.yml, backup.sh
```

Each backend module has the same layers: `api` (controllers + DTOs), `service`, `domain` (entities), `repo`. Modules talk to each other only through service interfaces, enforced by ArchUnit tests.

## Security requirements

All security rules come from *The Secure Backend Master-Vault* (Sithara Sangeeth), adapted from its Node.js / Prisma / PostgreSQL / S3 examples to Spring Boot, MySQL and a single low-spec server. Its golden rule applies to every task: never trust the client, never trust the AI's first draft, never deploy without verification.

Each rule has an ID. Claude Code must name the IDs it implements in each pull request. The "From" column shows the version where the rule must first be in place.

### Authentication and access control

| ID | Rule | How we build it | From |
| --- | --- | --- | --- |
| SEC-01 | Tokens only in HTTP-only cookies, never localStorage or sessionStorage | Access cookie `HttpOnly; Secure; SameSite=Lax; Path=/`; refresh cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` | v0.1 |
| SEC-02 | Access token: RS256 JWT, 15 minutes, minimal claims | Claims: `sub` (userId), `tid` (tenantId), `sid` (sessionId), `roles`. Keys from files, never in Git | v0.1 |
| SEC-03 | Refresh token: random opaque string, 7 days, stored hashed (SHA-256) | Table `refresh_token` with `family_id`, `session_id`, `expires_at`, `revoked_at` | v0.1 |
| SEC-04 | Rotate refresh token on every use; reuse of an old token revokes the whole family | Detect reuse → revoke family → audit log `TOKEN_REUSE` | v0.1 |
| SEC-05 | Session check on every request | `sid` must exist in `user_session` and not be revoked (cached 60 s in Caffeine) | v0.1 |
| SEC-06 | Lockout after 5 failed logins, 15 minutes, growing backoff | Counter per user and per IP | v0.1 |
| SEC-07 | Generic error messages | Always "Authentication failed"; never say whether the user exists | v0.1 |
| SEC-08 | Logout revokes session and token family; clear both cookies | Also "log out all devices" | v0.1 |
| SEC-09 | Role and permission check on every endpoint; deny by default | `@PreAuthorize` with permission names; ArchUnit test fails if a controller method has none | v0.1 |
| SEC-10 | No IDOR: every lookup by ID also checks tenant and warehouse access | Tenant entities use `@TenantId` (auto filter); services load by `findByPublicId`; never plain `findById` (ArchUnit) | v0.1 |
| SEC-11 | CSRF protection for cookie auth | SameSite plus Spring Security CSRF token (cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`) | v0.1 |

### Database

| ID | Rule | How we build it | From |
| --- | --- | --- | --- |
| SEC-20 | Validate all input on the server | Request DTOs as Java records with Bean Validation (`@NotBlank`, `@Size`, `@Pattern`, `@Positive`); length limits on every string | v0.1 |
| SEC-21 | Unknown JSON fields rejected | Jackson `FAIL_ON_UNKNOWN_PROPERTIES=true` (the Java version of Zod strict mode) | v0.1 |
| SEC-22 | No mass assignment | Never bind requests to entities; map DTO → entity field by field (MapStruct with explicit mappings) | v0.1 |
| SEC-23 | Parameterised queries only | JPA / JPQL with parameters; native queries only with `:params`, reviewed; ArchUnit blocks string-built queries | v0.1 |
| SEC-24 | Tenant isolation (replaces PostgreSQL RLS, which MySQL lacks) | Hibernate `@TenantId` on every tenant entity, tenant resolved from the JWT; integration tests that try cross-tenant reads and writes and must fail | v0.1 |
| SEC-25 | Password and token hashes never leave the auth module | Separate projection DTOs; `password_hash` never in any response | v0.1 |
| SEC-26 | Transactions for multi-step stock changes | `@Transactional` + `SELECT ... FOR UPDATE` on stock rows; optimistic `@Version` on documents | v0.4 |
| SEC-27 | Least-privilege DB users | App user: SELECT/INSERT/UPDATE/DELETE only. Flyway user: schema changes. No root from the app | v0.1 |
| SEC-28 | Audit log on all changes | Who, what, before/after JSON, time, IP, user agent, correlation ID | v0.1 |
| SEC-29 | Passwords hashed with Argon2id (or BCrypt cost 12) | Spring Security `Argon2PasswordEncoder` | v0.1 |

### File uploads (product images, logos, CSV imports)

| ID | Rule | How we build it | From |
| --- | --- | --- | --- |
| SEC-40 | Allow-list file types and size per type | Images JPEG/PNG/WebP max 5 MB; CSV max 10 MB; everything else rejected | v0.4 |
| SEC-41 | Check magic bytes and match them with Content-Type and extension | Apache Tika detection; reject on any mismatch | v0.4 |
| SEC-42 | Block SVG and HTML | Not in allow-list | v0.4 |
| SEC-43 | Re-encode every image, never serve the original | Decode with ImageIO and write a new JPEG/WebP (strips EXIF and hidden payloads); delete original | v0.4 |
| SEC-44 | Random file names, stored outside the web root | `{tenantId}/{uuid}.jpg` on local disk (MinIO/S3 optional later); served by a controller that checks tenant, with `Content-Disposition` and `nosniff` | v0.4 |
| SEC-45 | Upload rate limit per user | 20 uploads per minute | v0.4 |
| SEC-46 | CSV import safety | Max 5,000 rows per file; escape cells starting with `= + - @` on export (formula injection) | v0.4 |

### Infrastructure

| ID | Rule | How we build it | From |
| --- | --- | --- | --- |
| SEC-60 | Rate limits | Global 1,000 req/min per IP; auth 10 req/min per IP; user API 100 req/min (worker scan endpoints 300); 429 with `Retry-After` | v0.1 |
| SEC-61 | Security headers | CSP (no inline scripts), HSTS 1 year, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy: camera=(self)` | v0.1 |
| SEC-62 | CORS allow-list, no wildcard | Only our own domains; credentials allowed | v0.1 |
| SEC-63 | Global exception handler: generic errors, no stack traces | `ProblemDetail` with a correlation ID; full error only in server logs | v0.1 |
| SEC-64 | Hide server information | No `Server` / `X-Powered-By`; Nginx `server_tokens off`; Actuator only on localhost | v0.1 |
| SEC-65 | Request limits | JSON body max 1 MB; upload endpoints their own limits; request timeouts | v0.1 |
| SEC-66 | HTTPS everywhere | Let's Encrypt on Nginx; HTTP redirects to HTTPS | v1.0 |
| SEC-67 | Graceful shutdown and 404 for unknown routes | `server.shutdown=graceful` | v0.1 |

### Operations

| ID | Rule | From |
| --- | --- | --- |
| SEC-80 | Secrets only in environment variables or files outside Git; `.env` in `.gitignore`; gitleaks in CI | v0.1 |
| SEC-81 | Dependency scan in CI (OWASP Dependency-Check, `npm audit`), fail on high severity | v0.1 |
| SEC-82 | Pre-release AI security review of the whole diff, then a manual check against this list | Every version |
| SEC-83 | Daily encrypted MySQL backups kept 30 days; restore tested monthly | v1.0 |
| SEC-84 | Incident response notes: how to revoke all sessions, rotate keys, restore backup | v1.0 |
| SEC-85 | Penetration test (OWASP ZAP baseline scan) before pilot go-live | v1.0 |

## Version-by-version plan

Eight versions take the product from an empty repo to a sellable SaaS. Versions v0.1 to v1.0 are the MVP; v1.0 goes to the pilot customer. Each version lists its goal, tasks (tick them off as you go), deliverables and exit criteria.

### v0.1 — Foundation and security core

**Goal:** a secure, empty shell: companies can exist, users can log in, roles work, and every security base rule is in place before any business feature.

**Tasks**

- [x] Create monorepo, `CLAUDE.md`, `docs/SPEC.md`, `.gitignore`, `.editorconfig`
- [x] Backend skeleton: Spring Boot 4.1, Java 17, module packages, ArchUnit rules
- [x] MySQL + Flyway: `tenant`, `app_user`, `role`, `permission`, `user_role`, `user_session`, `refresh_token`, `audit_log`, `login_attempt`
- [x] Tenant context from JWT + Hibernate `@TenantId` (SEC-24)
- [x] Auth: login, refresh with rotation and reuse detection, logout, logout-all, lockout (SEC-01 to SEC-08, SEC-29)
- [x] CSRF, CORS, security headers, rate limits, global error handler with correlation IDs (SEC-11, SEC-60 to SEC-65, SEC-67)
- [x] Users and roles API: invite user, set roles, disable user; seed 8 built-in roles
- [x] Audit log service used by every write (SEC-28)
- [x] Frontend skeleton: Vite, React 19, Tailwind, shadcn/ui, dark mode, router, `en.json`
- [x] Login page, app layout (sidebar, top bar), users and roles pages
- [x] CI: build, tests, ArchUnit, gitleaks, dependency scan, bundle size check (SEC-80, SEC-81)

**Deliverables:** running app on `localhost` with login, user management, role management, audit log screen; CI green; security tests for every SEC rule above.

**Exit criteria:** cross-tenant test suite passes; token reuse test passes; idle RAM under 1.5 GB; AI security review done (SEC-82).

### v0.2 — Warehouse layout builder (2D)

**Goal:** a tenant admin can model any warehouse: floor, zones, racks, levels and bins, and print bin labels.

**Tasks**

- [ ] Tables: `warehouse`, `zone`, `rack_template`, `rack`, `rack_level`, `bin`, `layout_version`
- [x] Warehouse CRUD with plan limit check (per-warehouse pricing)
- [ ] 2D editor (react-konva): draw floor polygon on a metre grid, doors and docks
- [ ] Draw zones with type (receiving, storage, picking, dispatch, quarantine, cold)
- [ ] Rack templates: size, levels, bins per level, max weight per level
- [ ] Place racks: drag, snap, rotate 90°, duplicate row, aisle-width warning, overlap check
- [x] Auto-generate levels and bins with a naming pattern (`{zone}-{rack}-{level}-{bin}`)
- [ ] Edit single bin: capacity, blocked, pick face
- [x] Draft → publish layout; cannot delete racks or bins that hold stock (checked again in v0.4)
- [x] Bin label PDF (QR code + human-readable code), A4 sheets and single labels

**Deliverables:** layout editor, rack template library, label printing, layout saved as data (no images).

**Exit criteria:** a 100-rack, 5,000-bin warehouse can be built in under 30 minutes; editor stays smooth on a 4 GB laptop; layout API passes tenant and permission tests.

### v0.3 — 3D warehouse view

**Goal:** everyone can see the published layout in 3D, find bins, and see their status.

**Tasks**

- [ ] Load 3D page code only on 3D routes (lazy import)
- [ ] Draw floor, zones, racks and bins with instanced meshes; one draw call per shape
- [ ] Orbit, zoom, top view, reset camera; keyboard shortcuts
- [ ] Click bin → side panel (code, capacity, contents placeholder until v0.4)
- [ ] Search bin code → camera flies to it and it highlights
- [ ] Colour modes: by zone, by status (empty / partly full / full / blocked)
- [ ] Lite mode: 2D top-down map for weak PCs; auto-suggest when fps stays under 20
- [ ] Live updates over WebSocket (STOMP) for bin status changes

**Deliverables:** 3D viewer embedded in admin app; Lite mode; performance test page with 10,000 fake bins.

**Exit criteria:** 30+ fps with 5,000 bins on integrated graphics; 3D bundle loads only on 3D pages.

### v0.4 — Products, inbound and inventory

**Goal:** goods can come in, be put away into bins, be moved, and every movement is recorded.

**Tasks**

- [ ] Tables: `product`, `uom`, `product_uom`, `partner`, `purchase_order`, `po_line`, `receipt`, `receipt_line`, `stock`, `stock_movement`, `task`, `file_object`
- [ ] Product master with barcode, units of measure, size/weight, optional image (SEC-40 to SEC-45)
- [ ] Suppliers (partners); CSV import for products and suppliers (SEC-46)
- [ ] Purchase orders: create, approve, cancel
- [ ] Receiving on phone: scan PO or product, enter quantity, note damage, create GRN
- [ ] Putaway: system suggests bin (same product first, then closest empty bin in a storage zone, respecting capacity); worker scans bin to confirm
- [ ] `StockMovementService` is the ONLY code that changes stock; row locks and transactions (SEC-26)
- [ ] Bin-to-bin transfer; stock adjustment with reason and manager approval above a value
- [ ] Stock screens: by product, by bin, movement history; 3D shows real fullness and contents
- [ ] Worker PWA shell: install prompt, camera scanner, task list, big buttons, works on 2 GB phones

**Deliverables:** full inbound flow on phone, stock ledger, stock reports, 3D view with real stock.

**Exit criteria:** end-to-end test receive → putaway → transfer passes; concurrent putaway test (two workers, same bin) never over-fills; stock always equals sum of movements.

### v1.0 — Outbound, reports and pilot release (MVP)

**Goal:** the full warehouse cycle works, the system is deployed securely, and the pilot customer uses it daily.

**Tasks**

- [ ] Tables: `sales_order`, `so_line`, `reservation`, `pick_list`, `pick_line`, `shipment`
- [ ] Customers (partners); sales orders: create, CSV import, cancel
- [ ] Stock reservation on order release (FIFO by receive date)
- [ ] Pick list per order; picker scans bin and product; short-pick handling
- [ ] Packing screen: verify by scan, print packing slip and delivery note PDF
- [ ] Dispatch: mark shipped, stock leaves, movement recorded
- [ ] Supervisor task board: assign, re-assign, priorities, live status
- [ ] Dashboard: stock value, low stock, today's receipts and shipments, bin utilisation %, KPIs (dock-to-stock time, lines picked per hour)
- [ ] Exports: stock, movements, orders to Excel and PDF (streamed)
- [ ] Deployment: Nginx + HTTPS, systemd services, `backup.sh` with daily encrypted dumps, restore guide (SEC-66, SEC-83, SEC-84). Nginx must: send CSP/HSTS/nosniff/frame headers for the web app, keep `Host` (`proxy_set_header Host $host`), set `X-Forwarded-For`/`-Proto`, and never forward `/actuator` (security review v0.1, F12)
- [ ] Production profile: Swagger off, Actuator localhost only, log rotation
- [ ] OWASP ZAP baseline scan; fix all high findings (SEC-85)
- [ ] User guide (short, with screenshots) for admin and workers

**Deliverables:** MVP live on a VPS or the pilot's office PC; admin guide and worker guide; security checklist fully ticked.

**Exit criteria:** pilot runs receive → putaway → pick → pack → ship for 2 weeks with no stock mismatch; all v0.1 to v1.0 SEC rules verified; ZAP scan clean of high issues.

### v1.1 — Counts, returns, batch and expiry

**Goal:** stock stays accurate over time and the system suits food and pharmacy warehouses.

**Tasks**

- [ ] Batch / lot and expiry per product (turn on or off per product)
- [ ] FEFO picking for expiry products; expiry alerts (30 / 60 / 90 days); 3D colour by expiry
- [ ] Cycle counting: count plans by ABC class or zone, blind count on phone, variance review and approval
- [ ] ABC classification job (weekly, based on pick frequency)
- [ ] Returns: return order, inspect, restock / quarantine / scrap
- [ ] Quarantine zone rules (stock in quarantine cannot be picked)

**Deliverables:** count module, returns module, batch/expiry tracking.

**Exit criteria:** a full count of the pilot warehouse finishes without stopping work; FEFO test always picks the earliest expiry.

### v1.2 — Smarter picking and replenishment

**Goal:** workers walk less and pick faces never run empty.

**Tasks**

- [ ] Batch picking (many orders in one walk) and simple waves by cut-off time
- [ ] Pick path ordering by bin sequence; route drawn on the 3D floor
- [ ] Min/max replenishment for pick-face bins; auto tasks from bulk storage
- [ ] Putaway rules editor (fixed bin, same product, closest empty, by zone and category)
- [ ] In-app notifications (low stock, task assigned, expiry) via WebSocket
- [ ] Heat map in 3D: most-picked bins

**Deliverables:** advanced picking, replenishment, rules editor, heat map.

**Exit criteria:** batch picking of 10 orders shortens the route versus single-order picking in a test layout.

### v2.0 — SaaS and commercial release

**Goal:** new customers can sign up and pay per warehouse without your help.

**Tasks**

- [ ] Super Admin portal: tenants, plans, suspend/reactivate, usage
- [ ] Self-service sign-up with email verification and a guided setup wizard
- [ ] Plans and per-warehouse billing; invoices as PDF; payment gateway (choose a Sri Lankan gateway such as PayHere, to be decided)
- [ ] Public REST API with API keys (hashed, scoped, rate limited) and webhooks (signed)
- [ ] Optional 2FA (TOTP) for admins
- [ ] On-premise installer: one script that installs Java, MySQL, Nginx on a single Linux PC
- [ ] Data export for a tenant (all their data as CSV/ZIP) and tenant deletion

**Deliverables:** public SaaS, billing, API docs, on-prem installer.

**Exit criteria:** a new customer can sign up, build a warehouse and receive goods in under one hour without help.

## MySQL database schema

The schema has two cores: the location tree (warehouse → zone → rack → level → bin) and the append-only `stock_movement` ledger. The `stock` table is a fast running balance that must always equal the sum of movements.

**Conventions**

- InnoDB, `utf8mb4`, `utf8mb4_0900_ai_ci`; table and column names in `snake_case`.
- Primary keys `BIGINT UNSIGNED AUTO_INCREMENT`; public IDs exposed in the API are `CHAR(26)` ULIDs (`public_id`), so internal IDs are never guessable.
- Every business table has `tenant_id`, `created_at`, `created_by`, `updated_at`, `updated_by`, `version` (optimistic lock), and `deleted_at` for soft delete where history matters.
- Every query on a tenant table filters by `tenant_id` (Hibernate `@TenantId`); composite indexes start with `tenant_id`.
- Quantities `DECIMAL(14,3)`; money `DECIMAL(14,2)`; sizes in millimetres and grams as integers.
- One Flyway file per change: `V{version}__{short_name}.sql`. Never edit an applied migration.

**Core tables (v0.1 – v0.4)**

```sql
CREATE TABLE tenant (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  name VARCHAR(150) NOT NULL,
  plan_code VARCHAR(30) NOT NULL,
  max_warehouses INT NOT NULL DEFAULT 1,
  status ENUM('ACTIVE','SUSPENDED') NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL
);

CREATE TABLE app_user (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  tenant_id BIGINT UNSIGNED NOT NULL,
  email VARCHAR(190) NOT NULL,
  full_name VARCHAR(120) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  status ENUM('INVITED','ACTIVE','DISABLED','LOCKED') NOT NULL,
  failed_logins INT NOT NULL DEFAULT 0,
  locked_until DATETIME(3) NULL,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_user_email (email),
  KEY ix_user_tenant (tenant_id),
  CONSTRAINT fk_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id)
);

-- role, permission, role_permission, user_role, user_warehouse (warehouse scope)

CREATE TABLE user_session (
  id CHAR(26) PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  ip VARCHAR(45), user_agent VARCHAR(255),
  created_at DATETIME(3) NOT NULL,
  last_seen_at DATETIME(3) NOT NULL,
  revoked_at DATETIME(3) NULL,
  KEY ix_session_user (user_id)
);

CREATE TABLE refresh_token (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  session_id CHAR(26) NOT NULL,
  family_id CHAR(26) NOT NULL,
  token_hash CHAR(64) NOT NULL UNIQUE,      -- SHA-256 hex, never the raw token
  expires_at DATETIME(3) NOT NULL,
  used_at DATETIME(3) NULL,
  revoked_at DATETIME(3) NULL,
  KEY ix_rt_family (family_id)
);

CREATE TABLE audit_log (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NULL,
  user_id BIGINT UNSIGNED NULL,
  action VARCHAR(60) NOT NULL,
  entity VARCHAR(60) NOT NULL,
  entity_id VARCHAR(40) NULL,
  before_json JSON NULL, after_json JSON NULL,
  ip VARCHAR(45), user_agent VARCHAR(255), correlation_id CHAR(26),
  created_at DATETIME(3) NOT NULL,
  KEY ix_audit_tenant_time (tenant_id, created_at)
);

CREATE TABLE warehouse (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  tenant_id BIGINT UNSIGNED NOT NULL,
  code VARCHAR(20) NOT NULL,
  name VARCHAR(120) NOT NULL,
  floor_polygon JSON NOT NULL,              -- [[x_mm,y_mm],...]
  wall_height_mm INT NOT NULL,
  bin_code_pattern VARCHAR(60) NOT NULL DEFAULT '{zone}-{rack}-{level}-{bin}',
  layout_status ENUM('DRAFT','PUBLISHED') NOT NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_wh_code (tenant_id, code)
);

CREATE TABLE zone (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  code VARCHAR(10) NOT NULL,
  type ENUM('RECEIVING','STORAGE','PICKING','DISPATCH','QUARANTINE','COLD') NOT NULL,
  polygon JSON NOT NULL,
  UNIQUE KEY uk_zone (tenant_id, warehouse_id, code)
);

CREATE TABLE rack (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  zone_id BIGINT UNSIGNED NOT NULL,
  template_id BIGINT UNSIGNED NOT NULL,
  code VARCHAR(10) NOT NULL,
  pos_x_mm INT NOT NULL, pos_y_mm INT NOT NULL,
  rotation_deg SMALLINT NOT NULL DEFAULT 0,   -- 0, 90, 180, 270
  width_mm INT NOT NULL, depth_mm INT NOT NULL, height_mm INT NOT NULL,
  UNIQUE KEY uk_rack (tenant_id, warehouse_id, code)
);

CREATE TABLE rack_level (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  rack_id BIGINT UNSIGNED NOT NULL,
  level_no SMALLINT NOT NULL,
  height_from_floor_mm INT NOT NULL,
  max_weight_g BIGINT NOT NULL,
  UNIQUE KEY uk_level (rack_id, level_no)
);

CREATE TABLE bin (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  tenant_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  level_id BIGINT UNSIGNED NOT NULL,
  position_no SMALLINT NOT NULL,
  code VARCHAR(40) NOT NULL,                -- e.g. A-03-02-05, printed on the label
  max_weight_g BIGINT NOT NULL,
  max_volume_cm3 BIGINT NOT NULL,
  is_pick_face BOOLEAN NOT NULL DEFAULT FALSE,
  is_blocked BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE KEY uk_bin_code (tenant_id, warehouse_id, code)
);

CREATE TABLE product (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  tenant_id BIGINT UNSIGNED NOT NULL,
  sku VARCHAR(40) NOT NULL,
  barcode VARCHAR(64) NULL,
  name VARCHAR(200) NOT NULL,
  base_uom VARCHAR(10) NOT NULL,
  weight_g INT NULL, volume_cm3 INT NULL,
  track_batch BOOLEAN NOT NULL DEFAULT FALSE,
  track_expiry BOOLEAN NOT NULL DEFAULT FALSE,
  abc_class CHAR(1) NULL,
  image_file_id BIGINT UNSIGNED NULL,
  deleted_at DATETIME(3) NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_sku (tenant_id, sku),
  KEY ix_barcode (tenant_id, barcode)
);

CREATE TABLE stock (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  bin_id BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  batch_id BIGINT UNSIGNED NULL,
  qty_on_hand DECIMAL(14,3) NOT NULL DEFAULT 0,
  qty_reserved DECIMAL(14,3) NOT NULL DEFAULT 0,
  received_at DATETIME(3) NOT NULL,         -- for FIFO
  UNIQUE KEY uk_stock (tenant_id, bin_id, product_id, batch_id),
  KEY ix_stock_product (tenant_id, product_id),
  CONSTRAINT ck_stock_qty CHECK (qty_on_hand >= 0 AND qty_reserved >= 0 AND qty_reserved <= qty_on_hand)
);

CREATE TABLE stock_movement (               -- append only: no UPDATE, no DELETE
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  type ENUM('RECEIVE','PUTAWAY','TRANSFER','ADJUST','PICK','SHIP','RETURN','COUNT') NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  batch_id BIGINT UNSIGNED NULL,
  from_bin_id BIGINT UNSIGNED NULL,
  to_bin_id BIGINT UNSIGNED NULL,
  qty DECIMAL(14,3) NOT NULL,
  reason VARCHAR(200) NULL,
  ref_type VARCHAR(30) NULL, ref_id BIGINT UNSIGNED NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  KEY ix_mv_product_time (tenant_id, product_id, created_at),
  KEY ix_mv_bin (tenant_id, from_bin_id), KEY ix_mv_bin_to (tenant_id, to_bin_id)
);

CREATE TABLE task (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  tenant_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  type ENUM('PUTAWAY','PICK','TRANSFER','COUNT','REPLENISH') NOT NULL,
  status ENUM('OPEN','ASSIGNED','IN_PROGRESS','DONE','CANCELLED') NOT NULL,
  priority TINYINT NOT NULL DEFAULT 5,
  assigned_user_id BIGINT UNSIGNED NULL,
  ref_type VARCHAR(30), ref_id BIGINT UNSIGNED,
  version INT NOT NULL DEFAULT 0,
  KEY ix_task_user (tenant_id, assigned_user_id, status)
);
```

**Added in later versions:** `rack_template`, `partner`, `purchase_order`, `po_line`, `receipt`, `receipt_line`, `file_object` (v0.2 – v0.4); `sales_order`, `so_line`, `reservation`, `pick_list`, `pick_line`, `shipment` (v1.0); `batch`, `count_plan`, `count_line`, `return_order` (v1.1); `replenish_rule`, `putaway_rule`, `notification` (v1.2); `plan`, `subscription`, `invoice`, `api_key`, `webhook` (v2.0). Claude Code writes these following the same conventions.

The app DB user gets no `UPDATE` or `DELETE` grant on `stock_movement` and `audit_log`, so the ledger cannot be changed even by a bug.

## API conventions and endpoints

REST over JSON under `/api/v1`, cookie-authenticated, documented by springdoc in development.

**Conventions**

- URLs use plural nouns and public IDs: `/api/v1/warehouses/{publicId}/racks`.
- Lists: `?page=0&size=25&sort=code,asc` (max size 100); response `{ items, page, size, total }`.
- Errors: RFC 9457 `ProblemDetail` with `type`, `title`, `status`, `code` (e.g. `BIN_FULL`), `correlationId`. Never a stack trace (SEC-63).
- Validation errors return 400 with a list of `{ field, message }`.
- Write requests that change stock accept an `Idempotency-Key` header so a phone that retries on bad Wi-Fi does not double-count.
- Every endpoint has a permission (e.g. `inventory:transfer`) checked with `@PreAuthorize` (SEC-09).
- WebSocket (STOMP) at `/ws`; topics per tenant and warehouse: `/topic/t/{tenant}/wh/{wh}/bins`, `/user/queue/tasks`.

**Endpoint list by module**

| Module | Main endpoints | Version |
| --- | --- | --- |
| Auth | `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`, `POST /auth/logout-all`, `GET /auth/me`, `POST /auth/password/change` | v0.1 |
| Users and roles | `GET/POST /users`, `PATCH /users/{id}`, `POST /users/{id}/disable`, `GET /roles`, `PUT /users/{id}/roles` | v0.1 |
| Audit | `GET /audit-logs?entity=&user=&from=&to=` | v0.1 |
| Warehouses | `GET/POST /warehouses`, `GET/PATCH /warehouses/{id}`, `POST /warehouses/{id}/archive`, `GET/PUT /warehouses/{id}/users` (access) | v0.2 |
| Layout | `GET/PUT /warehouses/{id}/layout` (whole draft, with `baseRevision`), `POST /warehouses/{id}/layout/publish` (generates levels and bins), `GET/POST /rack-templates`, `PUT /rack-templates/{id}`, `GET /warehouses/{id}/bins`, `PATCH /bins/{id}`, `GET /warehouses/{id}/labels.pdf` | v0.2 |
| 3D data | `GET /warehouses/{id}/scene` (compact arrays for instancing), `GET /warehouses/{id}/bin-status` | v0.3 |
| Catalog | `GET/POST /products`, `PATCH /products/{id}`, `POST /products/import`, `POST /products/{id}/image`, `GET/POST /partners` | v0.4 |
| Inbound | `GET/POST /purchase-orders`, `POST /purchase-orders/{id}/approve`, `POST /receipts`, `POST /receipts/{id}/lines` (scan), `POST /receipts/{id}/close` | v0.4 |
| Putaway and tasks | `GET /tasks/mine`, `POST /tasks/{id}/start`, `GET /putaway/{taskId}/suggestion`, `POST /putaway/{taskId}/confirm` | v0.4 |
| Inventory | `GET /stock?product=&bin=`, `GET /stock-movements`, `POST /transfers`, `POST /adjustments`, `POST /adjustments/{id}/approve`, `GET /scan/{code}` (resolve any barcode) | v0.4 |
| Outbound | `GET/POST /sales-orders`, `POST /sales-orders/{id}/release`, `GET /pick-lists/{id}`, `POST /pick-lines/{id}/confirm`, `POST /shipments`, `GET /shipments/{id}/delivery-note.pdf` | v1.0 |
| Reports | `GET /dashboard`, `GET /reports/stock.xlsx`, `GET /reports/movements.xlsx` | v1.0 |
| Counts and returns | `POST /count-plans`, `POST /count-lines/{id}/submit`, `POST /count-plans/{id}/approve`, `POST /returns` | v1.1 |
| Picking and rules | `POST /waves`, `GET /pick-lists/{id}/route`, `GET/PUT /putaway-rules`, `GET/PUT /replenish-rules` | v1.2 |
| Platform | `/admin/tenants`, `/billing/*`, `/public-api/v1/*` (API keys), `/webhooks` | v2.0 |

## CLAUDE.md, definition of done and testing

Copy this file to the repo root as `CLAUDE.md` on day one. Claude Code reads it automatically every session.

```markdown
# 3D Warehouse System

Read docs/SPEC.md before any task. Current version: v0.1 (update as you go).

## Stack
Java 17, Spring Boot 4.1, Hibernate, MySQL 8.4 LTS, Flyway, Caffeine, Bucket4j.
React 19 + TypeScript strict + Vite, Tailwind + shadcn/ui, TanStack Query,
Zustand, React Hook Form + Zod, three.js + React Three Fiber 9, react-konva.

## Commands
backend:  ./mvnw verify            (tests + ArchUnit)
backend:  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
frontend: npm run dev | npm test | npm run build | npm run size
local db: docker compose -f deploy/docker-compose.yml up mysql

## Hard rules
- Plan first, code after approval. One task per session.
- Every tenant table has tenant_id and uses @TenantId. Never findById in services;
  use findByPublicIdAndTenant.
- Stock changes ONLY through StockMovementService, inside a transaction, with row locks.
- Request bodies are Java records with Bean Validation. Never bind to entities.
- No native SQL built with strings. No raw tokens or password hashes in responses or logs.
- Every controller method has @PreAuthorize with a permission.
- Tokens only in HttpOnly cookies. Never localStorage or sessionStorage.
- Errors: ProblemDetail with code + correlationId. No stack traces to clients.
- Paginate every list (max 100). Use DTO projections for lists.
- No new heavy dependency or new server without asking. Must run on 4 GB RAM.
- All UI text in frontend/src/lib/i18n/en.json.
- Name the SEC-xx rules you implemented in each PR description.

## Style
Backend: package by module (api/service/domain/repo). Constructor injection.
Frontend: one folder per feature; components small; no any.
```

**Definition of done (every task)**

- [ ] Plan approved before coding
- [ ] Code follows CLAUDE.md hard rules
- [ ] Unit tests for services; integration test (Testcontainers MySQL) for each endpoint
- [ ] Security tests: wrong role → 403, other tenant → 404, bad input → 400, no cookie → 401
- [ ] Flyway migration added if schema changed
- [ ] UI works in light and dark mode and on a 360 px phone screen (worker screens)
- [ ] `./mvnw verify` and `npm test` pass; bundle size check passes
- [ ] SEC-xx IDs listed in the PR; `docs/SPEC.md` updated if behaviour changed

**Test plan per version**

| Level | Tool | What it covers |
| --- | --- | --- |
| Unit | JUnit 5, Vitest | Services, putaway suggestion, bin code generator, permission logic, 3D data helpers |
| Integration | Spring Boot Test + Testcontainers MySQL | Every endpoint, tenant isolation, token rotation and reuse, concurrent stock updates |
| Architecture | ArchUnit | Module boundaries, every controller method secured, no `findById` in services, no string SQL |
| End to end | Playwright | Login, build layout, receive → putaway → pick → ship, worker flow on a phone viewport |
| Security | OWASP ZAP baseline, Dependency-Check, gitleaks, AI review prompt | Before every version release |
| Performance | k6 (API), test page with 10,000 bins (3D) | 50 concurrent scanners; 30 fps target on a 4 GB laptop |

**Security review prompt for Claude Code (run before each version release)**

```markdown
Act as a senior application security reviewer. Review all changes since the last
release tag against docs/SPEC.md section "Security requirements" (SEC-01 to SEC-85)
and the OWASP Top 10. For each SEC rule in scope, say PASS or FAIL with file and line.
List any IDOR, missing @PreAuthorize, missing tenant filter, mass assignment, string-built
SQL, token or secret in logs, verbose error, or unsafe file handling. Do not fix anything
yet; output a findings table ordered by severity.
```

## Source

- *The Secure Backend Master-Vault* by Sithara Sangeeth (your uploaded PDF): security rules, checklists and review prompt pattern
- [MedhaCloud: MySQL versions and support dates](https://medhacloud.com/blog/mysql-versions)
- [Oracle MySQL blog: MySQL 9.7 LTS](https://blogs.oracle.com/mysql/mysql-9-7-lts-is-here-upgrade-and-modernize-on-a-stronger-community-edition)
- [spring.io: Spring Boot 4.1.0](https://spring.io/blog/2026/06/10/spring-boot-4/)
- Companion doc: [3D Warehouse Management System — Project Plan](https://claude.ai/artifact/1XHHK5LjAQxe3eA1vjBvCG)
