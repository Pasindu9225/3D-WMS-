# Security review — v1.1 (SEC-82)

Date: 2026-11-17 · Scope: v1.1 since v1.0 was reviewed (`658eb83` … `b284246`): expiry-first reservation (FEFO), the
dashboard expiry list and 3D expiry colours, ABC classes (a scheduled job that runs for every company), cycle counts
(blind counting, approvals that book stock), customer returns and quarantine rules (30 backend files, +2,112 lines).
Method: the review prompt in docs/SPEC.md: the SEC rules that touch these features (SEC-09/10, 20–26, 46, 60, 63)
plus the OWASP Top 10 (2021). New attack surface: work that runs for all companies at once, a count whose value
depends on the counter not seeing the system figure, managers booking stock differences in bulk, and goods coming back
into stock from customers. I read the new code file by file and checked it against the test suites (223 backend,
104 frontend) and the browser runs of every task.

## Findings (most severe first)

| # | Severity | Finding | Where | Fix |
| --- | --- | --- | --- | --- |
| F1 | Low | **Count creation read every stock row first.** A whole-warehouse count loaded all stock rows of the warehouse into memory and only then refused it above 2,000 lines: a large warehouse would spike memory on a 4 GB server. | `CountService.create` | **Fixed:** the query is limited to the line limit + 1 rows; the limit is a setting (`wms.count.max-lines`, 2,000). Test `CountLimitTest`. |
| F2 | Low | **Blind counting is blind on the counting screen only.** Every role that may count (Supervisor, Inventory controller) also has `inventory:read`, so the Stock page and the 3D bin panel show the system figure for a bin. A counter who wants to "agree with the system" can look it up. | role grants (V2) | Accepted for the pilot (small trusted teams; every count line records who counted and when, and the manager sees the differences). Question for the owner in questions.md: a counting role without `inventory:read`. |
| F3 | Info | **The ABC job works for every company.** | `AbcService.weekly` | Checked: the company list is read in the system context, then each company runs in its own context and transaction (`TenantContext.callAs`); every query inside is company-filtered and the bulk update only touches ids read in that company. One company failing does not stop the others (test). |
| F4 | Info | **Bulk stock booking on approval.** | `CountService.approve` | Checked: only `counts:approve`; the plan row is locked and version-checked; every difference goes through `StockMovementService` (bin lock, row locks, availability, capacity); all or nothing; audited with each difference. |
| F5 | Info | **Goods coming back into stock.** | `ReturnService` | Checked: only from a shipped order of the user's warehouse; capped at what was picked minus earlier live returns, with the order row locked so two returns at once cannot both take the same units; stock enters only at inspection, through `StockMovementService`, into a bin of the return's warehouse; quarantine/restock bin rules enforced on the server. |
| F6 | Info | **Quarantine units count as on hand.** Dashboard stock value and low stock include them. | `DashboardService` | By design (they are physically there and owned); the Stock page shows them separately as "In quarantine" and they are never reserved. |
| F7 | Info | **Cost of "Recalculate classes".** One grouped query over 90 days of pick lines. | `AbcService.recalculate` | Acceptable at pilot size; only managers (`counts:approve`), within the 100 requests/min user limit. |

Checked and found sound:
- **Access (SEC-09/10):** every new endpoint has `@PreAuthorize` (ArchUnit); counts, returns, the returnable list,
  the dashboard and bin status are reached by public id and checked for warehouse access (404 otherwise); the counter
  view never contains expected quantities or differences (test); review, recount, approve only with `counts:approve`;
  inspect only with `inbound:receive`; other-company and wrong-role tests in every new API test class.
- **Stock (SEC-26):** FEFO reservation keeps the lock order and all-or-nothing rule; expired and quarantined stock is
  never reserved; count expected figures are read under the stock row lock; lock order plan/return row → bin → stock
  everywhere; stock = sum of movements after every run (tests and the dev database).
- **Input (SEC-20–25):** every body is a record with Bean Validation (quantities bounded, lists capped: 200 return lines,
  2,000 recount lines, counts capped by the setting); zone codes are letters and digits only and used as a prefix
  match by Spring's escaped `StartingWith`; new JPQL is constant with bound parameters.
- **Abuse (SEC-60):** count submits and found goods use the scan bucket (300/min); the dashboard expiry scan is
  capped at 1,000 lots; returns and counts are created only by managers.
- **Errors (SEC-63):** all new error codes have user messages (errorCodes test); messages name only the user's own
  bins and products.

## Exit criteria (v1.1)

| Criterion | Result |
| --- | --- |
| A full count of the pilot warehouse finishes without stopping work | Pass in tests and the browser: the system figure is read when each place is counted, so picks during a count make no false difference (CountTest; dev run CC-000001). The real pilot needs a server |
| FEFO test always picks the earliest expiry | Pass: ReservationTest (later-received, earlier-expiring lot first; expired never; no-expiry last) and the browser run (SO-000010) |
