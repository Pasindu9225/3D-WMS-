# Security review — v1.2 (SEC-82)

Date: 2026-11-18 · Scope: v1.2 since v1.1 was reviewed (`2032ab3` … `666009d`): batch picking in waves, walking
order and routes, refill of pick bins (min/max rules, automatic tasks), putaway rules and product categories, in-app
notifications with a per-user live topic, and the pick heat map (42 backend files, +2,534 lines; 87 files in all).
Method: the review prompt in docs/SPEC.md: the SEC rules that touch these features (SEC-09/10, 20–26, 46, 60, 63)
plus the OWASP Top 10 (2021). New attack surface: work created automatically inside someone else's stock move
(refill tasks), rules that steer where goods go, a second WebSocket topic that is per person instead of per
warehouse, scheduled jobs that write for every company, and alert text that carries product data. I read the new code
file by file and checked it against the test suites (229 backend, 117 frontend) and the browser runs.

## Findings (most severe first)

| # | Severity | Finding | Where | Fix |
| --- | --- | --- | --- | --- |
| F1 | Low | **"Only this zone" is enforced at putaway only.** A putaway rule with *only this zone* refuses a scanned bin outside the zone, but Move stock, a refill or a return restock can still place that product in another zone (each has its own bin checks). Someone with `inventory:transfer` could move frozen goods into a dry rack. | `PutawayService.checkOnlyZone` vs `StockMovementService` callers | Accepted for v1.2: rules were specified as a putaway guide; every move is in the ledger and audit log with who did it. Question for the owner in questions.md: enforce *only this zone* on every move into a bin. |
| F2 | Low | **Heat counts are re-read on every live refresh.** `/bin-status` now also counts PICK movements of the last 30 days per bin; each 3D viewer calls it (debounced 1 s) after every stock change. | `SceneService.picks`, `StockService.binPicks` | Accepted at pilot size: one grouped query on the existing `(tenant, warehouse, created_at)` index, warehouse-scoped, within the 100 requests/min user limit. Upgrade path noted in the code: cache per warehouse for a minute if picking gets heavy. |
| F3 | Low | **Low-stock check totals the warehouse after each move out.** | `NotificationTriggers.lowStock` | Accepted: runs after commit in its own transaction (a failure never undoes the pick), only for products with a reorder point; marked `ponytail:` with the per-product query as the upgrade. |
| F4 | Info | **Refill tasks are made inside the picker's transaction.** | `RefillService.onStockTaken` | Checked: the rule row is locked (`PESSIMISTIC_WRITE`) after the bin and stock locks the move already holds, so the lock order bin → stock → rule is the same everywhere (Check now locks the rule and only reads stock); a unique key on the open task is the safety net, so two picks at once make one task (test). Doing a refill is a TRANSFER through `StockMovementService`, only from a storage bin (not a pick face, blocked or quarantine), only into the task's bin, never more than asked. |
| F5 | Info | **Per-person live topic.** `/topic/user/{id}/notifications` | `LiveConfig.guard` | Checked: subscribing is allowed only when the id is the subscriber's own public id — even a company admin is refused for a colleague (test). The push carries no content (`{"type":"NOTIFICATION"}`); the list is read over HTTPS, scoped to the caller. The same handshake rules apply as before (session cookie, origin allow-list, 5 sockets per sign-in, closed on sign-out or after 15 min). |
| F6 | Info | **Notification text and links.** | `NotificationService`, `NotificationBell` | Checked: rows hold kind + params; the sentence is built from en.json and rendered as React text (escaped), so product names cannot inject markup. Links are built on the server from fixed paths and public ids, never from user input. Someone else's notification id answers 404; other company sees none (test). Rows are deduplicated and removed after 90 days. |
| F7 | Info | **Jobs that write for every company.** Daily 06:00 expiry summary and clean-up. | `NotificationTriggers.daily` | Checked: same pattern as the ABC job — company list in the system context, each company in its own context, each warehouse in its own transaction; one failure is logged and the next company still runs. |
| F8 | Info | **Rules steer goods but cannot reach other warehouses.** | `PutawayRuleService` | Checked: bin codes are looked up inside the rule's warehouse, zone codes are checked against that warehouse's published layout, product by SKU/barcode within the company; rules of a warehouse the user cannot see answer 404; up to 200 rules per warehouse, so the unpaged list stays small. Product category: max 40 characters, no control characters (also in CSV import). |

Checked and found sound:
- **Access (SEC-09/10):** every new endpoint has `@PreAuthorize` (ArchUnit). Waves: create / by cut-off / delete
  with `tasks:assign`; view and route with `picking:execute` or `outbound:read`. Refill rules and putaway rules:
  change with `tasks:assign`; doing a refill with `inventory:transfer`. Notifications: `isAuthenticated()` and always
  the caller's own rows. Everything is reached by public id and checked for warehouse access (404 otherwise); the
  heat map rides the scene, so it has the scene's permission and access. Other-company and wrong-role tests in every
  new API test class.
- **Stock (SEC-26):** waves pick per order through the existing pick confirm (stock moves per list); refill and
  putaway still move stock only through `StockMovementService`; lock order list → wave, task → bin → stock → rule;
  stock = sum of movements after every run.
- **Input (SEC-20–25):** every body is a record with Bean Validation (quantities and priorities bounded, wave lists 2–20,
  codes length-limited); optional booleans are `Boolean` so a missing field cannot fail parsing; new JPQL is constant
  with bound parameters; no native SQL added.
- **Abuse (SEC-60):** all new endpoints are in the user bucket (100/min); waves, rules and refill tasks are created
  only by supervisors and managers or by the system; automatic notifications are once per product (or warehouse)
  per day.
- **Errors (SEC-63):** every new error code has a user message in en.json (errorCodes test); messages name only the
  user's own bins, zones and products.

## Exit criteria (v1.2)

| Criterion | Result |
| --- | --- |
| Batch picking of 10 orders shortens the route versus single-order picking in a test layout | Pass: `WaveTest.tenOrdersByCutOffWalkFarLessThanOneByOne` — ten orders over the same three racks: walking them one by one is more than 9 × the wave's walk (the racks are visited once instead of ten times); the cut-off wave takes the oldest 10 and leaves the late one out |

## Result
No high or medium findings. F1 is an owner decision (questions.md); F2 and F3 are noted performance ceilings for later.
v1.2 is accepted locally; it ships with v1.0's deployment once a server exists.
