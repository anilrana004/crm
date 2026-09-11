# SecureTravels CRM — API Standards

> **Status: ratified since Phase 1 Prompt 3.** Every future endpoint must
> follow this contract. The API is REST over JSON, generated OpenAPI 3,
> consumed by a typed Next.js client.

---

## 1. Base URL & versioning

- Base path: `/api` (e.g. `/api/leads`). No `/api/v1` prefix **today**.
- **Versioning strategy (agreed):** the API is versioned by *capability* via
  URL (`/api/...`) for the foreseeable future because there is exactly one
  consumer (our own frontend/typed client) and the contract is regenerated
  atomically from the live spec. If a second external consumer ever appears,
  or a breaking change can't be done in one deploy (frontend+backend
  monorepo deploy together), introduce `/api/v2` — recorded as an ADR at
  that time. Breaking changes to a shared contract require at least a
  minor-version bump noted in `CHANGELOG`.
- Webhook callbacks from third parties live under the same `/api` tree
  (e.g. `/api/webhook/lead`) and use their own auth (HMAC), never the
  browser session.

## 2. Resource naming & HTTP semantics

| Rule | Example |
|---|---|
| Plural nouns for collections | `/api/leads`, `/api/trips/{id}/batches` |
| Sub-resources under their owner | `/api/trips/{tripId}/batches`; `PATCH /api/batches/{id}` for the resource itself |
| Actions as sub-resources, `PATCH` for partial state change | `/api/leads/{id}/status`, `/api/tasks/{id}/complete` |
| `POST` creates (201), `PATCH` updates (200), `DELETE` (204) | |
| Create returns the full resource body; `Location` header not required | |
| JSON only (`application/json`); camelCase fields | `consentGiven`, `numPersons` |
| DTO records: `*CreateRequest` / `*UpdateRequest` / `*Response` | `lead.dto` |

## 3. Authentication

- **Bearer JWT** (`Authorization: Bearer <access>`), 15-min expiry; refresh
  via `POST /api/auth/refresh`, logout via `POST /api/auth/logout`.
- RBAC at controller via `@PreAuthorize`; **ownership re-enforced in the
  service layer** — the controller annotation is a gate, the service is the
  boundary.
- Public endpoints are the exception and must be justified:
  `POST /api/webhook/lead` (HMAC-signed, rate-limited) and
  `GET /api/health`.

## 4. Pagination

Spring Data `Page<T>` shape, zero-indexed, defaults `page=0`, `size=20`:

```json
{
  "content": [...],
  "totalElements": 93,
  "totalPages": 5,
  "number": 0,
  "size": 20,
  "numberOfElements": 20
}
```

## 5. Consistent error envelope

Every error uses `ApiError` from `GlobalExceptionHandler` — **no stack
traces, no HTML error pages**:

```json
{
  "timestamp": "2026-09-09T03:25:27.123Z",
  "status": 400,
  "code": "VALIDATION_FAILED",
  "message": "Invalid request body",
  "fieldErrors": [{ "field": "mobileNumber", "message": "must not be blank" }]
}
```

Stable `code` values map 1:1 to domain exceptions:

| HTTP | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | bean validation errors (fieldErrors populated) |
| 400 | `BAD_REQUEST` | semantic violations (e.g. dup lead, bad status transition) — with `code` detail |
| 401 | `UNAUTHENTICATED` | missing/invalid JWT |
| 401 | `INVALID_SIGNATURE` | webhook HMAC mismatch/missing |
| 401 | `INVALID_CREDENTIALS` | login failure (same body for unknown email and bad password) |
| 403 | `FORBIDDEN` | cross-owner access / wrong role |
| 404 | `NOT_FOUND` | resource / id |
| 409 | `CONFLICT` | duplicate lead (non-LOST), state conflicts |
| 429 | `RATE_LIMITED` | login (5/15 min/IP) or webhook (20/min/IP); `Retry-After` header |
| 503 | `SERVICE_UNAVAILABLE` | no SALES/MANAGER available for round-robin assignment |

## 6. Webhook contract (inbound, module 9)

`POST /api/webhook/lead` — unauthenticated-by-session but **authenticated by
signature**:

- Header `X-Webhook-Signature: sha256=<hex>` = HMAC-SHA256(shared secret, **raw
  body**). Verdict is constant-time.
- Payload accepted in camelCase **or** legacy snake_case (`@JsonAlias`).
- Response `{ok, duplicate, leadId, ownerId, ownerName, note}`.
- Statuses: 201 created / 200 duplicate:true / 400 (missing fields, invalid
  mobile, `consent_given` false, oversized field or payload) / 401 / 429 /
  503. Every call writes a `webhook_logs` row (payload bounded to 10,000 chars).
- Oversized requests (>16 KB) are rejected before any audit log write.

Full pact of the endpoint lives in the IT suite (`WebhookAutomationIT`) and
the live smoke script (`webhook-smoke.ps1` — dev-only).

## 7. OpenAPI generation → typed client (the contract pipeline)

1. Backend serves the spec live at `GET /v3/api-docs`; Swagger UI at `/docs`.
2. `frontend/openapi.json` is refreshed from that endpoint (`curl` in the
   deploy runbook / `npm run` note, see README).
3. `npm run openapi:gen` runs `openapi-typescript` → `frontend/src/openapi/generated.ts`.
4. `frontend/src/lib/api.ts` re-exports `components["schemas"]` types and is
   the **only** place that touches `fetch` / tokens / error mapping.

**Discipline:** regenerate the client in the same change as any backend
payload change — CI does not enforce it yet; a pre-commit check is a Phase-2
candidate. `tsc --noEmit` failing on an absent schema type is the safety net.

## 8. API design rules for new endpoints

- Controllers are thin: parse DTOs → call service → map to `*Response`.
- Every `*Request` validated with Jakarta Bean Validation; semantic checks in
  the service raise typed exceptions (never raw `IllegalArgumentException`
  surface as 500).
- Money as decimal numbers (never floats); dates ISO-8601 (`yyyy-MM-dd`),
  instants ISO-8601 UTC (`Instant`).
- Prefer PATCH for partial updates; no full-entity PUT unless upsert is
  semantically needed (targets use PUT).
- Do not return entities — always DTOs.
- Ranges/lists default to reasonably small page sizes; always cap.