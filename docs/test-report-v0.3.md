# Acceptance test — v0.3 (whole 3D view, after all tasks)

Date: 2026-11-14 · Local run on the **production build** (`vite preview` + backend jar + MySQL 8.4 container),
Chrome on Intel UHD Graphics. The 3D view was tested as the tenant admin and as a viewer. This sits on top of
the automated suites (143 backend, 76 frontend, green in CI) and the browser check done after each task.

## Results

| Area | Check | Result |
| --- | --- | --- |
| Navigation | "3D view" in the menu opens the only published warehouse; warehouse list has a 3D button | Pass |
| Scene | 100 racks / 5,000 bins, posts, shelves, bins on their levels, doors along walls; 7 draw calls | Pass |
| Camera | Drag to turn, scroll / + − zoom, T top view (matches the 2D plan), R reset, arrows pan | Pass (task 3 run) |
| Bin details | Click a bin: frame + panel with rack, level, slot, capacity, badges; Esc closes | Pass |
| Search | `z1-r50-04-07` (any case) flies to the bin, standing in the aisle in front of it; unknown code says so | Pass |
| Colour modes | Zone / Status with legend; pick-face bin purple; choice remembered | Pass |
| Lite map | No three.js download; racks coloured by their bins; rack grid; search opens rack and bin | Pass |
| Slow PC | Banner and switch to Lite (page test with a fake slow canvas); this PC stays at 40–90 fps even with a 20x CPU throttle, so the banner correctly stays hidden | Pass |
| Live updates | Block a bin in one tab: the other tab turns it red and its open bin panel shows Blocked, no reload; indicator "live" through the production proxy | Pass |
| Viewer | Sees 3D view, live updates, bin details; no Edit layout, no Edit in bins page | Pass |
| Phone (390 px) | No sideways scroll; header wraps | **Failed, fixed:** legend and camera buttons overlapped; now one bottom bar, stacked on phones |
| Light theme | Header, legend and panels readable (the 3D background stays dark on purpose) | Pass |
| Performance | 60 fps with 5,000 / 10,000 / 20,000 fake bins; 60 fps at 5,000 with CPU x4 | Pass |

## Not covered by this run

- Real touch gestures on a phone (pinch to zoom the 3D view): the viewer works with a mouse; on phones the Lite
  map is the practical choice.
- A weaker GPU than Intel UHD: the slow-PC banner covers it by offering the Lite map.
- Server deployment (Nginx WebSocket upgrade, gzip): v1.0 deploy task; still waiting for SSH access.

## Regression of v0.1 and v0.2 (same production build, fresh browser profile)

| Area | Check | Result |
| --- | --- | --- |
| Sign-in | Signed-out page → login; wrong password → generic message; sign in → back to the page | Pass |
| Users, roles, audit, account | Users list and invite dialog, roles page, audit log (25 rows), My account | Pass |
| Warehouses | List with Edit layout, Bins and 3D buttons | Pass |
| Layout editor | Real drag of rack R100 (28.65 → 37.4 m), save, publish keeps all 5,000 bins (0 new, 0 removed); an area dragged off the floor is refused on save ("Zone Z1 goes outside the floor") | Pass |
| Rack types, bins, labels | Rack type list, bins list, single-label PDF (application/pdf) | Pass |
| Sign out | Back to login; the API answers 401 afterwards | Pass |
