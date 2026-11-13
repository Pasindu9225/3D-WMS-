# Security checklist

Source: docs/SPEC.md, section "Security requirements" (based on The Secure Backend Master-Vault).
Tick a rule only when it is implemented AND covered by a test. Note the PR or commit.

- [ ] **SEC-01** (v0.1): Tokens only in HTTP-only cookies, never localStorage or sessionStorage
- [ ] **SEC-02** (v0.1): Access token: RS256 JWT, 15 minutes, minimal claims
- [ ] **SEC-03** (v0.1): Refresh token: random opaque string, 7 days, stored hashed (SHA-256)
- [ ] **SEC-04** (v0.1): Rotate refresh token on every use; reuse of an old token revokes the whole family
- [ ] **SEC-05** (v0.1): Session check on every request
- [ ] **SEC-06** (v0.1): Lockout after 5 failed logins, 15 minutes, growing backoff
- [ ] **SEC-07** (v0.1): Generic error messages
- [ ] **SEC-08** (v0.1): Logout revokes session and token family; clear both cookies
- [ ] **SEC-09** (v0.1): Role and permission check on every endpoint; deny by default
- [ ] **SEC-10** (v0.1): No IDOR: every lookup by ID also checks tenant and warehouse access
- [ ] **SEC-11** (v0.1): CSRF protection for cookie auth
- [ ] **SEC-20** (v0.1): Validate all input on the server
- [ ] **SEC-21** (v0.1): Unknown JSON fields rejected
- [ ] **SEC-22** (v0.1): No mass assignment
- [x] **SEC-23** (v0.1): Parameterised queries only — task 3, ArchUnit NO_STRING_BUILT_SQL + self-test
- [x] **SEC-24** (v0.1): Tenant isolation (replaces PostgreSQL RLS, which MySQL lacks) — task 3, TenantIsolationTest (JWT wiring in task 4)
- [ ] **SEC-25** (v0.1): Password and token hashes never leave the auth module
- [ ] **SEC-26** (v0.4): Transactions for multi-step stock changes
- [x] **SEC-27** (v0.1): Least-privilege DB users — task 1+3, DatabasePrivilegesTest
- [ ] **SEC-28** (v0.1): Audit log on all changes
- [ ] **SEC-29** (v0.1): Passwords hashed with Argon2id (or BCrypt cost 12)
- [ ] **SEC-40** (v0.4): Allow-list file types and size per type
- [ ] **SEC-41** (v0.4): Check magic bytes and match them with Content-Type and extension
- [ ] **SEC-42** (v0.4): Block SVG and HTML
- [ ] **SEC-43** (v0.4): Re-encode every image, never serve the original
- [ ] **SEC-44** (v0.4): Random file names, stored outside the web root
- [ ] **SEC-45** (v0.4): Upload rate limit per user
- [ ] **SEC-46** (v0.4): CSV import safety
- [ ] **SEC-60** (v0.1): Rate limits
- [ ] **SEC-61** (v0.1): Security headers
- [ ] **SEC-62** (v0.1): CORS allow-list, no wildcard
- [x] **SEC-63** (v0.1): Global exception handler: generic errors, no stack traces — task 2, ErrorHandlingTest
- [ ] **SEC-64** (v0.1): Hide server information
- [ ] **SEC-65** (v0.1): Request limits
- [ ] **SEC-66** (v1.0): HTTPS everywhere
- [x] **SEC-67** (v0.1): Graceful shutdown and 404 for unknown routes — task 2, ErrorHandlingTest
- [ ] **SEC-80** (v0.1): Secrets only in environment variables or files outside Git; `.env` in `.gitignore`; gitleaks in CI
- [ ] **SEC-81** (v0.1): Dependency scan in CI (OWASP Dependency-Check, `npm audit`), fail on high severity
- [ ] **SEC-82** (Every version): Pre-release AI security review of the whole diff, then a manual check against this list
- [ ] **SEC-83** (v1.0): Daily encrypted MySQL backups kept 30 days; restore tested monthly
- [ ] **SEC-84** (v1.0): Incident response notes: how to revoke all sessions, rotate keys, restore backup
- [ ] **SEC-85** (v1.0): Penetration test (OWASP ZAP baseline scan) before pilot go-live
