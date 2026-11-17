# Acceptance test — v1.1 (expiry, counts, ABC, returns, quarantine)

Date: 2026-11-17 · Local run on the dev servers (`npm run dev` + `spring-boot:run`, MySQL 8.4 container), Chrome,
company "Accept-Admin" (the v1.0 acceptance company with its earlier orders), as the tenant admin on a desktop
screen. Each feature was run in the browser right after it was built (same day, same data); after a PC restart the
servers were started again and the database checked once more. This sits on top of the automated suites (221
backend incl. ArchUnit, 104 frontend, green in CI) and the security review (docs/security-review-v1.1.md).

## Results

| Area | Check | Result |
| --- | --- | --- |
| Expiry-first (FEFO) | A lot received last but expiring first (OIL-1L, 2026-12-27, bin A-02-03-01) was reserved for SO-000010 before older lots expiring in 2027 | Pass |
| Dashboard | "Expiring soon (1)": 31–60 days band, A-02-03-01, 12 EA, 40 days left; nothing shown when empty | Pass |
| 3D / Lite map | Colour by Expiry: rack holding the 40-day lot amber; legend with six bands | Pass |
| ABC classes | Products page: Recalculate classes → "2 A, 0 B, 0 C", Class column filled | Pass |
| Cycle count | CC-000001 for zone A: 5 places in walking order (another company's stock in a similar bin code correctly not included); counting screen shows no system figures; one bin counted 10 against 11; review: one difference, −450.00 LKR; approved | Pass |
| Count booking | After approval A-02-02-01 holds 10; one COUNT movement of 1 in the ledger | Pass |
| Quarantine zone | Zone Q with rack 04 (6 bins) added through the layout API and published | Pass |
| Return | SO-000009 "Return goods": 18 EA can come back; RT-000001 with 4 Not wanted + 2 Damaged (two lines of one product); nothing moved before inspection | Pass |
| Inspection | Line 1 restocked into A-02-02-01, line 2 quarantined into Q-04-01-01, expiry required for OIL-1L; return Closed; Cancel no longer offered once a line was inspected | Pass |
| Quarantine rules | Stock page: OIL-1L "In quarantine 2"; never reserved and never suggested for putaway (ReturnTest) | Pass |
| Books | After the PC restart: stock = sum of movements for every place (0 differences); reserved on stock rows (9) = open reservations (9); movements this run: RECEIVE 4, PUTAWAY 4, TRANSFER 1, ADJUST 1, PICK 4, SHIP 4, COUNT 1, RETURN 2 | Pass |

## Found and fixed during v1.1

- A count resent by a phone after the count moved to review was refused (fixed in task 3, test).
- The new-return page allowed one reason per product, so a mixed return could not be split (fixed before the run).
- Two auth tests failed when the Docker clock ran about a second ahead (test margins widened).
- Security review F1: count creation no longer loads every stock row first (CountLimitTest).

## Exit criteria (SPEC v1.1)

| Criterion | Result |
| --- | --- |
| A full count of the pilot warehouse finishes without stopping work | Pass locally: the system figure is read when each place is counted, so picking can go on (CountTest; CC-000001). The pilot itself needs a server |
| FEFO test always picks the earliest expiry | Pass (ReservationTest; SO-000010) |
