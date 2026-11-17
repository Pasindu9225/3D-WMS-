# Security review — v1.0 (SEC-82)

Date: 2026-11-17 · Scope: v1.0 since v0.4 closed (`515b7ed` … `2717142`): customers, sales orders and their CSV
import, reservations, pick lists on the phone, packing and the packing slip / delivery note PDFs, shipments, the
task board, the dashboard, Excel/PDF exports, the production profile and the deploy files (91 files, +6,458 lines).
Method: the review prompt in docs/SPEC.md: the SEC rules that touch these features (SEC-09/10, 20–26, 46, 60–64,
80–85) plus the OWASP Top 10 (2021). New attack surface: reserved stock shared between orders, phones confirming
picks under bad Wi-Fi, generated documents (PDF, xlsx) that carry user text, large downloads, a supervisor who
hands work to other users. I read the new code file by file (controllers, services, repositories, migrations, the
new frontend features) and checked it against the test suites (212 backend, 97 frontend), the browser runs of every
task and the ZAP baseline (docs/zap-baseline-v1.0.md).

## Findings (most severe first)

| # | Severity | Finding | Where | Fix |
| --- | --- | --- | --- | --- |
| F1 | Medium | **Exports had no limit of their own.** One export can be 200,000 rows and takes seconds of database and CPU time; the general 100 requests a minute per user let one account start dozens at once and load down a 4 GB server. | `ExportController` | **Fixed:** exports have their own bucket, 10 per minute per user (`wms.rate-limit.export-per-minute`), checked before any query runs; `TOO_MANY_EXPORTS` (429) with a user message. Test `exportsAreLimitedTo10PerMinutePerUser`. |
| F2 | Low | **Contact details visible to floor roles.** Customers reuse `catalog:read`, which every warehouse role holds, so pickers and packers can list customers' phone, email and address. Only the company's own staff can see them. | `PartnerEndpoints`, V2 role grants | Accepted for the pilot; question for the owner in questions.md (a separate `partners:read` for office roles if wanted). |
| F3 | Info | **A supervisor can pick any list.** `tasks:assign` also lets a supervisor confirm lines on a list given to someone else. | `PickService.mine` | By design (step in when a picker goes home); every pick is audited with who did it. |
| F4 | Info | **Pick confirm without an Idempotency-Key.** A retried request without the header is a second pick. | `PickListController.confirm` | Handled: the phone always sends a key; without one the line's remaining quantity and its own reservation still cap the pick (OVER_PICK), so stock can never go negative or exceed the order. |
| F5 | Info | **User text in generated files.** Customer names, addresses, notes and product names go into PDFs and Excel. | `DocumentPdf`, `ExportService` | Checked: OpenPDF writes them as plain text (no HTML or script); Excel cells are string cells, so `=HYPERLINK(...)` stays text (test); file names come from server numbers and codes, encoded by `ContentDisposition`. |
| F6 | Info | **Native SQL** added to the ledger (stock upsert with an area). | `StockRepository` | Checked: a constant string with bound parameters; `tenant_id` from the server-side context (SEC-23). All other new queries are JPQL under the Hibernate tenant filter. |
| F7 | Info | **New dependency** Apache POI poi-ooxml 5.4.1 (user approved), OpenPDF 2.0.5. | `pom.xml` | Exact versions; OSV scan in CI clean. |

Checked and found sound:
- **Access (SEC-09/10):** every new endpoint has `@PreAuthorize` (ArchUnit); orders, pick lists, shipments, the
  board, the dashboard and exports are reached by public id and checked for warehouse access (404 otherwise);
  assigning a list accepts only pickers of that company who work in that warehouse; a list given to someone else is
  refused to other pickers (NOT_YOUR_LIST). Other-company and wrong-role tests in every new API test class.
- **Stock (SEC-26):** reserve, pick and ship go only through `StockMovementService`; reservation rows are locked in
  bin order and amounts read after the locks; release is all or nothing; a pick can only consume what its own line
  reserved in that bin; cancel locks the pick list and refuses once picking started, then frees exactly the order's
  reservations; dispatch locks its orders in id order and ships only PACKED ones once. Tests: concurrent releases,
  picks and dispatches; stock = sum of movements after the whole loop.
- **State and replays:** orders are version-checked on edit, release, pack and cancel (409 SO_CHANGED / STALE);
  pick confirm is idempotent by key; a shipment cannot include an order twice.
- **Input (SEC-20–25, 46):** every body is a record with Bean Validation (quantities bounded, 3 decimals, lists
  capped at 200/500 lines); CSV order import is UTF-8, 10 MB, 5,000 rows, every row validated, all or nothing,
  duplicate order refs refused, 20 uploads a minute.
- **Output (SEC-60–64):** PDFs get their own CSP (`default-src 'none'; object-src 'self'; frame-ancestors 'none'`)
  and nosniff; new error codes all have user messages (errorCodes.test); no internals in errors; prod profile binds
  only to localhost; nginx headers verified by ZAP.
- **Abuse:** pick confirms use the scan bucket (300/min), the board polls every 10 s and the dashboard every
  minute (well within 100/min), exports now have their own bucket and a row cap checked before the first byte.
- **Frontend (SEC-01):** no tokens in browser storage (only theme and view preferences); no HTML injection;
  `npm audit` clean.

## Exit criteria (v1.0)

| Criterion | Result |
| --- | --- |
| Receive → putaway → pick → pack → ship with no stock mismatch | Pass in tests and the browser (DispatchTest whole loop; see test-report-v1.0.md). The 2-week pilot needs a server and a pilot customer |
| All v0.1 to v1.0 SEC rules verified | All ticked except SEC-66 (HTTPS) and SEC-83 (backups): files ready and rehearsed locally, finished on the server |
| ZAP scan clean of high issues | Pass on the local rehearsal (0 High, 0 Medium, 0 Low); repeat on the real domain |
