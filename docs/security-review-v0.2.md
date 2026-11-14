# Security review — v0.2 (SEC-82)

Date: 2026-11-14 · Scope: everything since the v0.1 review fixes (`0934165` … task 9): warehouses, layout
draft/publish, rack types, bins, label PDFs, and the browser editor (tasks 1–8 and 7b).
Method: the review prompt in docs/SPEC.md: the SEC rules that touch these features plus the OWASP Top 10
(2021). I read the new code file by file, searched for risky patterns, ran a worst-case input through the
validator, and checked behaviour against the test suites (134 backend, 57 frontend) and the browser runs of
tasks 6–9.

## Findings (most severe first)

| # | Severity | Finding | Where | Fix |
| --- | --- | --- | --- | --- |
| F1 | Medium | **No cap on bins per layout.** 2,000 racks × 20 levels × 50 bins = 2 M bins was allowed. One company's admin publishing that would hold a DB transaction and gigabytes of heap on the shared 4 GB server (denial of service for every tenant). | `LayoutValidator` | **Fixed in task 9:** `TOO_MANY_BINS` error above 50,000 bins (10× the v0.2 target; 5,000 bins publish in about 5 s). Test `aLayoutCannotMakeMoreThan50000Bins`. |
| F2 | Low | **Wrong SEC rule names in two commit messages** (5a181cb said SEC-60, a37f48d said "SEC-40 style"). This is not a code defect, but the PR description is where reviewers check which SEC rules a change touches. | git history | Corrected in `docs/decisions.md` (SEC-20 and SEC-09). From now on, look up rule names in the checklist before writing them. |
| F3 | Info | **The editor's live checks are hints only.** Rack overlap, aisle and inside-area are computed in the browser for speed, and a modified client could skip them. | `geometry.ts` | Accepted: the server runs the same checks on every save and again at publish (SEC-20); the browser never decides what is stored. |
| F4 | Info | **Label PDFs are a GET download.** No CSRF token is needed because the request changes nothing. The filename comes from the warehouse code, which is limited to `[A-Z0-9-]`, so the `Content-Disposition` header cannot be injected. At most 10,000 labels per PDF. | `BinController.labels` | Accepted. |
| F5 | Info | **The unit choice is stored in `localStorage`** (`wms.editor.unit`). | `editorStore.ts` | Accepted: a display preference, not a token or personal data (the rule against tokens in browser storage still holds; ArchUnit/lint scope unchanged). |

No IDOR, missing `@PreAuthorize`, missing tenant filter, mass assignment, string-built SQL or verbose error
was found:
- Every new endpoint goes through `WarehouseService.requireAccess` (404 when not visible), and every
  request body is a validated record.
- Bin code search uses Spring's `StartingWith`, which escapes `%` and `_`.
- Save and publish take a row lock on the warehouse, so two planners cannot interleave.
- Layout saves, publishes, bin edits and rack type changes are written to the audit log.

## Checks run

| Check | Result |
| --- | --- |
| Worst-case validation at every limit: 100 areas × 100 corners, 100-corner floor, 2,000 angled racks | 265 ms, one request; per-user rate limit applies |
| Another company: read, save or publish a layout; edit or archive a rack type; download labels; edit a bin | 404 every time, nothing written (`onlyManagersOfThisCompanyCanPublishOrChangeItsRackTypes`, `binsAreVisible…`, `anotherCompanysRackTypeCannotBeUsed`) |
| Viewer: save a layout, publish, edit a bin | 403 (`readersCanViewButNotEdit…`, new publish test, bins test) |
| Unknown JSON fields, codes outside `[A-Z0-9]`, out-of-range sizes and angles | 400 before any geometry runs (`unknownFieldsAndBadValuesAreRejected…`, Bean Validation on every new field) |
| Request body over 1 MB | 413 (SecurityHardeningTest; the largest legal layout is about 400 KB) |

## Exit criteria (v0.2)

| Criterion | Result |
| --- | --- |
| 100-rack, 5,000-bin warehouse built in under 30 minutes | Done in the browser: 1 rack type (8 fields), then 17 editor actions: floor 60 × 50 m, one 56 × 46 m area, one rack, typed position, "Build block" 10 along × 10 rows with a 3 m aisle, save, publish. The scripted run took 48 s; publishing took 5.3 s and created 100 racks and 5,000 bins. |
| Editor stays smooth on a 4 GB laptop | Production build with the CPU slowed 4× and 100 racks on screen: zoom step 36 ms median (two blank frames take 23 ms), typed rack move 33 ms, 42 MB JS heap. Before the task 9 change (one grid shape, memoized racks), the same zoom took 135 ms. |
| Layout API passes tenant and permission tests | Yes, see "Checks run"; all in CI. |
