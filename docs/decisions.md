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
