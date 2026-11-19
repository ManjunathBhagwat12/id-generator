# AI log

Tool: Claude Code (Claude Sonnet 5.5) in the VS Code extension, one session. This log only records what actually
happened in this session.

## Prompts used
1. **Main prompt (pasted by me, verbatim in the session):** "Build Phase 1 of eGov Foundation's Templated ID Generation
   Service" — implement ONE template (PG_RECEIPT) end-to-end with React/Vite, Java 21 + Spring Boot + JdbcTemplate,
   PostgreSQL, Docker Compose; atomic `UPDATE/INSERT … RETURNING` sequence allocation; unit, integration and Python HTTP
   concurrency tests; spec.md, plan.md, README.md, ai-log.md; work in 7 milestones; compile and test after each; do not
   claim tests passed unless executed.
2. No other prompts were needed; the work ran as a single agentic turn. (A later "resume where you were cut off"
   instruction was a harness/continuation message, not a new requirement.)

## Important implementation decisions (made with the AI)
- **One-statement range allocation** `INSERT … ON CONFLICT (template_id, tenant_id) DO UPDATE … RETURNING next_value - count`:
  handles first-time creation, increments and bulk in a single atomic step (no Java read-modify-write).
- **Reservation commits in its own transaction** (`REQUIRES_NEW`); issued IDs are persisted separately → gaps possible,
  duplicates not; `UNIQUE(generated_id)` as a backstop.
- **Generic parser + resolver beans**; "enabled" = allowlist ∧ all placeholders supported, so PG_RECEIPT is the only
  issuable template without hard-coding template contents.
- **Business date captured once per request** so a bulk batch cannot straddle a financial-year boundary.
- **Jackson `accept-float-as-int=false`** so `count: 2.5` is rejected instead of truncated.
- Python concurrency test uses a `threading.Barrier` so workers are released simultaneously, and checks that first-time
  counters yield exactly 1..N per city.

## AI-generated mistakes found, and how they were caught
1. **Wrong assumption about missing input.** The AI treated `BRIEF.md` as absent and inferred the grammar. A
   `BRIEF.md.pdf` later appeared in the repo; reading it (PDF text extraction via macOS PDFKit, since poppler was not
   installed) confirmed the inference and the docs were corrected. Lesson: re-check the workspace before finalising.
2. **Test bug, not product bug.** The first full test run had 1 failure of 50 (`errorBodyNeverLeaksInternals`): the AI sent
   a raw string body without a JSON content type, so the server correctly answered 415, not 400. Caught by running the
   suite (not by reading it); fixed by sending `Content-Type: application/json`. Re-run: 50/50 passing.
3. **Naming slip.** The AI first put the test clock config class `FixedClockConfig` in a file called `AbstractIntegrationTest.java`. It is package-private, so it compiled, but the name was misleading; I renamed the file to match before the first test run.
4. **Potential false confidence in the concurrency test.** A passing run alone does not prove the test is meaningful, so I
   cross-checked the database independently: 5,180 issued rows / 5,180 distinct IDs, BLR sequence 1..5000 contiguous,
   counter `next_value`=5001, and the sequence continued correctly after a backend restart and after `docker compose down/up`.

## Not verified
The React UI was built by Docker and served (HTTP 200) and the proxied API was exercised with curl, but it was **not**
clicked through in a browser or covered by automated UI tests.

---

# Enhancement session: admin authentication and tenant management

## Prompt used
"Enhancement: Add Admin Authentication and Tenant Management" (pasted by me): Spring Security with BCrypt and
HttpOnly session cookies, env-configured initial admin, tenant CRUD/status/search UI and APIs on the existing `cities`
table, seed from CSV only when empty, DB-backed tenant resolution rejecting inactive tenants, tests, Docker and docs;
"explain first, then implement incrementally, report actual test results".

## Decisions made with the AI
- Flyway with baseline-on-migrate instead of `schema.sql`, so the existing Docker volume upgrades in place.
- CSRF double-submit cookie kept ON for admin/auth routes; only the session-less public ID endpoints are exempt.
- FK `issued_ids → cities` (RESTRICT) to enforce "no deleting tenants with issued IDs" in the database.
- Tenant IDs must contain a dot so `tenant_id` and `city_code` can never collide during lookup.
- City code locked once IDs are issued (a freed code could otherwise collide with historical IDs).

## Verification actually performed
`mvn test`: 70 tests, 0 failures in one pass (the earlier 50, with CityServiceTest rewritten from 7 to 5 tests for the DB-backed service, plus 22 new admin/tenant tests). Against the pre-existing Phase 1 database volume
(5,210 issued IDs): after the upgrade the row count, distinct count and every counter value were identical. The HTTP
concurrency test passed both with and without `--reset-db`, and tenants/admin survived `docker compose down` + `up`.

## Mistakes / caveats (honest)
- No test failed on the first run of the new suite, which is not proof of correctness by itself; I therefore also checked
  behaviour manually through curl on the running nginx-proxied stack (401 anonymous, 401 bad login, HttpOnly/SameSite
  cookie, create, inactive-tenant 422) and verified the upgrade against real prior data.
- My manual "duplicate tenant" probe used a one-character city name, so it hit validation (400) rather than the duplicate
  path; the duplicate-ID and duplicate-code 409 paths are covered by the integration tests instead.
- My first attempt to edit a controller with BSD `sed -i` failed (macOS syntax) and the compile error revealed it; I redid
  the edit with a script.
- The React screens compile (Docker `vite build`) and are served, but were not clicked through in a browser.

---

# Enhancement session: immutable, versioned template management

## Prompt used
"Update the Template Management module with the following design decision: Templates and published template versions must
be immutable" (pasted by me): remove edit/delete, create templates and versions, preview/validate, atomic publish, retire the
previous version, issued IDs keep their version, history and audit preserved, counters continue, enforced in backend and
database, UI actions View / Create / New Version / Preview / Publish / History.

## Important observation
There was no Template Management module to update: templates were read-only configuration from `templates.json`. The AI
therefore built the module (storage, versions, audit, UI) and said so before coding, instead of pretending to modify one.

## Decisions made with the AI
- DB triggers for immutability, so raw SQL and future code paths are covered; tested with direct JDBC.
- Publish = one transaction under a template row lock; issuance reads the active version with a single statement.
- Counters stay keyed by `(template_id, tenant_id)` so versions share the logical counter; no reset option.
- Seeded non-enabled templates are stored as DRAFT v1 so the Phase 1 behaviour "only PG_RECEIPT issuable" is unchanged.
- `[CITY]` is required in every format (found while reasoning about uniqueness: per-tenant counters + city-less format = duplicate IDs).

## Verification actually performed
`mvn test`: 86 tests, 0 failures in the final run (70 earlier + 16 new). Against the real Phase 1 volume: V3 migrated it,
5,191 pre-existing IDs were linked to version 1 with none left NULL, counters continued across a published format change,
and the HTTP concurrency test passed afterwards. PUT/DELETE on templates return 405.

## Mistakes / caveats (honest)
- First run of the new test class: 9 of 16 failed because of MY test bugs (template name with an underscore violating the
  name rule; preview called with `pune`, which is neither a tenant id nor a city code). Fixed the tests, not the product.
- Second run: one failure from test-order coupling (a hard-coded "6 templates" while other tests add templates to the shared
  database). Fixed the assertion to look at the six seeded templates.
- My manual demo published a version 2 on the real local `PG_RECEIPT` template. Since versions can never be deleted, I
  restored the original format by publishing version 3 (the intended rollback path); the history therefore shows v1 and v2
  RETIRED and v3 ACTIVE, and 2 IDs on this dev database carry versions 2 and 3.
- The atomicity test shows no violation under load but cannot prove atomicity by itself; the guarantee rests on the
  transaction/lock design described in plan.md. I did not run a mutation check (e.g. publishing without a transaction).
- The new React screens compile and are served, but were not clicked through in a browser.

---

# Simplification: remove template versioning

## Prompt used
"Please remove the template versioning implementation completely. It is unnecessary complexity for this assignment."
(pasted by me): single immutable format per template; admin can create and view; no edit/update/delete; no versions, history or
draft/published/retired states; one format per template id; issued IDs reference the template; counters preserved and
unaffected; remove the related tables, APIs, services, UI and tests; update spec.md, plan.md, README.md.

## What the AI did
Inventoried every versioning artefact first (3 entities, 8 DTOs, version/audit repository code, publish/preview service logic,
two controllers' endpoints, 3 frontend components, 1 test class, V3 migration, doc sections, and 8 version + 19 audit rows in
my local database), then removed them. Because V3 had already been applied to the local database, it added migration V4
instead of editing V3 (editing an applied migration would break Flyway's checksum).

## Verification actually performed
`mvn test`: 82 tests, 0 failures (the 70 earlier non-template tests plus 12 new template-management tests; the 16 versioning
tests were deleted). Against the real local volume: V3→V4 applied, 10,413 issued IDs (all distinct) and the BLR counter
(10016) unchanged, version tables and column gone, FKs validated, `PG_RECEIPT` kept its original format, creating a new template
left existing counters untouched, `PUT`/`DELETE`/`/versions` return 405/405/404, a raw SQL `UPDATE` on `templates` is rejected,
and the HTTP concurrency test passed (5,000 unique IDs, 0 duplicates, 0 failures; first-time scenario passed too).

## Mistakes / caveats (honest)
- My first counter-consistency probe after the upgrade had a shell quoting bug and printed an empty value; I re-ran it correctly
  (every counter is at least max(issued sequence)+1) rather than reporting the broken output as a pass.
- My shell glob (`grep --include=*.java` under zsh) failed on the first inventory attempt and returned nothing; I redid the search
  with a plain recursive grep so the inventory was real.
- I left a convoluted leftover expression in one new test assertion and cleaned it up before the final run.
- My demo created a template `PG_RECEIPT_V2` and 2 IDs on the local dev database; templates are undeletable by design, so they stay.
- The removed preview feature is a real loss of convenience (validation now happens only on create, with clear 400 messages).
- The simplified UI compiles and is served, but was not clicked through in a browser.

---

# Enhancement session: all six templates

## Prompts used
1. When a create-template attempt in the UI failed with "Named counter [SEQ_WS] is not supported yet; Placeholder
   [CY:MM-yyyy] is not supported yet", I asked about the error (with a screenshot). The AI explained it was validation
   working as designed, gave a format that worked immediately, and proposed implementing `CY` and named counters.
2. "Extend ID Generation to All Six Templates" (pasted by me): generic parser/formatter, CITY/FY/CY/SEQ/named counters,
   named starts from `templates.json` (SEQ_EST_ALLOT default 1), scope (counter_name, tenant), PostgreSQL atomic allocation,
   validation of missing configuration and invalid date formats, review and document the PGR_COMPLAINT uniqueness ambiguity,
   UI, comprehensive tests, multi-template HTTP concurrency test, docs.

## Decisions made with the AI (flagged to me before coding)
- **Conflict found in my own instructions vs the brief:** brief = unconfigured named counter must fail; my prompt = start
  SEQ_EST_ALLOT at 1 *and* validate missing configuration. Resolved with `idgen.named-counters.require-config` (default
  follows my prompt; `true` follows the brief); both tested.
- **PGR_COMPLAINT:** chose globally unique strings; a format without `[CITY]` uses one counter shared by all tenants.
  This deviates from the per-tenant default only where per-tenant counters would produce duplicate strings.
- Named counters in a new `named_counters` table rather than altering `counters` (existing rows and FK untouched).

## Verification actually performed
- `mvn test`: 139 tests, 0 failures in the final run.
- Docker on my existing database: V5 applied; existing counters continued (`PG-BLR-2026-27-015024`); every template issued
  correct IDs through nginx (`TL/BLR/2026/00001`, `PGR-00000001`, `WS-BLR-1000-11-2026`, `BPA-BLR-26-27-000001`,
  `EST-BLR-00001`); PGR from PUN continued the shared counter (`PGR-00000003`).
- `tests/concurrency_test.py` (without `--reset-db`, to keep my local data): all three scenarios PASS; scenario 3 issued 1,008
  IDs from 144 parallel requests over 6 templates × 4 tenants with 0 duplicates and 0 failures; 11 of 21 counter scopes were
  created concurrently during the run and each started exactly at its configured value.

## AI mistakes found and how
- **Real bug, caught by the AI's own unit test:** the first `CY` validation regex allowed `MMMM` and `MMM` (it matched them as
  `MM`+`MM` / `MM`+`M`), but Java renders those as month *names* (locale-dependent letters). The parameterised rejection test
  failed, and an integration test showed the API accepting such a template (201 instead of 400). Fixed by validating maximal
  runs of the same letter; added more rejection cases (`y`, `yyy`, `yyyyy`, `ddd`, trailing/leading/double separators).
- The AI's first fix attempt for that bug was a muddled loop; it was rewritten into a simple greedy-run check before testing.
- A one-off shell listing command failed on Python f-string quoting; re-run from a script file. Not a product issue.
- Not verified: the UI changes (counter scope line, create-form help) were built and served but not clicked through in a
  browser; scenario 3 did not run against a fully reset database (I chose not to wipe local data), so scopes that already
  existed were checked for overlap and contiguity but not for their start value; fresh-start behaviour is covered by the
  integration tests and by the 11 scopes created during the run.

---

# UI refinement session

## Prompt used
"Improve the visual design and usability of the existing Government ID Generator frontend" (pasted by me): UI only, no
backend/API/schema/logic changes; navy navigation and blue primary actions; better generator (selection, single/bulk,
copy), dashboard cards, tenant table and forms, template list with formatted expressions, login; loading/success/error
states, disabled buttons while submitting, copyable IDs, field-level validation, accessibility and keyboard navigation.

## What the AI did
Added a small shared UI kit (`frontend/src/components/ui.jsx`: Button with busy state, Field with label/hint/error wired via
`aria-describedby`, Alert, Badge, CopyButton with clipboard fallback, accessible Modal with Esc/focus trap/focus restore,
ConfirmDialog replacing `window.confirm`, FormatExpression highlighting placeholders), a token-based stylesheet, and refitted
the five screens. API calls and behaviour are unchanged; no backend file was touched.

## Verification actually performed
`npm run build` succeeded. The UI was driven in headless Chrome (Playwright, outside the repo) at 1440 px and 820 px:
generator single + bulk + copy, login validation and wrong-password error, dashboard, tenant search/add/edit/deactivate
dialogs, template list/view/create with placeholder insert buttons. Checked by script: focus lands in the first field when
a dialog opens, Tab stays inside the dialog, Esc closes it. Screenshots were reviewed.

## AI mistakes found and how
- `FormatExpression` first put `aria-label` on a plain `<code>` element (not allowed by ARIA, and it made 9 elements answer
  to "Format"). Found because the automation could not uniquely find the Format field; removed the label.
- In two-column form rows a field without a hint stretched, making its input taller and misaligned (seen in screenshots);
  fixed with `align-content: start`.
- At tablet width the nav wrapped mid-list (seen in screenshots); the nav now takes its own row below 960 px.
- My first automation script used an exact label match that the required-field asterisk broke; fixed in the script.
- Not verified: screen-reader output itself (only the ARIA wiring), and real phones.
- The automated run issued about a dozen real IDs on my local database (PG_RECEIPT for Amritsar).

---

# Dashboard analytics session

## Prompt used
"Enhance the existing Admin Dashboard with useful ID issuance analytics" (pasted by me): line chart of IDs per day with
7/30-day filter, horizontal bar chart per template sorted by usage, total-issued card; `GET /api/v1/admin/analytics`
aggregating `issued_ids` with efficient SQL, admin-protected, empty data handled, no new tables; lightweight chart library,
existing design language, loading/error/empty states, responsive, real data only.

## Decisions
- Days bucketed in Asia/Kolkata (consistent with FY/CY); zero-filled with `generate_series`; one read-only REPEATABLE READ
  transaction so the three aggregates are consistent.
- Per-template chart follows the selected period and includes templates with 0.
- Chart.js 4.5.1 (no library was installed; established, responsive, tree-shakeable). Adds about 60 KB gzipped.
- Single-series charts: one validated colour (dataviz palette validator: all checks pass), no legend, value at the line end and
  at bar tips, data-table toggle for accessibility.

## Verification actually performed
`mvn test`: 145 tests, 0 failures (139 earlier + 6 analytics tests, incl. the IST midnight boundary and empty data).
Deployed with Docker; anonymous request returns 401; dashboard driven in headless Chrome at 1440 px and 820 px with no
console errors; the API's numbers (28,171 total; 5,180 on 18 Nov and 22,991 on 19 Nov IST) match what the charts show.

## Mistakes / caveats
- First render of the 30-day chart labelled the last tick "Nov 18" while the last point was Nov 19 (Chart.js auto-skip);
  seen in the screenshot and fixed by spacing labels back from today.
- The empty state was verified through the API test (zero-filled response) but not seen in a browser, because my local
  database has data.

---

# Architecture documentation session

## Prompt used
"I want to improve the architecture documentation" (pasted by me): Mermaid diagrams for the system architecture, the
ID-generation sequence, the database ERD and the concurrency mechanism, based on the actual code and migrations, with short
explanations, decisions and trade-offs (modular monolith, PostgreSQL sequence allocation), files under docs/, linked from
README.md, documentation only, verified to render on GitHub.

## What the AI did
Read the Flyway migrations (V1 to V5), `IdGenerationService.issue`, `TemplateService`, `CityService`, `CounterRepository`,
`IssuedIdRepository`, `nginx.conf` and `docker-compose.yml` first, then wrote `docs/architecture.md`,
`docs/id-generation-sequence.md`, `docs/data-model.md` and `docs/concurrency.md`. The ERD shows only constraints that exist:
`counters.tenant_id` and `named_counters` deliberately have no foreign keys (tenant `*` for city-less formats; named counters
are linked to templates only through the format text), and that is stated rather than drawn. No code changed.

## Verification actually performed
Every Mermaid block was extracted and rendered in Chrome with Mermaid 10.9.3, 11.4.1 and 11.12.1: all 5 diagrams render with
no errors in all three versions. Rendered images were reviewed. GitHub's own renderer was not used directly (no push).

## Mistakes found and how
- The step-number table under the sequence diagram did not match Mermaid's autonumbering (notes are not numbered); recounted
  the 29 messages and corrected it.
- Long subgraph titles wrapped and overlapped their contents in two diagrams (seen in the rendered images); shortened them and
  moved the details into the text.

---

# API credentials session

## Prompt used
"Implement API Credentials management ... inspired by the API key management experience in Google Cloud Console" (pasted by
me): admin UI (create, generate, copy once, view, revoke, rotate), X-API-Key authentication with 256-bit keys and an
`egid_live_` prefix, hash-only storage, tenant and template restrictions, last-used tracking, no admin access for keys, Flyway
migrations with audit data, admin endpoints, keep the public demo flow, tests listed in the prompt, README curl example.

## Decisions
- SHA-256 (not BCrypt) for key hashes: correct for 256-bit random secrets and allows one indexed lookup per request.
  CHECK constraints make plaintext storage impossible at the database level.
- The filter only acts when the header is present; an invalid key is 401 and never treated as anonymous.
- Template scope checked before the template lookup (a key cannot probe which templates exist); tenant after resolution;
  both before counter allocation.
- `last_used_at` throttled to once a minute: a write per request would serialise parallel requests on the credential row.
- Default keeps the public demo; `API_KEY_REQUIRED=true` locks issuance to keys or an admin session.

## Verification actually performed
`mvn test`: 156 tests, 0 failures (145 earlier + 11 new). HTTP concurrency test: PASS (all scenarios). Browser run (headless
Chrome): created a credential, the key matched the expected format, Esc did not dismiss the one-time key dialog before
confirmation, the Copy button put exactly the key on the clipboard, the key was absent from the page after closing, the key
issued an ID over HTTP, rotation made the old key fail (401) and the new one work (200), no console errors.

## Mistakes found and how
- A malformed credential ID in the URL returned 500 (unhandled type-mismatch exception). Found by my own test; now 400 for
  type mismatches and missing parameters across the API.
- The "Copied" state of a primary Copy button was green text on blue (unreadable), also on the ID generator; seen in a
  screenshot and fixed.
- A label was attached to a wrapper div instead of the key input; fixed before deployment.
- Two automation-script selector ambiguities (not product bugs).
- The browser runs created three credentials on my local database and their keys appeared in screenshots; I revoked all
  three afterwards.
