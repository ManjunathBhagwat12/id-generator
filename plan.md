# Plan

## Architecture overview
Modular monolith: `frontend` (React build served by nginx) → `backend` (Spring Boot) → `postgres`.
Request flow for issuance: controller → `IdGenerationService` → [`TemplateService`, `CityService`, `PlaceholderResolver`s]
→ `SequenceService` → `CounterRepository` (atomic range) → `IdFormatter` → `IssuedIdRepository` → response.

## Component responsibilities
| Component | Responsibility |
|---|---|
| `TemplateParser` | Format string → `Literal`/`Placeholder` segments; validates syntax and SEQ width. |
| `TemplateService` | Loads/parses templates.json at startup; decides issuability. |
| `CityCsvParser` / `CityLoader` / `CityService` | Load cities.csv into PostgreSQL (upsert) and resolve tenant_id/city_code. |
| `PlaceholderResolver` (`CITY`, `FY`) | Request-constant placeholder values. |
| `FinancialYearResolver` | Pure FY maths. |
| `SequenceService` / `CounterRepository` | Atomic range reservation; overflow check. |
| `IdFormatter` | Zero-padding and rendering. |
| `IssuedIdRepository` | Batch persistence, recent query. |
| `GlobalExceptionHandler` | Uniform safe error body. |

## Database model
- `cities(tenant_id PK, city_code UNIQUE, city_name)`
- `counters(id, template_id, tenant_id, next_value, UNIQUE(template_id, tenant_id))`
- `issued_ids(id, template_id, tenant_id, sequence_value, generated_id UNIQUE, created_at)` + index `(created_at DESC, id DESC)`

## API design
`POST /api/v1/ids/issue`, `POST /api/v1/ids/bulk`, `GET /api/v1/templates`, `GET /api/v1/cities`, `GET /api/v1/ids/recent`
(contracts and error codes in README).

## Milestones (all done)
1. Project setup + DB connectivity (pom, schema.sql, Compose-ready config).
2. Config loading + city resolution.
3. PG_RECEIPT generation (parser, FY, formatter).
4. Atomic allocation + bulk.
5. React frontend.
6. Automated tests + HTTP concurrency test.
7. Docker Compose + documentation.

## Admin and tenant management (enhancement)

**Components added.** `SecurityConfig` (filter chain, BCrypt, JSON 401/403 handlers, CSRF cookie), `AdminBootstrap`
(env-driven initial admin), `AdminUserRepository`, `TenantAdminService`, `AdminTenantController`, `AuthController`;
`CityRepository` extended; `CityService` now queries PostgreSQL (active-only resolution, 422 for inactive);
`CityLoader` seeds only an empty table. Frontend: hash-routed React app (Generator, Login, Dashboard, Tenants) with an
auth context and a fetch wrapper that sends the CSRF header.

**Data model changes (Flyway V2).** `cities` + `active`, `created_at`, `updated_at`; new `admin_users`; index
`issued_ids(tenant_id)`; FK `issued_ids.tenant_id → cities.tenant_id ON DELETE RESTRICT`. `counters` and `issued_ids`
rows, the atomic allocation SQL and the ID format are unchanged.

**API additions.** `POST /api/v1/auth/login`, `POST /api/v1/auth/logout`, `GET /api/v1/auth/me`, and
`/api/v1/admin/tenants` (GET list, POST, GET `/{id}`, PUT `/{id}`, PATCH `/{id}/status`, GET `/summary`).

**Milestones.** A1 migrations + Flyway; A2 DB-backed tenant resolution and seed-if-empty; A3 Spring Security + login/logout;
A4 tenant admin API; A5 React screens; A6 tests, upgrade check against an existing Phase 1 volume, Docker, docs.

**Tests added.** Login success/failure/cookie flags/logout; 401 on every admin route; CSRF rejection; tenant create,
normalisation, duplicate id/code, validation, update, code lock, deactivate/reactivate, search/filter, DB-level delete
protection; inactive tenant rejected for issue and bulk with no sequence consumed; restart persistence including
non-overwriting seed and unchanged admin password; plus the entire Phase 1 suite.

## Template management (simple, immutable)

**Data model.** `templates(seq, template_id PK, name, format UNIQUE, created_at, created_by)` with append-only triggers
(no UPDATE/DELETE/TRUNCATE); `issued_ids.template_id` and `counters.template_id` are FKs to it (`ON DELETE RESTRICT`).
Migration history: V3 introduced versioning, V4 removed it (kept as migrations because V3 had already been applied; V4
carries the active format into `templates.format` and drops `template_versions`, `template_audit_log` and
`issued_ids.template_version`).

**Components.** `TemplateRepository` (insert/read only), `TemplateValidator` (pure), `TemplateService` (runtime lookup + cache),
`TemplateAdminService` (list, get, create), `TemplateSeeder`, `AdminTemplateController` (GET list, GET one, POST); React `Templates`
page with a list, a View dialog and a Create dialog.

**Milestones.** C1 migration V4 and removal of version tables/columns; C2 simplify repository, services, controller and seeder;
C3 simplify UI; C4 replace the versioning tests with template-management tests; C5 verify upgrade on the existing database
(counters and issued IDs preserved) and concurrency; C6 docs.

## All six templates

**Components.** `CyPlaceholderResolver` (new), `FyPlaceholderResolver` (`yyyy-yy`, `yy-yy`), `CityPlaceholderResolver`;
`PlaceholderRegistry` returns a human-readable *problem* per placeholder (used by validation and 422 messages);
`NamedCounterConfig` (loads `counters` from `templates.json` at every startup, fails fast on malformed entries);
`SequenceService` decides the scope (implicit vs named counter; per tenant vs `*`) and calls
`CounterRepository.allocate` / `allocateNamed` (same atomic upsert, own `REQUIRES_NEW` transaction).

**Data model (Flyway V5).** `named_counters(id, counter_name, tenant_id, next_value ≥ 1, UNIQUE(counter_name, tenant_id))`.
`counters` unchanged (a `'*'` tenant row is used for city-less formats). `issued_ids.generated_id` stays globally unique.

**Milestones.** D1 CY resolver + named-counter config + V5; D2 scope selection in SequenceService; D3 validation messages
and 422 reasons; D4 UI (counter scope shown, create-form help); D5 tests (unit, all-template integration, strict mode,
restart persistence); D6 HTTP concurrency scenario over all templates; D7 Docker verification on the existing database; D8 docs.

**Tests added.** Unit: CY patterns incl. rejection of `MMMM`/time/locale patterns, FY `yy-yy` and Feb/March/April
boundaries, padding 5/8 digits, named-counter config parsing and fail-fast, validator over the six supplied formats in both
policies. Integration: first ID of every template, bulk contiguity and ordering, tenant and template isolation, shared named
counter across templates, PGR shared counter, inactive/unknown tenant, invalid count, exhaustion (409), strict mode (422),
restart persistence of implicit, named and shared counters. HTTP: scenario 3 of `tests/concurrency_test.py`.

## Dashboard analytics

`GET /api/v1/admin/analytics?period=7d|30d` (admin only, read-only GET). Three aggregate queries over `issued_ids`, run in
one read-only REPEATABLE READ transaction so the numbers agree: totals (`count(*)` + `count(*) FILTER (...)`), IDs per day
(`generate_series` over the period LEFT JOIN a `GROUP BY (created_at AT TIME ZONE 'Asia/Kolkata')::date`, so empty days are 0),
and IDs per template (`templates` LEFT JOIN a grouped count, highest first). Period bounds are computed from the business
clock, and the `created_at` range uses the existing `idx_issued_ids_recent` index. No new tables. Frontend: Chart.js 4.5.1
(line + horizontal bar, only the used components registered), one series per chart in the app's blue, hover tooltips,
crosshair, a data-table toggle per chart, loading/error/empty states. Tests: `AnalyticsIntegrationTest` (6).

## API credentials

Flyway V6 (`api_credentials`, `api_credential_templates`, `api_credential_audit`); `security` package (`ApiKeyGenerator`,
`ApiKeyAuthenticationFilter` added to the Spring Security chain before anonymous authentication, `ApiKeyAuthentication`,
`ApiClient`); `ApiCredentialService` and repository; `AdminApiCredentialController`; scope checks in
`IdGenerationService.issue`; React `Credentials` page with one-time key reveal. Tests: `ApiCredentialIntegrationTest` (10),
`ApiKeyRequiredIntegrationTest` (1).

## Test strategy
- Unit: FY (boundaries, time zone), city resolution against the real CSV, padding, formatting, parser.
- Integration (Testcontainers PostgreSQL): issuance, bulk, errors, recent, DB duplicate protection, restart persistence.
- System: `tests/concurrency_test.py` over HTTP against the Compose stack.

## With another day
- Templates: an audited way to retire (not delete) a template; optional per-period counter scopes (e.g. reset per FY) as an explicit template property; locale-aware `CY` month names if ever required.
- Admin: audit log of tenant changes, password change/reset, login throttling, multiple roles, pagination, browser (Playwright) tests.
- Phase 2: remaining five templates — `CY` resolver, named counters (`counter_name` column + `start` from config), `[SEQ_*]` support, templates without `[CITY]`.
- Decide on FY counter reset (add period to counter key).
- Pagination/filters for issued IDs; idempotency keys for retries.
- Observability (Micrometer metrics, structured logs), load test at higher scale, multiple backend replicas in the concurrency test.
- Frontend component tests and an automated browser test.
- Flyway for versioned migrations; CI pipeline.
