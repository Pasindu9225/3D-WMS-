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
