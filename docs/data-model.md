# Data model

The schema after Flyway migrations V1 to V6 (`backend/src/main/resources/db/migration`). Only constraints that exist in the
database are drawn as relationships. Back to the [architecture overview](architecture.md).

```mermaid
erDiagram
    cities ||--o{ issued_ids : "issued for (FK tenant_id)"
    templates ||--o{ issued_ids : "rendered from (FK template_id)"
    templates ||--o{ counters : "implicit SEQ counter (FK template_id)"
    cities ||--o{ api_credentials : "scoped to (FK tenant_id)"
    api_credentials ||--|{ api_credential_templates : "permits (FK credential_id)"
    templates ||--o{ api_credential_templates : "permitted (FK template_id)"
    api_credentials ||--o{ api_credential_audit : "history (FK credential_id)"

    cities {
        varchar tenant_id PK "e.g. ka.bengaluru"
        varchar city_code UK "e.g. BLR, used inside IDs"
        varchar city_name
        boolean active "inactive tenants cannot issue"
        timestamptz created_at
        timestamptz updated_at
    }

    templates {
        varchar template_id PK "e.g. PG_RECEIPT"
        bigserial seq UK "display order"
        varchar name
        varchar format UK "immutable, triggers block UPDATE and DELETE"
        timestamptz created_at
        varchar created_by
    }

    counters {
        bigserial id PK
        varchar template_id FK "UK with tenant_id"
        varchar tenant_id "UK with template_id, '*' = all tenants"
        bigint next_value "next number to issue"
    }

    named_counters {
        bigserial id PK
        varchar counter_name "UK with tenant_id, e.g. SEQ_WS"
        varchar tenant_id "UK with counter_name, '*' = all tenants"
        bigint next_value "CHECK >= 1"
    }

    issued_ids {
        bigserial id PK
        varchar template_id FK
        varchar tenant_id FK
        bigint sequence_value
        varchar generated_id UK "globally unique"
        timestamptz created_at "indexed (created_at DESC, id DESC)"
    }

    api_credentials {
        uuid id PK
        varchar app_name
        varchar description
        varchar tenant_id FK
        varchar key_prefix "e.g. egid_live_Ab3dEf9h, CHECK format"
        char key_hash UK "SHA-256 hex only, CHECK 64 hex chars"
        varchar status "ACTIVE or REVOKED"
        timestamptz created_at
        varchar created_by
        timestamptz last_used_at
        timestamptz rotated_at
        timestamptz revoked_at
        varchar revoked_by
    }

    api_credential_templates {
        uuid credential_id PK, FK
        varchar template_id PK, FK
    }

    api_credential_audit {
        bigserial id PK
        uuid credential_id FK
        varchar action "CREATED, ROTATED, REVOKED"
        varchar actor
        timestamptz occurred_at
        varchar details
    }

    admin_users {
        bigserial id PK
        varchar username UK
        varchar password_hash "BCrypt only"
        boolean enabled
        timestamptz created_at
    }
```

## Tables

| Table | Key and uniqueness | Foreign keys (all `ON DELETE RESTRICT`) | Notes |
| --- | --- | --- | --- |
| `cities` | PK `tenant_id`; unique `city_code` | none | Tenants. Seeded from `cities.csv` into an empty table; never deleted, only deactivated. |
| `templates` | PK `template_id`; unique `format`; unique `seq` | none | One immutable format per template. Append-only triggers reject UPDATE, DELETE and TRUNCATE. |
| `counters` | PK `id`; unique (`template_id`, `tenant_id`) | `template_id` to `templates` | Implicit `[SEQ:n]` counters. `tenant_id` has **no** foreign key because it is `*` for formats without `[CITY]`. |
| `named_counters` | PK `id`; unique (`counter_name`, `tenant_id`) | none | `[SEQ_NAME:n]` counters, shared by every template whose format names the counter. The link to templates is the counter name inside the format text, not a foreign key. Start values come from `templates.json`. |
| `issued_ids` | PK `id`; unique `generated_id` | `template_id` to `templates`; `tenant_id` to `cities` | Every ID ever issued. Indexes on (`created_at DESC`, `id DESC`) for recent IDs and analytics, and on `tenant_id`. |
| `api_credentials` | PK `id` (UUID); unique `key_hash` | `tenant_id` to `cities` | API keys for external applications. Only the SHA-256 hash and an 18-character display prefix are stored; CHECK constraints reject anything else. `status = 'REVOKED'` exactly when `revoked_at` is set. |
| `api_credential_templates` | PK (`credential_id`, `template_id`) | `credential_id` to `api_credentials`; `template_id` to `templates` | Templates a credential may use (at least one, enforced by the application). |
| `api_credential_audit` | PK `id` | `credential_id` to `api_credentials` | Who created, rotated or revoked a credential, and when. |
| `admin_users` | PK `id`; unique `username` | none | Created from environment variables at first start; password stored as a BCrypt hash. |

**Cardinalities.** A tenant has zero or more issued IDs; a template has zero or more issued IDs and zero or more implicit
counters (one per tenant, or a single `*` counter); each issued ID belongs to exactly one tenant and one template. Because the
foreign keys are `ON DELETE RESTRICT` (and templates are additionally protected by triggers), neither a tenant nor a template
that has issued IDs can be removed.

**Not tables of the application.** `flyway_schema_history` is maintained by Flyway. Migration V3 once added
`template_versions` and `template_audit_log`; V4 removed them when template versioning was dropped, so they do not exist in
the current schema.
