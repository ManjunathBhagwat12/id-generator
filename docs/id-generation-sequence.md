# ID generation sequence

One request to `POST /api/v1/ids/bulk` (or `/issue`, which is the same flow with `count = 1`) passes through the steps below.
Participant names are the actual classes. Back to the [architecture overview](architecture.md).

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant Ctl as IdController
    participant Gen as IdGenerationService
    participant Tpl as TemplateService
    participant Prs as TemplateParser and TemplateValidator
    participant Cty as CityService
    participant Reg as PlaceholderRegistry
    participant Seq as SequenceService
    participant Ctr as CounterRepository
    participant Fmt as IdFormatter
    participant Iss as IssuedIdRepository
    participant DB as PostgreSQL

    C->>Ctl: POST /api/v1/ids/bulk {templateId, city, count}
    Note over C,Ctl: Spring Security runs first: public endpoint, no session or CSRF token needed
    Ctl->>Ctl: Bean Validation of the body (fields present, JSON well formed)
    Ctl->>Gen: issue(templateId, city, count)

    Gen->>Tpl: getForIssuance(templateId)
    Tpl->>DB: SELECT template by template_id
    Tpl->>Prs: parse format (cached per template, formats are immutable)
    Tpl->>Prs: validate (supported placeholders, exactly one SEQ, named counter configured)
    Tpl-->>Gen: parsed Template

    Gen->>Gen: check 1 <= count <= 1000
    Gen->>Cty: resolve(city)
    Cty->>DB: SELECT tenant WHERE tenant_id = ? OR city_code = ?
    Cty-->>Gen: active tenant

    Gen->>Reg: resolveStatics(template, tenant, today in Asia/Kolkata)
    Reg-->>Gen: values for CITY, FY, CY (computed once per request)

    Gen->>Seq: allocate(template, tenant, count)
    Seq->>Seq: choose counter scope
    Seq->>Ctr: allocate or allocateNamed(key, tenant, start, count)
    Ctr->>DB: INSERT ... ON CONFLICT DO UPDATE ... RETURNING first number
    Note over Ctr,DB: own transaction (REQUIRES_NEW), committed immediately
    DB-->>Ctr: first number of the reserved range
    Ctr-->>Seq: start
    Seq->>Seq: last number fits the SEQ width?
    Seq-->>Gen: start

    loop each number from start to start + count - 1
        Gen->>Fmt: format(template, static values, number)
        Fmt-->>Gen: formatted ID, zero-padded sequence
    end

    Gen->>Iss: saveAll(rows)
    Iss->>DB: batch INSERT INTO issued_ids, one transaction
    Note over Iss,DB: UNIQUE(generated_id) refuses any duplicate string
    Iss-->>Gen: saved
    Gen-->>Ctl: IDs in order
    Ctl-->>C: 200 {count, ids}
```

## Step notes

| Steps | What happens | Failure (HTTP status, body from `GlobalExceptionHandler`) |
| --- | --- | --- |
| 1–3 | Spring Security lets the public ID endpoints through without a session; the controller validates the JSON body. | Missing field or malformed JSON: 400 |
| 4–8 | The template is loaded from the `templates` table, parsed by the generic `TemplateParser` (result cached by template ID, safe because templates never change) and checked by `TemplateValidator`. | Unknown template: 404. Unsupported placeholder, invalid date pattern, unconfigured named counter in strict mode: 422 |
| 9 | The count is checked against `idgen.max-bulk-count` (1000). | 400 |
| 10–12 | The tenant is found by tenant ID or city code (case-insensitive) in `cities`. | Unknown: 404. Inactive: 422 |
| 13–14 | `CITY`, `FY` and `CY` are resolved once, so every ID in a bulk request shares the same date values. | none |
| 15–22 | `SequenceService` picks the counter: `[SEQ:n]` uses `counters` keyed by (template ID, tenant); `[SEQ_NAME:n]` uses `named_counters` keyed by (counter name, tenant); the tenant is `*` when the format has no `[CITY]`. The whole range is reserved by one atomic statement, committed on its own. See [Concurrency](concurrency.md). | Range exceeds the SEQ width: 409 (the reserved numbers become a gap) |
| 23–24 | Each ID is rendered from the parsed segments with the sequence zero-padded to its width. | none |
| 25–27 | All IDs are inserted in one transaction. | Duplicate string (should never happen): 409, nothing issued. Any other failure: 500 with a generic message; the reserved numbers become a gap. |
| 28–29 | The IDs are returned in sequence order. | none |

Because the counter is committed before the IDs are rendered and stored, the worst outcome of a failure is an unused range
(a gap). A duplicate cannot happen: the range belongs to this request alone, and the database rejects any repeated string.
