# Acceptance test — v1.2 (waves, routes, refill, putaway rules, notifications, heat map)

Date: 2026-11-18 · Local run on the dev servers (`npm run dev` + `spring-boot:run`, MySQL 8.4 container), Chrome,
company "Accept-v04b" / warehouse "CMB · Colombo store" (the earlier acceptance data with its orders), signed in as
the tenant admin on a desktop screen. On top of the automated suites (229 backend incl. ArchUnit and the live
WebSocket tests, 117 frontend; backend and frontend tests green in CI) and the security review
(docs/security-review-v1.2.md).

## Results

| Area | Check | Result |
| --- | --- | --- |
| Batch picking (exit criterion) | Ten orders over three racks in one wave walk less than a ninth of picking them one by one; cut-off wave takes the oldest 10 (WaveTest) | Pass (automated) |
| Waves | Two orders in one wave, one tote each, walked bin by bin; picked per order; wave Done; lists in a wave not offered alone (WaveTest, Waves.test) | Pass (automated) |
| Route | PL-000002 on the phone: "Show route (1 stop, about 19 m)" → 3D view draws the cyan line from the receiving area to rack A-02 | Pass |
| Refill rules | Office page loads (no rules yet), Check now and New rule offered; rule checks, one task per drop below min, FEFO source, confirm and refusals (RefillTest, Refill.test) | Pass (page) / automated (flow) |
| Putaway rules | Office page loads with the explanation and New rule; fixed bin, category → zone, only-zone refusal, product beats category (PutawayRuleTest) | Pass (page) / automated (flow) |
| Notifications | Gave PL-000002 to the admin: the bell showed "1 new" within ~1.5 s without reloading; "Pick list PL-000002 was given to you."; clicking opened the list on the phone and marked it read; list given back to anyone afterwards | Pass |
| Notifications (people and privacy) | Low stock once a day to that warehouse's dashboard people and admins; expiry summary; someone else's topic and notification refused (NotificationTest, LiveUpdatesTest) | Pass (automated) |
| Heat map | Colour by Pick heat: A-02-01-02 red (2 picks), A-02-01-01 and A-02-02-01 yellow (1 pick), others grey; legend "Not picked / 1 pick / 2 picks"; choice remembered after reload | Pass |
| Books | Dev database: stock = sum of movements for every place (0 differences); reserved on stock rows (9) = open reservations (9) | Pass |

## Found and fixed during v1.2

- Putaway rule requests failed when "only this zone" was left out (Jackson 3 refuses a missing primitive): optional
  booleans are now `Boolean` (PutawayRuleTest).
- Product category lookup threw for a product without a category (empty Optional from a null) — fixed before commit.
- A new live-socket test left its socket open and tripped the five-sockets test: it now disconnects.
- Wording: "1 picks" and "1 stops" now read "1 pick" and "1 stop".

## Not done here

- Refill and putaway flows were checked by the automated API tests, not clicked through in the browser (no rules exist
  in the acceptance company, and adding them would change its stock behaviour).
- CI: the dependency scan and the frontend `npm audit` step stay red on the `picomatch` advisory (build and test tools
  only, no fixed release yet) until the owner decides.
