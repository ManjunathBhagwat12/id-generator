# Government ID Generator

Templated ID generation service for eGov. **All six templates in `data/templates.json` are supported** through one generic
placeholder parser and formatter (no template-specific code), with PostgreSQL-backed atomic counters, an admin module for
tenants and templates, and a React UI.

See also: **[Architecture overview](docs/architecture.md)** (system diagram, decisions and trade-offs), with
[ID generation sequence](docs/id-generation-sequence.md), [data model](docs/data-model.md) and
[concurrency](docs/concurrency.md) diagrams; [spec.md](spec.md) (requirements, assumptions, ambiguities),
[plan.md](plan.md) (design, milestones), [ai-log.md](ai-log.md) (AI usage record).

## Architecture

Modular monolith: React (nginx) → Spring Boot → PostgreSQL. Diagrams and the reasoning behind the design are in
[docs/architecture.md](docs/architecture.md).

```
frontend (nginx :3000) --/api--> backend (Spring Boot :8080) --JDBC--> postgres (:5432, volume pgdata)
                                   ^ reads data/templates.json + data/cities.csv (mounted read-only)
```

Backend packages (`com.egov.idgenerator`): `controller`, `dto`, `service` (city, template, FY, sequence,
formatting, orchestration), `parser` (template grammar), `repository` (JdbcTemplate), `entity`, `config`, `exception`.

**Concurrency strategy.** A sequence range is reserved by ONE atomic statement, never read-modify-write in Java:

```sql
INSERT INTO counters (template_id, tenant_id, next_value) VALUES (?, ?, 1 + :count)
ON CONFLICT (template_id, tenant_id) DO UPDATE SET next_value = counters.next_value + :count
RETURNING next_value - :count;      -- first number of the reserved range
```

- The increment runs inside PostgreSQL under the row lock, so concurrent ranges can never overlap.
- `ON CONFLICT` on the unique `(template_id, tenant_id)` index makes first-time counter creation race-free
  (no duplicate rows, no surfaced unique-violation).
- A whole bulk request reserves its range in one statement.
- The reservation commits in its own short transaction; issued IDs are then inserted in a second all-or-nothing
  transaction. A failure in between leaves a **gap, never a duplicate**.
- `UNIQUE(generated_id)` on `issued_ids` is a last line of defence (returns 409 and issues nothing).

## Templates and placeholders

The six supplied templates (read from `data/templates.json`; first IDs shown for Bengaluru on 18 Nov 2026, fresh counters):

| Template | Format | First ID | Counter |
|---|---|---|---|
| `PG_RECEIPT` | `PG-[CITY]-[FY:yyyy-yy]-[SEQ:6]` | `PG-BLR-2026-27-000001` | own, per city, starts at 1 |
| `TL_LICENSE` | `TL/[CITY]/[CY:yyyy]/[SEQ:5]` | `TL/BLR/2026/00001` | own, per city, starts at 1 |
| `PGR_COMPLAINT` | `PGR-[SEQ:8]` | `PGR-00000001` | own, **shared by all cities** (see below) |
| `WS_CONNECTION` | `WS-[CITY]-[SEQ_WS:4]-[CY:MM-yyyy]` | `WS-BLR-1000-11-2026` | named `SEQ_WS`, per city, starts at **1000** |
| `BPA_PERMIT` | `BPA-[CITY]-[FY:yy-yy]-[SEQ_BPA_PERMIT:6]` | `BPA-BLR-26-27-000001` | named `SEQ_BPA_PERMIT`, per city, starts at 1 |
| `EST_ALLOTMENT` | `EST-[CITY]-[SEQ_EST_ALLOT:5]` | `EST-BLR-00001` | named `SEQ_EST_ALLOT`, per city, **not configured → default 1** |

| Placeholder | Meaning |
|---|---|
| `[CITY]` | `city_code` of the requesting tenant (request may give `tenant_id` or `city_code`) |
| `[FY:yyyy-yy]`, `[FY:yy-yy]` | Indian financial year (1 Apr–31 Mar), Asia/Kolkata: 18 Nov 2026 → `2026-27` / `26-27`; 15 Feb 2027 → `2026-27` |
| `[CY:pattern]` | business date (Asia/Kolkata) with a Java date pattern of numeric fields only: `yyyy`, `yy`, `MM`, `M`, `dd`, `d`, separated by `- / . _` or nothing (`[CY:yyyy]` → `2026`, `[CY:MM-yyyy]` → `11-2026`). Month names, time fields and other letters are rejected. |
| `[SEQ:n]` | next number of the template's own counter, zero-padded to n digits (1 → `000001` for n=6) |
| `[SEQ_NAME:n]` | next number of the **named** counter `SEQ_NAME`, shared by every template using that name |

Date-based values (FY, CY) are captured **once per request**, so all IDs of one bulk request share them.

**Counter scopes** (`SequenceService`):
- `[SEQ:n]` → key `(template_id, tenant)` in table `counters`, starts at 1.
- `[SEQ_NAME:n]` → key `(counter_name, tenant)` in table `named_counters`; starts at `counters.SEQ_NAME.start` from
  `templates.json` (**the first number issued**). `start` only applies when a counter row is first created: restarts and
  reloading the file never reset or change an existing counter.
- *tenant* is the requesting tenant if the format contains `[CITY]`, otherwise `*` (one counter shared by all tenants).

**`PGR_COMPLAINT` uniqueness decision.** `PGR-[SEQ:8]` contains no city. With a counter per tenant, Bengaluru and Pune
would both issue `PGR-00000001`, so the same string would exist twice, while the brief says a duplicate is never
acceptable. **Chosen semantics: identifier strings are globally unique** (`UNIQUE(generated_id)` on `issued_ids`, unchanged).
To make that possible, a format without `[CITY]` uses one counter shared by all tenants: BLR gets `PGR-00000001`, PUN then
`PGR-00000002`. Each issued ID still records the tenant that requested it. This deviates from the default per-tenant scope
**only** for city-less formats, where a per-tenant scope cannot produce unique strings.

**Named counter without configuration (`SEQ_EST_ALLOT`).** The brief says such a template must fail with a clear error;
the later instruction for this project was to start it at 1. Both are supported by one setting:

| `NAMED_COUNTERS_REQUIRE_CONFIG` (`idgen.named-counters.require-config`) | Behaviour |
|---|---|
| `false` (default) | unconfigured named counters start at `idgen.named-counters.default-start` (1) |
| `true` | templates using an unconfigured named counter are not issuable: HTTP 422 `Named counter [SEQ_EST_ALLOT] has no configuration in the counters section of templates.json` |

## Admin module and tenant management

Administrators log in (UI: **http://localhost:3000/#/login**) to manage tenants. Tenants live in the existing
`cities` table (extended with `active`, `created_at`, `updated_at`); `data/cities.csv` is only the **initial seed**,
applied once when the table is empty. Tenants created or edited in the UI are never overwritten or reset by restarts.

UI screens: Login, Admin Dashboard (total / active / inactive), Tenant Management (search, status filter, add, edit,
view details, activate/deactivate) and the ID Generator (city dropdown lists **active tenants only**).

**Initial admin credentials** come from environment variables, never from source code:

| Variable | Meaning | Local Docker default |
|---|---|---|
| `ADMIN_USERNAME` | username of the initial admin | `admin` |
| `ADMIN_PASSWORD` | its password (8–72 bytes) | `admin12345` (**development only, change it**) |
| `SESSION_COOKIE_SECURE` | set `true` when served over HTTPS | `false` |

Override them with a `.env` file (see `.env.example`) or the shell: `ADMIN_PASSWORD='s3cret-pass' docker compose up --build`.
The admin is created only if that username does not already exist; changing the variable later does **not** reset an
existing admin's password. Passwords are stored as BCrypt hashes and never logged.

**Security summary:** Spring Security session authentication; `HttpOnly` + `SameSite=Lax` session cookie (`Secure` via
`SESSION_COOKIE_SECURE`); CSRF double-submit token (`XSRF-TOKEN` cookie → `X-XSRF-TOKEN` header) on all state-changing
admin/auth requests; `/api/v1/admin/**` requires the ADMIN role (401 anonymous, 403 otherwise); every admin input is
validated server-side; login failures return one generic message. The public ID-generation endpoints
(`/ids/issue`, `/ids/bulk`, `/ids/recent`, `/templates`, `/cities`) remain open and session-less, as before.

**Tenant rules:** tenant ID lowercase letters/digits/`_`/`-` with at least one dot (`tn.coimbatore`); city code 2–10
letters/digits (stored upper-case); both unique (enforced by PostgreSQL constraints → 409). Tenants are never deleted
(no delete API, and a foreign key from `issued_ids` blocks it); deactivate instead. Inactive tenants cannot issue IDs
(HTTP 422). The city code cannot be changed once the tenant has issued IDs (409); renaming is always allowed.

Database migrations (Flyway, `backend/src/main/resources/db/migration`) run automatically at startup; a database created
by Phase 1 is adopted in place (counters and issued IDs are untouched).

## Template management (simple, immutable templates)

Templates live in PostgreSQL (`templates` table); `data/templates.json` only seeds an **empty** table. Each template has
**one id and one immutable format**.

- Admins can **view** templates and **create** new ones (UI: `http://localhost:3000/#/admin/templates`). A new template can
  issue IDs immediately.
- There is **no edit, update or delete**: no UI, no API (`PUT`/`PATCH`/`DELETE` return 405), and PostgreSQL triggers reject
  `UPDATE`/`DELETE`/`TRUNCATE` on `templates` even from raw SQL. If you need a different format, create a **new template with
  a new template id**.
- There are no versions, drafts or publishing. Template ids are unique, and so are formats (two templates with the same
  format would generate colliding IDs); both are enforced by database constraints.
- A format is accepted only if every placeholder is supported and valid (see the table above: FY patterns, safe CY
  patterns, configured named counters whose start fits the width), it has exactly one sequence placeholder, and literal
  text contains only letters, digits and `/ _ . -`. All six supplied templates pass; the same rules apply to new ones.
- Every issued ID references its template (`issued_ids.template_id` is a foreign key to `templates`), and so does every
  implicit counter. Templates can never be altered or removed, so **counters cannot be reset by template changes**. A new
  template id has its own `[SEQ:n]` counter starting at 1; a new template using `[SEQ_WS:n]` shares the `SEQ_WS` counter.

| Method & path (admin session + CSRF header for POST) | Purpose |
|---|---|
| `GET /api/v1/admin/templates` | list templates (`issuable` flag) |
| `GET /api/v1/admin/templates/{id}` | details incl. number of IDs issued |
| `POST /api/v1/admin/templates` `{templateId, name, format}` | create (201); 400 invalid, 409 duplicate id or format |

## API credentials (external applications)

External systems call the same issuance endpoints with an **API key** in the `X-API-Key` header. Administrators manage keys
under **API Credentials** in the admin UI (`#/admin/credentials`): create (application name, description, tenant, permitted
templates), view, rotate and revoke.

- A key looks like `egid_live_` + 43 base64url characters (32 bytes from `SecureRandom`, 256 bits). The full key is shown
  **once**, when it is created or rotated; afterwards only its prefix (e.g. `egid_live_Ab3dEf9h…`) is visible.
- Only the **SHA-256 hash** is stored (a database CHECK accepts nothing but 64 hex characters in that column). A fast hash is
  appropriate for a 256-bit random secret; slow password hashes such as BCrypt are meant for low-entropy human passwords.
- A key may issue IDs **only for its tenant and permitted templates** (otherwise 403, and no counter is touched). Invalid or
  revoked keys get 401 and never fall back to anonymous access. Keys can never call `/api/v1/admin/**` (403).
- Rotation replaces the key immediately: the old key stops working at once. Revocation is permanent. Both are audited.
- `last_used_at` is updated after successful issuance, at most once per minute per key (so heavy parallel use of one key does
  not queue on its database row).

```bash
export EGID_API_KEY='egid_live_...'          # the key shown once in the admin UI
curl -s -X POST http://localhost:8080/api/v1/ids/bulk \
  -H "X-API-Key: $EGID_API_KEY" -H 'Content-Type: application/json' \
  -d '{"templateId":"PG_RECEIPT","city":"ka.bengaluru","count":3}'
# {"count":3,"ids":["PG-BLR-2026-27-000001","PG-BLR-2026-27-000002","PG-BLR-2026-27-000003"]}
```

| `API_KEY_REQUIRED` (`idgen.security.require-api-key`) | Issuance endpoints |
|---|---|
| `false` (default) | open, as before, for the public demo UI; a request that *sends* `X-API-Key` is held to that key's scope |
| `true` | require a valid `X-API-Key` (or an admin session, so the web generator still works for administrators) |

Admin API (session + CSRF): `GET /api/v1/admin/api-credentials`, `GET .../{id}` (with history), `POST /api/v1/admin/api-credentials`
`{appName, description, tenantId, templateIds[]}` (201, returns the key once), `POST .../{id}/rotate` (returns the new key once),
`POST .../{id}/revoke`. Key-bearing responses are sent with `Cache-Control: no-store`.

## Technology stack

React 18 + Vite (JavaScript, plain CSS) · Java 21 · Spring Boot 3.5 (Web, Validation, JDBC, Security) · Flyway · Maven · PostgreSQL 16 ·
Docker / Docker Compose · nginx · JUnit 5 + Testcontainers · Python 3 (stdlib) concurrency test.

## Prerequisites

- Docker with Compose v2 (that is all that is needed to run the app).
- To run backend tests locally: JDK 21, Maven 3.9+, and a running Docker daemon (Testcontainers starts PostgreSQL).
- To run the concurrency test: Python 3.8+ (no third-party packages).

## Start

```bash
docker compose up --build
```

No other setup. The schema is created automatically, `cities.csv` is loaded at startup, `templates.json` is read at
startup, and PostgreSQL data persists in the `pgdata` volume (counters survive restarts; `docker compose down -v` wipes them).

| What | URL |
|---|---|
| Web UI (ID generator) | http://localhost:3000 |
| Admin login | http://localhost:3000/#/login |
| Backend API | http://localhost:8080/api/v1 (also proxied at http://localhost:3000/api/v1) |
| PostgreSQL (pgAdmin, psql) | `localhost:5433`, database `idgen`, user `idgen`, password `idgen` (127.0.0.1 only; change with `POSTGRES_HOST_PORT`) |

## API examples

```bash
# Single ID (city may be a city_code OR a tenant_id)
curl -s -XPOST localhost:8080/api/v1/ids/issue -H 'Content-Type: application/json' \
  -d '{"templateId":"PG_RECEIPT","city":"BLR"}'
# {"id":"PG-BLR-2026-27-000001"}

# Bulk (1..1000)
curl -s -XPOST localhost:8080/api/v1/ids/bulk -H 'Content-Type: application/json' \
  -d '{"templateId":"PG_RECEIPT","city":"ka.bengaluru","count":5}'
# {"count":5,"ids":["PG-BLR-2026-27-000002", ... ]}

curl -s localhost:8080/api/v1/templates     # all 6, with "enabled": true only for PG_RECEIPT
curl -s localhost:8080/api/v1/cities
curl -s localhost:8080/api/v1/ids/recent    # latest 20, newest first
```

Admin API (requires a logged-in session; examples assume a cookie jar and CSRF header, see the UI or tests for the flow):

| Method & path | Purpose |
|---|---|
| `POST /api/v1/auth/login` (form: `username`, `password`) | log in; sets the session cookie |
| `POST /api/v1/auth/logout` | log out (204) |
| `GET /api/v1/auth/me` | `{authenticated, username}`; also issues the CSRF cookie |
| `GET /api/v1/admin/tenants?search=&status=all\|active\|inactive` | list / search / filter |
| `GET /api/v1/admin/tenants/summary` | `{total, active, inactive}` |
| `GET /api/v1/admin/analytics?period=7d\|30d` | issuance analytics: all-time total, total in period, IDs per day (zero-filled, days in Asia/Kolkata), IDs per template (all templates, highest first); 400 for any other period |
| `GET /api/v1/admin/tenants/{tenantId}` | details incl. `issuedIdCount`, `cityCodeLocked` |
| `POST /api/v1/admin/tenants` `{tenantId, cityCode, cityName}` | create (201) |
| `PUT /api/v1/admin/tenants/{tenantId}` `{cityCode, cityName}` | edit |
| `PATCH /api/v1/admin/tenants/{tenantId}/status` `{active}` | activate / deactivate |

```bash
J=$(mktemp); tok() { grep XSRF-TOKEN $J | awk '{print $7}'; }
curl -s -c $J -b $J localhost:8080/api/v1/auth/me                       # obtains the CSRF cookie
curl -s -c $J -b $J -XPOST localhost:8080/api/v1/auth/login -H "X-XSRF-TOKEN: $(tok)" -d 'username=admin&password=admin12345'
curl -s -c $J -b $J -XPOST localhost:8080/api/v1/admin/tenants -H "X-XSRF-TOKEN: $(tok)" -H 'Content-Type: application/json' \
  -d '{"tenantId":"tn.coimbatore","cityCode":"CBE","cityName":"Coimbatore"}'
```

All templates use the same endpoints:

```bash
curl -s -XPOST localhost:8080/api/v1/ids/issue -H 'Content-Type: application/json' -d '{"templateId":"WS_CONNECTION","city":"BLR"}'
# {"id":"WS-BLR-1000-11-2026"}
curl -s -XPOST localhost:8080/api/v1/ids/bulk -H 'Content-Type: application/json' -d '{"templateId":"BPA_PERMIT","city":"BLR","count":5}'
# {"count":5,"ids":["BPA-BLR-26-27-000001", ..., "BPA-BLR-26-27-000005"]}   (in order)
curl -s -XPOST localhost:8080/api/v1/ids/issue -H 'Content-Type: application/json' -d '{"templateId":"TL_LICENSE","city":"pb.amritsar"}'
# {"id":"TL/AMR/2026/00001"}
```

`GET /api/v1/templates` returns `id, name, format, enabled, counterScope` for each template.

Errors share one body: `{"timestamp","status","error","message","path"}`.

| Case | HTTP |
|---|---|
| Unknown template / unknown city / unknown tenant | 404 |
| Not logged in (admin API); invalid or revoked `X-API-Key` | 401 |
| Missing CSRF token, or not an admin (including any API key on `/api/v1/admin/**`); API key used for another tenant or template | 403 |
| Duplicate tenant ID / city code / template ID / template format; city code locked after issuance | 409 |
| Inactive tenant used for issuance | 422 |
| Invalid/missing field, count outside 1..1000, malformed JSON | 400 |
| Template that cannot issue: unsupported/invalid placeholder, invalid date pattern, unconfigured named counter in strict mode (message lists the reasons) | 422 |
| Counter exhausted for its width (e.g. `SEQ_WS` past 9999 for `[SEQ_WS:4]`) | 409 |
| Generated-ID collision refused by the database (should not happen) | 409 |
| Unexpected error (generic message, details only in server log) | 500 |

## Testing

Backend unit + integration tests (needs JDK 21 and Docker running):

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test
```

Currently 139 tests (all passing at the time of writing). Covers all six templates (first IDs, CITY by code and tenant id, FY around March/April, CY, padding, named and default start values, tenant and template isolation, shared named counters, PGR's shared counter, bulk ordering, sequence exhaustion, errors, the strict missing-configuration mode, persistence of implicit, named and shared counters across restarts), Covers template management (seed, view, create, duplicates, validation, no edit/delete/version API, database-level immutability, counters unaffected by creating templates), plus admin login (incl. cookie flags), bad credentials, 401/403 protection, CSRF, tenant create/duplicate/update/deactivate/search, inactive-tenant ID rejection, tenant persistence and non-overwriting seed across restarts, and financial year (incl. Asia/Kolkata boundary), city resolution by tenant_id and city_code, zero-padding, ID
formatting, template parsing, single/bulk issuance, unknown template/city, invalid count, recent IDs, DB duplicate
protection, and counter persistence across a full application restart.

### Concurrency test (against the running HTTP service)

```bash
docker compose up --build -d
python3 tests/concurrency_test.py --reset-db     # clean slate: scenario 2 is a true first-time allocation
python3 tests/concurrency_test.py                # or without reset (still checks uniqueness)
# options: --base-url http://localhost:8080
```

Scenario 3 issues from **every template in `data/templates.json`** across 4 tenants (half addressed by `tenant_id`), 6 parallel
requests each (bulk of 10 and single issues mixed), all released by one barrier. It derives each ID's counter scope from the
format (named counter or template id; per tenant only if the format has `[CITY]`) and checks: no sequence number handed out
twice within a scope, each bulk response is one contiguous range, returned count equals requested count, and any counter
created during the run started at exactly its configured value (`SEQ_WS` 1000) with no gaps.

Scenario 1: 50 workers released together by a barrier, each requesting 100 IDs on the same template/tenant → 5,000 IDs
must all be distinct. Scenario 2: for the other 9 cities, 20 simultaneous first-ever requests each (half by `city_code`,
half by `tenant_id`) → no failures, no duplicates, and (on fresh counters) exactly 1..20 per city. It prints total requests,
total IDs, unique IDs, duplicate count, failed requests and PASS/FAIL; exit code 0 only on PASS.
`--reset-db` truncates `counters`, `named_counters` and `issued_ids` via `docker compose exec`, so use it only on a dev stack.

## Local development without Docker for the app

```bash
docker compose up -d postgres            # published on localhost:5433 (DB_URL=jdbc:postgresql://localhost:5433/idgen)
cd backend && ADMIN_USERNAME=admin ADMIN_PASSWORD=admin12345 mvn spring-boot:run   # uses ../data/*, DB_URL etc. env overrides
cd frontend && npm install && npm run dev   # http://localhost:5173, proxies /api to :8080
```

## Data files (original notes)

- **`cities.csv`** — the city master: `tenant_id`, `city_code`, `city_name`. 10 cities.
- **`templates.json`** — 6 ID templates (`id`, `name`, `format`) plus a `counters` section configuring **named** counters
  (e.g., starting value). Plain `[SEQ:n]` placeholders use an implicit counter scoped per (template, city) and need no
  entry in `counters`.
- Placeholder grammar is specified in the assignment brief (`BRIEF.md.pdf`); the parser implements it generically (see spec.md, "Ambiguities").
- Load both files at startup; treat them as the system's configuration, not as user input to be edited.

## Known limitations

A template created by mistake cannot be removed (create a new one instead); `start` in `templates.json` cannot move an
existing counter (by design); counters never reset at a financial or calendar year change (scope is exactly as documented);
uniqueness across *different* templates relies on distinct literals plus the `UNIQUE(generated_id)` backstop (an exotic pair
of formats could collide and would then get a 409, never a duplicate); single admin role, no login rate-limiting; tenant
list is not paginated; the React UI has no automated browser tests.
