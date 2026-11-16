# Acceptance test — v0.4 (inbound and stock, after all tasks)

Date: 2026-11-16 · Local run on the dev servers (`npm run dev` + `spring-boot:run`, MySQL 8.4 container),
Chrome. Fresh company "Accept-v04b" made with the bootstrap command; tested as the **tenant admin** (desktop)
and as a **Receiver** on a phone-sized screen (390 × 844) in a separate browser profile. This sits on top of the
automated suites (191 backend, 88 frontend, green in CI) and the browser check done after each task; the
production build and its service worker were checked in task 9.

Setup data was created through the app's own API as the admin (warehouse CMB 30 × 40 m, rack type PR with
2 bins per level and 60 kg per level, 3 racks = 18 bins published, supplier SUP-001, products OIL-1L with a
12-bottle case barcode and expiry tracking, RICE-5KG), then PO-000001 for 4 cases of oil and 10 bags of rice.

## Results

| Area | Check | Result |
| --- | --- | --- |
| Products | List shows units ("CASE = 12 EA"), expiry tag, weight; CSV templates for products and suppliers download | Pass |
| Purchase orders | PO-000001 total LKR 34,100.00 (4 × 5,400 + 10 × 1,250); same person creates and approves | Pass |
| Worker home | Receiver sees only Receive, Put away, Find; count of orders waiting | Pass (count wording fixed, see below) |
| Receiving | Pick the PO, GRN-000001 opens; case barcode × 4 = 48 EA; expiry required for OIL-1L (refused without it); 11 bags refused as over the order; 10 bags accepted; close posts goods to the receiving area | Pass |
| Putaway | 10 bags (50 kg) refused, no bin has room (30 kg each); 6 bags suggested to empty bins nearest receiving; bin label typed in lowercase accepted; full bin not suggested again; 24 bottles to the next bin | Pass |
| Find | Product barcode lists receiving area + bin; bin code (lowercase) lists its contents | Pass |
| Receiver limits | Move stock form submitted by URL → "You do not have permission to do this.", nothing moved; Users and Audit APIs 403 | Pass |
| Stock page | By product: on hand 48 EA / 10 BAG, in receiving 24 / 4; Movements: 2 received, 2 put away, with who did it | Pass |
| Transfer (admin) | 2 bags A-02-01-01 → A-01-01-01 | Pass |
| Adjustment (admin) | 1 bottle damaged, LKR 450, under the limit → ADJ-000001 applied at once | Pass |
| Fill levels | `bin-status` fill: 10 kg → 34, 20 kg → 67, 21.85 kg → 73 (of 30 kg); Lite map in Status mode shows racks 01 and 02 partly full, 03 empty | Pass |
| Audit | Every step by both people recorded, from invite to adjustment | Pass (labels fixed, see below) |

## Found and fixed in this run

| # | Problem | Fix |
| --- | --- | --- |
| 1 | Audit log showed raw names for v0.4 actions ("Po approved", "Partner created") | Labels for all 32 new actions and record types |
| 2 | Audit "Record type" filter had no products, suppliers, orders, receipts or adjustments | Added |
| 3 | Home page still said "Stock arrives in the next version" | Rewritten for v0.4 |
| 4 | Worker home said "1 orders to receive" | "Orders to receive: 1" (works for any number) |
| 5 | The receiving area showed as lower-case "receiving" in stock, find and adjustments | "Receiving area" |

## Known, not fixed

- Pages a role cannot use are hidden from the menu but still open by typed URL (since v0.1); the server refuses
  every action (403, plain message), so this is cosmetic. Route guards can come with v1.0's new screens.
- The 30 kg-per-bin limit comes from the rack type (60 kg per level, 2 bins); a pallet of rice needs a stronger
  rack type. Working as designed.
- Adjustment waiting for approval was not walked in the browser this time (the admin approves its own); covered
  by `StockChangeApiTest` and checked in the task 7 browser run.
- Real phone camera scanning: checked with the browser's barcode reader in tests; a real device test is part of
  the pilot.

## Regression of v0.1 to v0.3 (same run)

| Area | Check | Result |
| --- | --- | --- |
| Sign-in | Invite link → set password → sign in, for admin and receiver | Pass |
| Users, roles, audit | Users list with Invited/Active; roles matrix incl. new permissions; audit log | Pass |
| Warehouses | List with Published, Edit layout, Bins, 3D | Pass |
| Layout editor | Loads the published plan: floor corners, two zones, three racks | Pass |
| Labels | Sheet and single-label PDFs (application/pdf) | Pass |
| 3D view | 3 racks / 18 bins, live indicator, zone and status colours, Lite map | Pass |

## Exit criteria (v0.4)

See docs/security-review-v0.4.md: end-to-end receive → putaway → transfer, concurrent putaway never over-fills,
stock equals the sum of movements: all pass in the automated suites, and the browser run above agrees.
