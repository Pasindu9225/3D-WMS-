# Acceptance test — v1.0 (outbound, reports, after all tasks that need no server)

Date: 2026-11-17 · Local run on the dev servers (`npm run dev` + `spring-boot:run`, MySQL 8.4 container), Chrome.
Company "Accept-Admin" (from the v0.4 run, with its earlier orders); tested as the **tenant admin** (desktop), as
**Kamal, Receiver** and as **Nimal, Picker** on a phone-sized screen (390 × 780), each in its own browser profile.
This sits on top of the automated suites (212 backend incl. ArchUnit, 97 frontend, green in CI), the browser check
after each task, the security review (docs/security-review-v1.0.md) and the ZAP baseline (docs/zap-baseline-v1.0.md).

## Results

| Area | Check | Result |
| --- | --- | --- |
| Purchase order | PO-000003, 2 cases of OIL-1L (= 24 EA), LKR 10,800.00; approved by the same admin | Pass |
| Receiving (Kamal, phone) | GRN-000002; expiry required for OIL-1L (refused without it); 24 EA with expiry 2027-08-31; "All received"; close posts to the receiving area | Pass |
| Putaway (Kamal) | Three nearest empty bins suggested (9.5 m from receiving); bin typed in lowercase accepted; "Put 24 EA in A-02-02-01. 0 left in receiving." | Pass |
| Sales order | SO-000009 for Kandy Super Mart, customer order no. KSM-9001, ship-to filled from the customer's address | Pass |
| Release | Reserved oldest first: 7 EA from the older lot (A-02-01-02, 2027-06-30), 13 EA from the new lot; pick list PL-000004 in bin order | Pass |
| Task board (admin) | PL-000004 set to Urgent and given to Nimal; counts per stage shown | Pass |
| Pick list (Nimal, phone) | Urgent lists first; wrong bin refused ("Wrong bin: go to A-02-01-02"), Picked stays disabled | Pass |
| Short pick | "Fewer here": found 5 of 7, reason Damaged → line shows 5 / 7 short, rest of its reservation freed | Pass |
| Pick | 13 EA from A-02-02-01 → "Everything on this list is done. Take it to the dispatch area." | Pass |
| Packing (admin) | 19 refused ("more than was picked (18 EA)"); 18 accepted; 2 cartons, 19.5 kg → DN-000002 | Pass |
| Papers | Delivery note: 200, `application/pdf`, file DN-000002.pdf, PDF-only CSP | Pass |
| Dispatch | Vehicle WP LB-5521, driver Ruwan Silva → SH-000002; "Nothing packed is waiting" | Pass |
| Dashboard | Today: 1 receipt (24 units), 1 shipment (18 units), 1 order released, 1 shipped; low stock RICE-5KG | Pass |
| Export | movements.xlsx (type SHIP) downloads as movements-CMB-2026-11-17.xlsx | Pass |
| Books | Database check after the run: stock = sum of all movements for every place (0 differences); reserved on stock rows (4) = open reservations (4) | Pass |

## Found in this run

- No app defects. (The browser tool's simulated tap on "Fewer here" landed on the label above it because of a
  coordinate offset in phone emulation; the button itself opens its dialog, as the PickPage tests also show.)
- Before the run, the screenshots for the user guide showed the scales icon on every phone "Waiting for you"
  row; fixed in `2717142`.

## Exit criteria (SPEC v1.0)

| Criterion | Result |
| --- | --- |
| Receive → putaway → pick → pack → ship with no stock mismatch | Pass (this run and DispatchTest). The 2-week pilot waits for a server and a pilot customer |
| All v0.1–v1.0 SEC rules verified | All except SEC-66 (HTTPS) and SEC-83 (backups): ready and rehearsed locally, finished on the server |
| ZAP scan clean of high issues | Pass on the local rehearsal; repeat on the real domain |
