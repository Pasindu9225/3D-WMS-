# Security checklist

Source: docs/SPEC.md, section "Security requirements" (based on The Secure Backend Master-Vault).
Tick a rule only when it is implemented AND covered by a test. Note the PR or commit.

- [x] **SEC-01** (v0.1): Tokens only in HTTP-only cookies, never localStorage or sessionStorage — task 4, AuthFlowTest
- [x] **SEC-02** (v0.1): Access token: RS256 JWT, 15 minutes, minimal claims — task 4, AuthFlowTest
- [x] **SEC-03** (v0.1): Refresh token: random opaque string, 7 days, stored hashed (SHA-256) — task 4, AuthFlowTest
- [x] **SEC-04** (v0.1): Rotate refresh token on every use; reuse of an old token revokes the whole family — task 4, AuthFlowTest
- [x] **SEC-05** (v0.1): Session check on every request — task 4, AuthFlowTest
- [x] **SEC-06** (v0.1): Lockout after 5 failed logins, 15 minutes, growing backoff — task 4, AuthFlowTest
- [x] **SEC-07** (v0.1): Generic error messages — task 4, AuthFlowTest
- [x] **SEC-08** (v0.1): Logout revokes session and token family; clear both cookies — task 4, AuthFlowTest
- [x] **SEC-09** (v0.1): Role and permission check on every endpoint; deny by default — task 6, UserManagementTest + ArchUnit
- [x] **SEC-10** (v0.1): No IDOR: every lookup by ID also checks tenant and warehouse access — tenant (TenantIsolationTest) + warehouse access (WarehouseTest, v0.2 task 1); new warehouse data must go through WarehouseService.requireAccess
- [x] **SEC-11** (v0.1): CSRF protection for cookie auth — task 5, SecurityHardeningTest/RateLimitTest/ArchUnit
- [x] **SEC-20** (v0.1): Validate all input on the server — task 5, SecurityHardeningTest/RateLimitTest/ArchUnit
- [x] **SEC-21** (v0.1): Unknown JSON fields rejected — task 5, SecurityHardeningTest/RateLimitTest/ArchUnit
- [x] **SEC-22** (v0.1): No mass assignment — task 5, SecurityHardeningTest/RateLimitTest/ArchUnit
- [x] **SEC-23** (v0.1): Parameterised queries only — task 3, ArchUnit NO_STRING_BUILT_SQL + self-test
- [x] **SEC-24** (v0.1): Tenant isolation (replaces PostgreSQL RLS, which MySQL lacks) — task 3, TenantIsolationTest (JWT wiring in task 4)
- [x] **SEC-25** (v0.1): Password and token hashes never leave the auth module — task 4, AuthFlowTest
- [x] **SEC-26** (v0.4): Transactions for multi-step stock changes — task 6: StockMovementService, one transaction per move, bin then stock-row locks in a fixed order, append-only ledger (triggers), StockMovementTest
- [x] **SEC-27** (v0.1): Least-privilege DB users — task 1+3, DatabasePrivilegesTest
- [x] **SEC-28** (v0.1): Audit log on all changes — task 6, UserManagementTest + ArchUnit
- [x] **SEC-29** (v0.1): Passwords hashed with Argon2id (or BCrypt cost 12) — task 4, AuthFlowTest
- [x] **SEC-40** (v0.4): Allow-list file types and size per type — task 1b: JPEG/PNG, 5 MB, ProductImageTest
- [x] **SEC-41** (v0.4): Check magic bytes and match them with Content-Type and extension — task 1b, ProductImageTest
- [x] **SEC-42** (v0.4): Block SVG and HTML — task 1b, ProductImageTest
- [x] **SEC-43** (v0.4): Re-encode every image, never serve the original — task 1b: new JPEG, EXIF/comments gone, ProductImageTest
- [x] **SEC-44** (v0.4): Random file names, stored outside the web root — task 1b: {tenant}/{uuid}.jpg under wms.files.dir, served via product (tenant check)
- [x] **SEC-45** (v0.4): Upload rate limit per user — task 1b: 20/min, ProductImageTest
- [x] **SEC-46** (v0.4): CSV import safety — task 2: .csv UTF-8, 10 MB, 5,000 rows, all or nothing, formula-safe export (CsvTest, ImportApiTest)
- [x] **SEC-60** (v0.1): Rate limits — task 5, SecurityHardeningTest/RateLimitTest/ArchUnit
- [x] **SEC-61** (v0.1): Security headers — task 5, SecurityHardeningTest/RateLimitTest/ArchUnit
- [x] **SEC-62** (v0.1): CORS allow-list, no wildcard — task 5, SecurityHardeningTest/RateLimitTest/ArchUnit
- [x] **SEC-63** (v0.1): Global exception handler: generic errors, no stack traces — task 2, ErrorHandlingTest
- [x] **SEC-64** (v0.1): Hide server information — task 5, SecurityHardeningTest/RateLimitTest/ArchUnit; v1.0: Actuator on a localhost-only port in prod, no Swagger (ProductionProfileTest, NoSwaggerTest)
- [x] **SEC-65** (v0.1): Request limits — task 5, SecurityHardeningTest/RateLimitTest/ArchUnit
- [ ] **SEC-66** (v1.0): HTTPS everywhere
- [x] **SEC-67** (v0.1): Graceful shutdown and 404 for unknown routes — task 2, ErrorHandlingTest
- [x] **SEC-80** (v0.1): Secrets only in environment variables or files outside Git; `.env` in `.gitignore`; gitleaks in CI — task 9 (history scanned: no leaks)
- [x] **SEC-81** (v0.1): Dependency scan in CI (OSV-Scanner instead of OWASP Dependency-Check, `npm audit`), fail on high severity — task 9
- [x] **SEC-82** (Every version): Pre-release AI security review of the whole diff, then a manual check against this list — v0.1 done: docs/security-review-v0.1.md (F1–F8 fixed); v0.2 done: docs/security-review-v0.2.md (F1 fixed, rest info); v0.3 done: docs/security-review-v0.3.md (F1 fixed, rest info); v0.4 done: docs/security-review-v0.4.md (F1, F2 fixed, rest info); v1.0 done: docs/security-review-v1.0.md (F1 fixed: export limit; F2 open question; rest info)
- [ ] **SEC-83** (v1.0): Daily encrypted MySQL backups kept 30 days; restore tested monthly — scripts and timer ready, restore rehearsed locally 2026-11-17; to finish on the server
- [x] **SEC-84** (v1.0): Incident response notes: how to revoke all sessions, rotate keys, restore backup — docs/deploy.md
- [x] **SEC-85** (v1.0): Penetration test (OWASP ZAP baseline scan) before pilot go-live — docs/zap-baseline-v1.0.md (0 High/Medium/Low on the local rehearsal; repeat on the server)
