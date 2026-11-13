# Security review — v0.1 (SEC-82)

Date: 2026-11-13 · Scope: all code from the first commit to `e84e151` (tasks 1–9), backend and frontend.
Method: the review prompt in docs/SPEC.md — every SEC rule due in v0.1 plus the OWASP Top 10 (2021), code read
file by file, risky patterns searched across the repo, and the behaviour checked against the test suites
(81 backend, 17 frontend) and the manual runs done in tasks 5, 6 and 8. Nothing was changed during the review.

## Findings (most severe first)

| # | Severity | Finding | Where | Fix |
| --- | --- | --- | --- | --- |
| F1 | High (at deploy) | **Client IP is wrong behind Nginx.** `server.forward-headers-strategy` is not set, so behind a proxy every request comes from 127.0.0.1. Then: (a) `/actuator/health` (localhost-only) is open to everyone; (b) one shared login limit (10/min) and one IP block (20 failures) for all users, so one attacker can lock the whole company out. If the setting is turned on with Spring's defaults, Tomcat trusts `X-Forwarded-For` from any private LAN address (10/8, 192.168/16 ...), so on an office network a client could forge its IP and dodge every IP limit. | `RequestInfo.java:16`, `application.properties` | Set `server.forward-headers-strategy=native` and `server.tomcat.remoteip.internal-proxies` to 127.0.0.1 / ::1 only. Test that a forged `X-Forwarded-For` from a non-proxy is ignored. |
| F2 | Medium | **Two open tabs log the user out every 15 minutes.** Both tabs share one access cookie, so it expires in both at the same moment; both refresh with the same refresh token; the second refresh looks like token theft (SEC-04) and the whole session is revoked. The single-flight refresh only works inside one tab. | `frontend/src/lib/api.ts:93` (`refreshOnce`) | Serialise refresh across tabs with the Web Locks API (`navigator.locks`); after waiting, retry the request first, since another tab may already have refreshed. Server-side detection stays strict. |
| F3 | Low | **Argon2 settings slightly below OWASP minimum**: 16 MiB / 2 iterations (Spring's `defaultsForSpringSecurity_v5_8`); OWASP asks for 19 MiB / 2. | `SecurityConfig.java:194` | `new Argon2PasswordEncoder(16, 32, 1, 19456, 2)`. Existing hashes upgrade on next login (`upgradeEncoding` is already called). |
| F4 | Low | **No absolute session lifetime.** Refresh tokens slide 7 days on each use, so a session used weekly never ends. | `AuthService.refresh` | Refuse refresh when the session is older than 30 days (`user_session.created_at`). |
| F5 | Low | **Locked accounts have no "Unlock" in the UI.** SEC-06 lets anyone who knows an email lock that account; the API's Enable already clears the lock, but the Users page only offers Enable for disabled users and does not show who is locked. | `UsersPage.tsx`, `UserService.UserView` | Return `lockedUntil` in the user view; show a Locked badge and an Unlock action. |
| F6 | Low | **Old rows are never deleted**: `login_attempt`, used/revoked `refresh_token`, revoked `user_session`, used `user_invite`. Slow growth; login-attempt rows also hold IPs (personal data). | schema V1/V2 | Nightly cleanup job (e.g. attempts > 30 days, tokens/sessions > 30 days after end). Before v1.0. |
| F7 | Low | **Rate-limit counters can be evicted** by an attacker rotating many IPv6 addresses (cache max 100,000 keys; IPv6 gives each client a /64). | `RateLimiter.java` | Key IPv6 clients by their /64 prefix. |
| F8 | Low | **Suspended companies can still sign in**: `tenant.status` is not checked. No way to suspend exists yet. | `AuthService.login`, `SessionService.load` | Check it when suspension is built (v2.0); noted in the spec task. |
| F9 | Info | Invite to an email that exists in another company returns `EMAIL_IN_USE`, which tells an admin that the email is registered somewhere. | `UserService.invite` | Accepted: emails are global logins; only admins can probe, and every probe is audited. |
| F10 | Info | The bootstrap command prints the first admin's invite link to the console, which systemd keeps in its journal. | `BootstrapRunner.java:55` | Accepted: single use, 72 hours, needs server access to read. |
| F11 | Info | No check against breached / very common passwords (only length 10–128). | `passwordSchema.ts`, `AuthController` | Consider a small common-password list in v1.0. |
| F12 | Info (deploy) | CSP, HSTS and the other headers are set on API responses only. Nginx must add them for `index.html` and assets, keep `Host` (`proxy_set_header Host $host`), and never forward `/actuator`. | — | v1.0 deploy task (SEC-61, SEC-64, SEC-66). |

No IDOR, missing `@PreAuthorize`, missing tenant filter, mass assignment, string-built SQL, secret in logs,
verbose error or unsafe file handling was found. These are also enforced by ArchUnit rules and tests, so a
future change that breaks them fails the build.

## SEC rules due in v0.1

| Rule | Result | Evidence |
| --- | --- | --- |
| SEC-01 HttpOnly cookies only | PASS | `AuthCookies.java`; frontend never stores tokens (only the theme in localStorage, `theme.ts:5`); AuthFlowTest |
| SEC-02 RS256 JWT, 15 min, minimal claims | PASS | `TokenService.issueAccessToken`; decoder pins RS256 + issuer (`SecurityConfig.jwtDecoder`); tampered/expired tests |
| SEC-03 opaque refresh, 7 days, hashed | PASS | `TokenService.newRefreshToken`, `sha256`; test asserts raw value not stored |
| SEC-04 rotation + reuse revokes family | PASS (see F2) | `AuthService.refresh`; reuse test. F2 is a usability gap, not a weakness |
| SEC-05 session check every request | PASS | `SecurityConfig.sessionChecked` + `SessionService` (60 s cache, evicted after commit) |
| SEC-06 lockout 5 failures, growing backoff | PASS (see F1, F5) | `AppUser.recordFailedLogin`; per-IP limit; tests |
| SEC-07 generic errors | PASS | one `AUTH_FAILED` for every login failure; dummy hash equalises timing; test compares bodies |
| SEC-08 logout revokes session + family | PASS | `AuthService.logout/logoutAll`; cookies cleared; tests |
| SEC-09 permission on every endpoint | PASS | ArchUnit `CONTROLLER_METHODS_NEED_PRE_AUTHORIZE` + self-test; UserManagementTest per role |
| SEC-10 no IDOR | PARTIAL (by plan) | tenant part PASS (`@TenantId`, no-findById rule, cross-tenant tests); warehouse access comes in v0.2 |
| SEC-11 CSRF | PASS | `csrf().spa()` + Strict cookie; SecurityHardeningTest; manual run in task 5 |
| SEC-20 validate input | PASS | records + Bean Validation; ArchUnit requires `@Valid` and a length limit on every String |
| SEC-21 unknown JSON fields rejected | PASS | `fail-on-unknown-properties=true`; test with `"role":"ADMIN"` |
| SEC-22 no mass assignment | PASS | ArchUnit: request bodies must be records, never entities |
| SEC-23 parameterised queries | PASS | ArchUnit `NO_STRING_BUILT_SQL`; the 2 native queries use `:userId` |
| SEC-24 tenant isolation | PASS | TenantIsolationTest (read, write, update, no tenant) |
| SEC-25 hashes never leave auth | PASS | no DTO or audit snapshot carries a hash; tests grep responses for `argon2` |
| SEC-27 least-privilege DB users | PASS | `wms_app` data only, `wms_flyway` schema; DatabasePrivilegesTest |
| SEC-28 audit log on all changes | PASS | every write in Auth/UserService audits; append-only triggers; before/after snapshots |
| SEC-29 Argon2id | PASS (see F3) | `DelegatingPasswordEncoder` with Argon2id; test checks `{argon2}$argon2id$` |
| SEC-60 rate limits | PASS in tests, FAIL behind a proxy until F1 | `IpRateLimitFilter`, `UserRateLimitFilter`; RateLimitTest |
| SEC-61 security headers | PASS for the API (see F12) | SecurityHardeningTest; real Tomcat check in task 5 |
| SEC-62 CORS allow-list | PASS | exact origins, wildcard refuses to start; test |
| SEC-63 generic errors | PASS | ProblemDetail + correlation ID; ErrorHandlingTest |
| SEC-64 hide server info | PASS in tests, FAIL behind a proxy until F1 | no Server header; health localhost-only |
| SEC-65 request limits | PASS | 1 MB JSON cap, header size, timeouts; test |
| SEC-67 graceful shutdown, 404 | PASS | `server.shutdown=graceful`; 404 test |
| SEC-80 secrets outside Git, gitleaks | PASS | full history scanned: no leaks; secrets git-ignored |
| SEC-81 dependency scan | PASS | OSV (106 backend libs + npm) and npm audit: 0 findings |
| SEC-82 AI security review | PASS | this document |

## OWASP Top 10 (2021)

| Category | Result |
| --- | --- |
| A01 Broken access control | PASS — deny by default, permission per endpoint, tenant filter, 404 for other tenants |
| A02 Cryptographic failures | PASS (F3) — RS256 keys from files, Argon2id, SHA-256 token hashes, HTTPS in v1.0 |
| A03 Injection | PASS — JPQL/derived queries only, ArchUnit blocks string SQL, React escapes output |
| A04 Insecure design | F2, F4 — session lifetime and multi-tab refresh |
| A05 Security misconfiguration | F1, F12 — proxy/IP and Nginx headers |
| A06 Vulnerable components | PASS — OSV and npm audit clean, actions pinned |
| A07 Identification and authentication failures | PASS (F5, F11) |
| A08 Software and data integrity failures | PASS — pinned CI actions, append-only audit and ledger triggers |
| A09 Security logging and monitoring failures | PASS — audit log with correlation IDs; alerting comes later |
| A10 Server-side request forgery | N/A — the server makes no outbound requests from user input |

## Resolution (same day)

All of F1–F8 were fixed; F9–F11 are accepted as described; F12 is on the v1.0 deploy task. Each fix has a test
that fails without it. Backend 89 tests, frontend 18 tests, all passing.

| # | Fix | Test |
| --- | --- | --- |
| F1 | `server.forward-headers-strategy=native`; `server.tomcat.remoteip.internal-proxies` = loopback only (the test also caught that the regex dots were not escaped in the first try) | `ForwardedClientIpTest` (real Tomcat: forwarded outside IP cannot reach health; only loopback trusted) |
| F2 | Refresh serialised across tabs with `navigator.locks` (`api.ts` `acrossTabs`); server keeps strict reuse detection | `api.test.ts` "refreshes inside a browser-wide lock" |
| F3 | `new Argon2PasswordEncoder(16, 32, 1, 19456, 2)`; old hashes re-hashed at login via `AppUser.upgradeHash` (does not count as a password change) | `AuthFlowTest.weakOldHashIsUpgradedAtLogin…` |
| F4 | `TokenService.SESSION_MAX_AGE` = 30 days, checked at refresh and in the session check | `AuthFlowTest.sessionEndsThirtyDaysAfterLogin…` |
| F5 | `lockedUntil` in the user view; `POST /users/{id}/unlock` (audited `USER_UNLOCKED`); Locked badge and Unlock action in the UI | `UserManagementTest.adminSeesAndClearsALockout` |
| F6 | `CleanupJob`, 03:30 nightly: login attempts, expired refresh tokens, dead sessions, expired invites older than 30 days; audit log untouched. `app_user.password_set_at` (V3) replaces inferring "has a password" from accepted invites, so invites can be deleted | `CleanupJobTest` |
| F7 | `RequestInfo.clientKey`: IPv6 counted per /64 for rate limits and the 20-failure login block | `RateLimitTest.ipv6CountsPerSlash64…` |
| F8 | Suspended company refused at login (after the password, same generic error), refresh, and the per-request session check (applies within 60 s) | `AuthFlowTest.suspendedCompanyCannotSignInOrRefresh` |

Updated rule results: SEC-60 and SEC-64 now PASS behind a proxy as well (F1).
