# Acceptance test — v0.2 (whole app, after all tasks)

Date: 2026-11-14 · Local run (backend jar + Vite, MySQL 8.4 container). The run went through the whole app in
the browser after task 9, as two users (tenant admin, and a viewer invited during the run). It is on top of
the automated suites (134 backend, 57 frontend, green in CI).

## Results

| Area | Check | Result |
| --- | --- | --- |
| Sign-in (v0.1) | Opening the editor signed out → login; wrong password → generic message; sign in → back to the editor | Pass |
| Invites (v0.1) | Admin invites a Viewer → one-time link → set password → link removed from the address bar → sign in | Pass |
| Menus (v0.1) | Viewer sees only Warehouses and Rack types; Users by URL → permission message | Pass (table now hidden under the message) |
| Editor drags | Rack drag, rotate handle (snaps 90°), resize handle (3.72 m, "use type size" appears), floor corner, door slides along its wall | Pass |
| Area drag | Area and its racks move together by the dragged distance | **Failed, fixed:** a 1 m drag threw the area to (0, 0) because the full distance was re-applied on every mouse move. Fixed; re-tested: 2 → 1 m, rack R01 moved with it |
| Two planners | Save in one tab, save a different change in another, save again in the first → "Someone else saved" → Load their version | Pass |
| Unsaved changes | Back link with unsaved edits → Stay / Leave dialog | Pass |
| Archived rack type | Rack of an archived type → publish refused with the rack named | Pass; message reworded ("archived or does not exist; choose another type") and the heading is "Fix these first" (it said "saving" on publish) |
| Publish | 100 racks, 5,000 bins; republish keeps every bin (0 new, 0 removed) | Pass |
| Bins and labels | Search, pick face, capacity "1t", A4 sheet and single-label PDFs | Pass |
| Viewer read-only | Editor only Back/Fit; dragging changes nothing; bins switches disabled, no labels (no permission); rack types no buttons | Pass |
| Audit log | Draft saved, published, rack type archived, invites, sign-ins all logged | Pass; filter lacked warehouse/rack type/bin and showed "Rack template": fixed |
| Phone (390 px) | Editor, bins, rack types, warehouses: no sideways page scroll | Pass; warehouse row buttons were cut off inside the table: now wrap |
| Light theme | Editor and pages readable | Pass |

## Not covered by this run

- Real touch gestures (pinch zoom) on a phone: the editor is built for a mouse; phones are for viewing.
- Server deployment and RAM on the real server: still waiting for SSH access (v0.1 exit item).

Test data left in the local DB: two viewer accounts (`viewer-accept@demo.lk`, password not kept;
`viewer2-accept@demo.lk`) and the archived rack type SHELF.
