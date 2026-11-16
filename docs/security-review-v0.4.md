# Security review — v0.4 (SEC-82)

Date: 2026-11-16 · Scope: v0.4 tasks 1–9 (`68ae4aa` … `183cd73`): products, pictures, suppliers, CSV import,
purchase orders, receiving, the stock ledger, putaway, transfers, adjustments, stock screens, 3D fill levels and
the worker phone app.
Method: the review prompt in docs/SPEC.md: the SEC rules that touch these features (SEC-09/10, 20–28, 40–46, 60,
61, 80, 81) plus the OWASP Top 10 (2021). New attack surface: file uploads, CSV files, money-like values
(adjustment approval), concurrent stock changes from phones, a camera library and a service worker. I read the
new code file by file and checked it against the test suites (190 backend, 88 frontend) and the browser runs of
every task.

## Findings (most severe first)

| # | Severity | Finding | Where | Fix |
| --- | --- | --- | --- | --- |
| F1 | Medium | **Scanning workers would hit the per-user limit.** All requests shared one bucket of 100 per minute; a receiver scanning a delivery (plus the screen's own reads) can pass that, get 429s and retry, which is both a usability and a reliability problem. | `UserRateLimitFilter` | **Fixed:** scan endpoints (receipt lines, putaway, transfers, barcode lookup) have their own bucket, 300 per minute per user (`wms.rate-limit.scan-per-minute`); office requests keep 100. Test `phoneScansHaveTheirOwnLargerBucket`. |
| F2 | Low | **The build's file list was published.** `dist/.vite/manifest.json` (added for the worker size check) would be served by Nginx and lists every chunk name. | `vite.config.ts`, `check-size.mjs` | **Fixed:** the size check reads it and deletes it, so it never reaches `dist` as deployed. |
| F3 | Info | **Adjustment value is unknown when a product has no cost.** A large write-off could slip under the approval limit as "0". | `AdjustmentService` | Handled by design: no cost = value unknown = waits for approval (test). |
| F4 | Info | **An approver's own adjustments apply at once.** No second person for managers. | `AdjustmentService` | Accepted, matches the purchase-order decision for small offices (questions.md); every adjustment is audited with requester and reason. |
| F5 | Info | **Native SQL in the stock ledger** (upsert, row lock, capacity read, document counter). | `StockRepository`, `DocCounterRepository` | Checked: constant strings with bound parameters (SEC-23); each sets or filters `tenant_id` from the server-side context, or works on ids already loaded through tenant-filtered queries. |
| F6 | Info | **Retried requests from phones.** | receipts, putaway, transfers, adjustments | Handled: Idempotency-Key unique per company (per receipt for scans); a repeat returns the first result and moves nothing (tests). |
| F7 | Info | **Service worker scope is the whole site.** | `public/sw.js` | Checked: it never touches `/api` or `/ws` (no data, no tokens cached — SEC-01), caches only hashed build files and the last page; registered in production builds only. |
| F8 | Info | **New dependency** @zxing/browser 0.2.1 + @zxing/library 0.23.0. | `package.json` | Exact versions, more than two weeks old, approved in the v0.4 plan; `npm audit` and OSV clean; loaded only when a browser lacks BarcodeDetector. |

Checked and found sound:
- **Uploads (SEC-40–45):** JPEG/PNG only, 5 MB, extension + declared type + magic bytes must agree, header size
  checked before decoding (16,000 px cap), decoded subsampled, re-encoded as a new JPEG (EXIF gone), random name
  outside the web root, served through the product with `nosniff`, 20 uploads per minute.
- **CSV (SEC-46):** strict UTF-8, 10 MB, 5,000 rows, every row through Bean Validation, all or nothing,
  formula-safe exports.
- **Stock (SEC-26):** only StockMovementService writes stock; locks in a fixed order (bin, then stock rows by bin
  id); availability and capacity checked under the locks; `stock_movement` append-only by triggers. Tests: 16
  concurrent putaways never over-fill; 30 opposite transfers without deadlock; stock = sum of movements.
- **Access (SEC-09/10):** every new endpoint has `@PreAuthorize` (ArchUnit); every record is reached through a
  public id inside the company and checked for warehouse access (404 otherwise), including receipts, adjustments,
  bins scanned by code and stock queries; other-company tests on every API test class.
- **Errors (SEC-63):** new error codes all have user messages (errorCodes.test); details never include other
  companies' data or internals.

## Exit criteria (v0.4)

| Criterion | Result |
| --- | --- |
| End-to-end receive → putaway → transfer passes | Pass: `StockChangeApiTest.receivePutAwayAndTransferEndToEnd` through the app's own endpoints |
| Concurrent putaway (two workers, same bin) never over-fills | Pass: `StockMovementTest.twoWorkersPuttingAwayIntoTheSameBinNeverOverfillIt` (16 at once, exactly 10 fit) |
| Stock always equals the sum of movements | Pass: checked place by place after every stock test |
