# Security review — v0.3 (SEC-82)

Date: 2026-11-14 · Scope: v0.3 tasks 1–8 (`a427fd9` … task 8): the scene and bin-status API, the 3D and Lite
viewers, and live updates over WebSocket.
Method: the review prompt in docs/SPEC.md: the SEC rules that touch these features plus the OWASP Top 10
(2021), with extra care for the new attack surface, a cookie-authenticated WebSocket. I read the new code file
by file and checked it against the test suites (143 backend including 6 real-socket tests, 76 frontend) and the
browser runs of every task.

## Findings (most severe first)

| # | Severity | Finding | Where | Fix |
| --- | --- | --- | --- | --- |
| F1 | Medium | **One account could open unlimited sockets.** Each open socket holds memory and a send buffer on the shared 4 GB server, so a signed-in user (or a stolen session) with a loop could exhaust it. | `LiveConfig` | **Fixed in task 8:** at most 5 sockets per sign-in; the 6th is closed straight away. Test `oneSignInGetsAtMostFiveSockets`. |
| F2 | Info | **Cross-site WebSocket hijacking.** Browsers send cookies with WebSocket handshakes, and CSRF tokens do not apply to them. | `LiveConfig.registerStompEndpoints` | Handled: only our origin (and the explicit CORS allow-list, no wildcards) may connect. Test: `https://evil.example` gets 403. |
| F3 | Info | **A socket would outlive its sign-in:** the handshake is checked once. | `LiveConfig.closeStaleSockets` | Handled: every 60 s the session is checked again (sign-out, disabled user, suspended company) and sockets older than 15 min (the access-token lifetime) are closed, so permission changes apply. Test: sign-out closes the socket. |
| F4 | Info | **The scene is not paginated** (exception to "paginate every list"). | `SceneController` | Accepted: SPEC defines it; bounded by the 50,000-bin cap (about 2.7 MB of JSON); needs warehouses:read and warehouse access. Nginx should gzip JSON at deploy. |
| F5 | Info | **Dev-only `window.__wmsRenderer`** for the browser checks. | `SceneCanvas` | Accepted: guarded by `import.meta.env.DEV`, so production builds drop it. Checked: not present in the preview build. |
| F6 | Info | **localStorage holds two view preferences** (`wms.3d.view`, `wms.3d.colorMode`). | `View3DPage` | Accepted: display choices only, read inside try/catch; tokens still live only in HttpOnly cookies. |

No IDOR, missing `@PreAuthorize`, missing tenant filter or verbose error was found:
- The scene and bin-status endpoints both need warehouses:read plus warehouse access, and return 404
  otherwise (stranger and other-company tests).
- WebSocket subscriptions are checked again in the user's tenant: only `/topic/wh/{warehouse}/bins`, only with
  access. Clients can never SEND (tests for both).
- Pushes go out only after commit, so a refused or rolled-back edit sends nothing (tested).
- Incoming frames are capped at 8 KB. The STOMP client is in the 3D page's bundle only, and three.js is in a
  lazy chunk (the size check fails if either reaches the initial bundle).

## Exit criteria (v0.3)

| Criterion | Result |
| --- | --- |
| 30+ fps with 5,000 bins on integrated graphics | Intel UHD Graphics (integrated), production build, `/app/3d-perf` spinning the camera for 10 s: **60 fps median** (screen limit, slowest 10% 59 fps) with 5,000, 10,000 and 20,000 bins; still 60 fps with 5,000 bins and the CPU slowed 4x. The real warehouse draws 5,000 bins in 7 draw calls. |
| 3D bundle loads only on 3D pages | three.js, React Three Fiber and drei sit only in the lazy `SceneCanvas` chunk (254 KB gzip); the initial JS is 162 KB of 400 KB; `npm run size` fails if three.js or Konva ever reach the initial bundle. In the browser no three.js file loads before opening a 3D page, and none at all in Lite mode. |
| Deliverables: 3D viewer, Lite mode, performance page with 10,000 fake bins | Done: `/app/warehouses/:id/3d`, the Lite map, and `/app/3d-perf` (1,000 to 20,000 fake bins). |
