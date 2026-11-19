# Specification — Phase 1

> **Current scope: all six templates are implemented** (section 6c). Sections 1–6 describe Phase 1, which started with `PG_RECEIPT` only.

## 1. Problem statement
Government systems need unique, human-readable identifiers (receipts, licences, complaints...) whose shape is defined by
configurable templates such as `PG-[CITY]-[FY:yyyy-yy]-[SEQ:6]`. The service must resolve placeholders from a city master,
the current Indian financial year and a persistent counter, and must **never issue the same ID twice**, even under heavy
concurrent load. Phase 1 delivers this end-to-end for one template, with an extensible design for the rest.

## 2. Functional requirements
- FR1 Issue one ID (`POST /api/v1/ids/issue`) and many (`POST /api/v1/ids/bulk`, 1..1000).
- FR2 `CITY`: accept tenant_id or city_code; both resolve to the same record from `cities.csv`.
- FR3 `FY:yyyy-yy`: 1 Apr–31 Mar financial year in Asia/Kolkata (2026-04-01..2027-03-31 → `2026-27`).
- FR4 `SEQ:6`: zero-padded, starts at 1, scoped by `(template_id, tenant_id)`, persisted in PostgreSQL, survives restarts.
- FR5 List templates and cities; list the 20 most recently issued IDs (newest first).
- FR6 Validation and meaningful errors (404 unknown template/city, 400 bad input, safe 500).
- FR7 Web UI: template + city dropdowns from the API, count, generate, results, recent IDs, loading and error states.
- FR8 Schema, cities and templates load automatically on startup; existing counters are never reset.

## 3. Non-functional requirements
- NFR1 **Correctness under concurrency** (top priority): no duplicate IDs, no overlapping ranges; gaps acceptable.
- NFR2 Durability: PostgreSQL is the only source of truth for counters.
- NFR3 One command start: `docker compose up --build`.
- NFR4 Safety: no stack traces or internals in responses.
- NFR5 Extensibility: new placeholder = one new resolver bean; new template = configuration + enabling.
- NFR6 Testability: FY and formatting are pure and independently tested.

## 4. Assumptions
- A1 One city ↔ one tenant (true in `cities.csv`), so the README's "(template, city)" and the brief's "(template_id, tenant_id)" are the same scope.
- A2 The tenant is always derived from the request's `city` field, even for future templates without `[CITY]`.
- A3 City lookup is case- and whitespace-insensitive; the ID uses the canonical `city_code`.
- A4 Single backend instance is the baseline, but the design is safe for several (the lock lives in PostgreSQL).
- A5 Bulk limit of 1000 per request is a reasonable cap (configurable `idgen.max-bulk-count`).
- A6 For bulk requests the business date is captured once, so all IDs share one FY.

## 5. Ambiguities identified
1. The README points to `BRIEF.md` for the placeholder grammar. It was absent when work started, so the grammar was first inferred from the six formats (`[NAME(:ARG)?]`, `SEQ_xxx` = named counter). `BRIEF.md.pdf` appeared in the repo later; reading it confirmed the inference (incl. `[FY:yy-yy]`, `[CY:fmt]`, per-(template, city) counters) and no code change was needed. The brief also says a template whose named counter has no configuration must fail with a clear error: in Phase 1 every named-counter template returns a clear 422 because it is not enabled; real per-counter configuration checks come with Phase 2.
2. Should the counter reset each financial year? The brief scopes it by `(template_id, tenant_id)` only → **no reset**; documented as a decision to confirm.
3. `recent` response shape is unspecified → array of `{id, templateId, tenantId, sequence, createdAt}`.
4. Status for a known-but-disabled template is unspecified → 422.
5. Behaviour when `[SEQ:n]` overflows (e.g. 1,000,000 for n=6) is unspecified → 409, no ID issued.
6. `/ids/issue` with an extra `count` field: ignored.
7. `counters` table has no column for named counters (`SEQ_WS`, start 1000) → resolved in 6c with a separate `named_counters` table.

## 6. Design decisions and rationale
| Decision | Rationale |
|---|---|
| Atomic `INSERT … ON CONFLICT DO UPDATE … RETURNING` | One statement does create-or-increment under PostgreSQL's row lock; no Java read-modify-write; solves first-time race and bulk range in one step. |
| Reservation in its own transaction (`REQUIRES_NEW`) | Short lock; a later failure only creates a gap, as allowed. |
| `UNIQUE(generated_id)` + batch insert in one transaction | Defence in depth; all-or-nothing per request. |
| Generic parser + `PlaceholderResolver` beans | Parser knows syntax not meaning; new placeholders add a bean only. |
| Issuability = every placeholder supported (originally also an allowlist, later replaced by validation, see 6b) | Honest "enabled" flag; PG_RECEIPT only in Phase 1 without hardcoding template contents. |
| Static placeholders resolved once per request | Consistent FY/city for every ID in a bulk batch. |
| Pure `FinancialYearResolver` taking a `LocalDate`; `Clock` bean in Asia/Kolkata | Independently testable; time-zone boundary tested. |
| JdbcTemplate, no ORM | Explicit SQL for the critical statement. |
| nginx proxies `/api` | Same-origin, no CORS configuration. |
| Data files mounted read-only | They are configuration; never modified or copied. |

## 6a. Enhancement: admin authentication and tenant management

**Requirements.** Admin login/logout (Spring Security, BCrypt, HttpOnly session cookie, initial admin from environment
variables); tenant management UI and API (list, create, view, edit city name/code, activate/deactivate, search/filter)
on the existing `cities` table; ID generation resolves tenants from PostgreSQL and rejects unknown or inactive tenants;
`cities.csv` seeds only an empty table; tenants and counters survive restarts.

**Decisions and rationale**
| Decision | Rationale |
|---|---|
| Extend `cities` (`active`, `created_at`, `updated_at`); no new tenant table | Requested; keeps counters/issued IDs keyed by the same `tenant_id`. |
| Flyway migrations with baseline-on-migrate | Versioned, automatic, and adopts databases created before migrations existed without touching their data. |
| Seed only when `cities` is empty; CSV not even read otherwise | UI-managed data can never be overwritten; a missing CSV does not break restarts. |
| No delete API + FK `issued_ids → cities ON DELETE RESTRICT` | "Never delete tenants with issued IDs" enforced in the database, not just the UI. |
| Session cookie auth + CSRF double-submit cookie | Browser SPA on one origin (nginx proxy); cookie is HttpOnly/SameSite=Lax; CSRF protects state-changing calls. |
| Public ID-generation endpoints stay open and CSRF-exempt | Existing behaviour and the HTTP concurrency test must keep working; they use no session. |
| Tenant ID must contain a dot; city code is letters/digits only | A tenant ID can never equal a city code, so "tenant_id or city_code" resolution is unambiguous. |
| Inputs normalised (trim, lower-case id, upper-case code) then validated server-side | Canonical stored values; frontend validation is only a convenience. |
| Uniqueness enforced by PostgreSQL constraints; violated constraint → 409 naming the field | Race-free under concurrent creates; no check-then-insert window. |
| City code immutable after the tenant has issued IDs (409); rename allowed | A freed code could be reused by another tenant and collide with historical IDs. |
| Inactive tenant → HTTP 422 (unknown stays 404) | Distinguishes "does not exist" from "exists but disabled"; no sequence is consumed. |
| Initial admin created only if absent; env changes never reset a password | Prevents silent credential overwrite. Compose provides overridable local defaults, none in Java. |
| Generic login failure message | Avoids username enumeration. |

**Additional ambiguities.** Role model (only ADMIN); whether deactivation should affect already issued IDs (no);
password policy (8–72 bytes enforced for the bootstrap admin only); rate limiting (out of scope); `GET /api/v1/cities` stays
public and now returns active tenants only.

**Out of scope (admin enhancement):** multiple roles, user management, password reset/change, MFA, login lockout,
audit log of tenant changes, pagination, tenant deletion.

## 6b. Enhancement: simple immutable template management

An earlier iteration added template versioning (draft/active/retired versions, publication, audit log). It was judged
unnecessary complexity for this assignment and **removed completely**; migration `V4` converts existing databases.

**Requirements.** A template has a single immutable format. Administrators can create and view templates. There is no
edit, update or delete, and no versions, version history or draft/published/retired states. Each template id has exactly
one format; a different format needs a new template id. Issued IDs reference their template. Counter state is preserved and
template changes cannot reset counters.

**Decisions and rationale**
| Decision | Rationale |
|---|---|
| Templates move from `templates.json` into a `templates` table (id, name, format, created_at/by); JSON only seeds an empty table | Admins must be able to create templates that survive restarts; same "seed once, never overwrite" rule as tenants. |
| No update/delete API, plus triggers rejecting UPDATE/DELETE/TRUNCATE on `templates` | Immutability holds even for raw SQL, not just through the UI. |
| `UNIQUE(format)` in addition to the primary key on the id | Two templates with identical formats would produce colliding IDs; the database rejects it (409). |
| FKs `issued_ids.template_id → templates` and `counters.template_id → templates`, `ON DELETE RESTRICT` | "Issued IDs reference the template used"; a counter or ID can never outlive or dangle from its template. Declared `NOT VALID` so legacy rows are validated at startup after seeding. |
| Counters unchanged: keyed by `(template_id, tenant_id)` and never written by template management | Template creation cannot reset or alter a counter; a new id gets its own counter starting at 1 (the assignment's counter scope). |
| Issuability = validation (supported placeholders, exactly one sequence placeholder, safe literals), not an allowlist | One rule for seeded and admin-created templates; the property `idgen.enabled-templates` was removed. Only `PG_RECEIPT` qualifies among the seeded six, so Phase 1 behaviour is unchanged. |
| (superseded by 6c) `[CITY]` was mandatory here | Replaced in 6c by a shared counter for city-less formats. |
| Parsed templates cached by id | Safe because a template can never change. |
| Preview endpoint and version screens removed | Validation happens on creation (clear 400 messages); previewing was only needed for versioning. |

**Additional ambiguities.** Whether a "new format" should reuse the old counter (no: it is a new template id with its own
counter, per the stated scope); whether a mistaken template can be removed (no, by design).

**Out of scope here:** editing/deleting templates, versions, counter reset. (`CY`, named counters and city-less templates were added in 6c.)

## 6c. Enhancement: all six templates

**Requirement.** Issue IDs for all six templates in `templates.json` through a generic parser/formatter (no template-specific
code), with `CITY`, `FY:yyyy-yy`/`FY:yy-yy`, `CY:<date pattern>`, `SEQ:n` and named counters `SEQ_NAME:n` configured in the
`counters` section; PostgreSQL atomic counters; existing APIs unchanged; meaningful errors; HTTP concurrency test over
multiple templates and tenants.

**Template table**
| Template | Format | Counter scope | First number |
|---|---|---|---|
| PG_RECEIPT | `PG-[CITY]-[FY:yyyy-yy]-[SEQ:6]` | (PG_RECEIPT, tenant) | 1 |
| TL_LICENSE | `TL/[CITY]/[CY:yyyy]/[SEQ:5]` | (TL_LICENSE, tenant) | 1 |
| PGR_COMPLAINT | `PGR-[SEQ:8]` | (PGR_COMPLAINT, `*` all tenants) | 1 |
| WS_CONNECTION | `WS-[CITY]-[SEQ_WS:4]-[CY:MM-yyyy]` | (SEQ_WS, tenant) | 1000 (configured) |
| BPA_PERMIT | `BPA-[CITY]-[FY:yy-yy]-[SEQ_BPA_PERMIT:6]` | (SEQ_BPA_PERMIT, tenant) | 1 (configured) |
| EST_ALLOTMENT | `EST-[CITY]-[SEQ_EST_ALLOT:5]` | (SEQ_EST_ALLOT, tenant) | 1 (not configured; default) |

**Decisions and rationale**
| Decision | Rationale |
|---|---|
| Generic pipeline: parser → placeholder resolvers (CITY, FY, CY beans) → SequenceService → formatter | New placeholder = one resolver bean; no per-template code. Templates come from the database, seeded from `templates.json`. |
| `CY` limited to numeric date fields (`yyyy`/`yy`, `M`/`MM`, `d`/`dd`, separators `- / . _`), validated by maximal letter runs | Locale-independent, ID-safe output. Runs matter: `MMMM` is one token (month name), not `MM` twice (a bug caught by tests). |
| FY/CY evaluated once per request in Asia/Kolkata | A bulk request cannot straddle 31 Mar / 31 Dec with mixed values. |
| Named counters in a separate `named_counters(counter_name, tenant_id, next_value)` table, same atomic `INSERT … ON CONFLICT … RETURNING` | Keeps existing `counters` rows, FK and behaviour untouched; identical concurrency guarantees. |
| `start` = first number issued, applied only when the counter row is created | Restarts or edited configuration can never reset or move a counter. |
| Named counter shared by all templates using the same name (per tenant) | That is what a *named* counter is for; distinct templates with the same counter cannot reuse a number. |
| **PGR_COMPLAINT uniqueness:** identifier strings are globally unique; a format without `[CITY]` uses one counter shared by all tenants (`tenant_id = '*'`) | The default per-tenant scope would make BLR and PUN both issue `PGR-00000001`, a duplicate string, which the brief forbids. Alternative considered: uniqueness per tenant (`UNIQUE(tenant_id, generated_id)`), rejected because consumers and reviewers compare strings. Each issued row still records the requesting tenant. |
| **Unconfigured named counter (SEQ_EST_ALLOT):** property `idgen.named-counters.require-config`, default `false` → start at 1 | Conflict between the brief ("must fail with a clear error") and the explicit instruction ("use default 1"). Default follows the instruction; `true` gives the brief's behaviour (422 with a clear message). Both modes are tested. |
| Malformed `counters` section (non-integer, < 1, bad name) fails startup | Configuration errors surface immediately, not at the first request. |
| Named counter whose start does not fit its width is rejected at validation; overflow at runtime → 409 | Clear errors instead of malformed IDs. |
| Uniqueness constraint kept global: `UNIQUE(generated_id)` | Last line of defence for any residual cross-template collision (409, never a duplicate). |

**Ambiguities recorded.** (1) PGR scope vs. global uniqueness, decided above. (2) Missing named-counter configuration,
configurable as above. (3) Whether counters reset per FY/CY: no, scopes are exactly as listed. (4) Whether `start` may be
changed later: only affects counters not yet created. (5) Whether a city-less template still needs a city in the request:
yes, every request names an active tenant (needed for validation and recorded on the issued ID).

## 6d. Enhancement: dashboard analytics

Two charts (IDs per day with 7/30-day filter; IDs per template, highest first) and a total-issued card, aggregated from
`issued_ids` by `GET /api/v1/admin/analytics`. Decisions: days are calendar days in Asia/Kolkata (same zone as FY/CY in IDs);
the per-template chart follows the selected period and lists every template, including those with 0; the period is a fixed
window ending today, zero-filled; only actual counts are shown (no estimates); any period other than 7d/30d is 400.

## 6e. Enhancement: API credentials

Scoped API keys for external applications, managed in the admin UI (create, view, rotate, revoke). Decisions: key =
`egid_live_` + 256 random bits; stored as SHA-256 only (fast hash is right for high-entropy secrets) with CHECK constraints
preventing plaintext storage; full key shown once (create/rotate), `Cache-Control: no-store`; `X-API-Key` header,
stateless, role API_CLIENT, never admin; invalid/revoked key = 401 with no fallback to anonymous; scope checks (template
first, then tenant) before any counter is touched = 403; rotation invalidates the old key immediately (no grace period, for
simplicity); revocation permanent; audit of create/rotate/revoke; `last_used_at` after successful issuance, throttled to once
a minute per key; public demo kept by default, `API_KEY_REQUIRED=true` for production. Out of scope: OAuth/OIDC, per-key rate
limits, key expiry dates, multiple simultaneous keys per credential during rotation.

## 7. Out of scope for Phase 1
(Phase 1 only, now implemented in 6c: templates `TL_LICENSE`, `PGR_COMPLAINT`, `WS_CONNECTION`, `BPA_PERMIT`, `EST_ALLOTMENT`;
placeholders `CY` and named counters.) Still out of scope: FY-based counter reset; authentication/authorisation; pagination;
rate limiting; metrics/tracing; multi-instance/HA PostgreSQL; ID cancellation/lookup APIs.
