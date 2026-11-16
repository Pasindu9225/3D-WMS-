# OWASP ZAP baseline scan — v1.0 (SEC-85)

Date: 2026-11-17 · Tool: `ghcr.io/zaproxy/zaproxy:stable`, `zap-baseline.py` (spider plus passive scan, no
attacks) · Target: the production front door rehearsed locally (no server yet): nginx 1.27.5 with
`deploy/nginx/*.conf` and a self-signed certificate on `https://localhost:8443`, serving the production build of the
web app and proxying the API to the backend. ZAP ran inside the nginx container's network, so it saw exactly what a
visitor sees.

## Result

| Run | High | Medium | Low | Informational | Rules passed |
| --- | --- | --- | --- | --- | --- |
| First | 0 | 0 | 3 | 5 | 62 |
| After fixes | **0** | **0** | **0** | 4 | 64 |

## Fixed

| Finding (risk) | Where | Fix |
| --- | --- | --- |
| Permissions-Policy header not set (Low) | `/assets/*.js` | The assets location sets its own Cache-Control, which in nginx drops the server-level headers. All security headers now live in one `wms-headers.conf`, included at server level and in every location that adds a header. |
| Cross-Origin-Resource-Policy missing (Low) | pages and assets | `Cross-Origin-Resource-Policy: same-origin` |
| Cross-Origin-Embedder-Policy missing (Low) | pages | `Cross-Origin-Embedder-Policy: require-corp` (the app loads nothing from other sites) |
| `robots.txt` answered with the app page | `/robots.txt` | A real `robots.txt` with `Disallow: /`: a private business app should not be indexed |

## Accepted (informational, by design)

- **Storable and cacheable content:** hashed build files are cached for a year on purpose (`immutable`); their names
  change with every release.
- **Re-examine Cache-control / storable but non-cacheable:** `index.html`, `sw.js` and the manifest are `no-cache`
  on purpose, so a new release is picked up at once.
- **Modern web application:** ZAP noticing a single-page app. The API is covered by the automated security tests
  (tenant isolation, permissions, CSRF, rate limits; 215 backend tests).
- **Suspicious comments:** one HTML comment in `index.html` explaining the CSP-safe theme script; no secrets.

## Not covered

No active scan (attacks) was run against this local rehearsal; the API's defences are tested in code
(`ArchitectureTest`, `SecurityHardeningTest`, per-endpoint permission and other-company tests). Repeat this baseline
against the real domain after the server install, before the pilot goes live.
