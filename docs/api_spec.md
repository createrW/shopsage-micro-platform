# ShopSage API Specification

Status: **skeleton** (Day 1, 2026-10-18). Endpoints below are planned; each lands on the day shown. Only the actuator
endpoints exist today. Request/response schemas are filled in as each endpoint is implemented.

## Conventions

| Topic | Convention |
|---|---|
| Base path | `/api/v1/...`; breaking changes get `/api/v2` |
| Entry point | the gateway on `:8080` (Day 6). Until then each service is called directly on 8081-8084 |
| Content type | `application/json`; errors are `application/problem+json` |
| Authentication | `Authorization: Bearer <JWT>` issued by user-service (RS256, public key at a JWKS endpoint), validated by every service (Day 2) and also by the gateway (Day 6), which passes the header through unchanged |
| Caller identity | the subject (`sub`) of the JWT the service validated itself — **never from a request body or query parameter**. From Day 6 the gateway also strips any client-supplied `X-User-Id` and sets it from the subject; that header is informational and never used for authorization |
| Roles | `USER` (default) and `ADMIN`, from the JWT `roles` claim (mapped to `ROLE_*` authorities) and enforced by each service; admin-only routes are marked below |
| Pagination | `?page=<0-based>&size=<1..100>`; responses carry `content`, `page`, `size`, `totalElements` |
| Money | integer amount in minor units plus an ISO-4217 `currency`, e.g. `{"totalAmountMinor": 3998, "currency": "USD"}` = 39.98 USD; `long` in Java; field names end in `Minor` (`amountMinor`, `unitPriceMinor`, `totalAmountMinor`). Same representation in Kafka events (design.md §5.2). Prices are never accepted from clients |
| Timestamps | ISO-8601 UTC, e.g. `2026-10-18T12:00:00Z` |
| Tracing | an incoming W3C `traceparent` header is honoured and propagated (HTTP and Kafka). The trace id is currently only in the service logs (`[<traceId>,<spanId>]`), not in the response. TODO(Day 8): a small servlet filter in `common` that returns `X-Trace-Id` on every response |

## user-service (`:8081`) — Day 2

HTTP only: user-service publishes and consumes no Kafka events (it has no Kafka client).

| Method | Path | Auth | Success | Description |
|---|---|---|---|---|
| POST | `/api/v1/auth/register` | none | 201 | create an account (email, password) |
| POST | `/api/v1/auth/login` | none | 200 | exchange credentials for a JWT |
| GET | `/api/v1/users/me` | USER | 200 | profile of the authenticated user |

TODO(Day 2): request/response bodies, password policy and token lifetime.

## inventory-service (`:8082`) — Day 3

| Method | Path | Auth | Success | Description |
|---|---|---|---|---|
| GET | `/api/v1/products?page&size` | none | 200 | list the catalog with prices and available stock |
| GET | `/api/v1/products/{sku}` | none | 200 | one product (served from the Redis cache when warm) |
| POST | `/api/v1/products` | ADMIN | 201 | create a product with its price |
| PUT | `/api/v1/products/{sku}/stock` | ADMIN | 200 | set available stock |
| GET | `/api/v1/inventory/reservations/{orderId}` | USER (own order) / ADMIN | 200 | reservation lines and status for an order |

TODO(Day 3): product and reservation schemas; `PUT .../stock` semantics (absolute value vs delta) and concurrency control.

## order-service (`:8083`) — `POST` on Day 4, reads on Day 5

| Method | Path | Auth | Success | Description |
|---|---|---|---|---|
| POST | `/api/v1/orders` | USER | **202** | start a checkout Saga |
| GET | `/api/v1/orders/{id}` | USER (own order) | 200 | order with status and reason |
| GET | `/api/v1/orders?page&size` | USER | 200 | the caller's orders, newest first |
| GET | `/api/v1/orders/{id}/payment` | USER (own order) | 200 | payment state of the order — answered by payment-service, which owns the data; the gateway routes this sub-path there |
| GET | `/api/v1/admin/orders?userId=&page&size` | ADMIN | 200 | any user's orders (optional) |

**`POST /api/v1/orders`**

```http
POST /api/v1/orders
Authorization: Bearer <JWT>
Idempotency-Key: 3f0e6c1e-2b8a-4c3e-9a51-0d7f6e2b1a90
Content-Type: application/json

{ "items": [ { "sku": "SKU-001", "quantity": 2 } ] }
```

```http
HTTP/1.1 202 Accepted
Location: /api/v1/orders/8a4b6f0c-1d2e-4f3a-9b8c-7d6e5f4a3b2c
Content-Type: application/json

{ "id": "8a4b6f0c-1d2e-4f3a-9b8c-7d6e5f4a3b2c", "status": "PENDING" }
```

- `Idempotency-Key` (UUID) is **required**; it is unique per `(user_id, idempotency_key)`. Replaying the same key with
  the same body returns the original response; the same key with a different body returns **422**.
- No prices, no user id in the body: inventory-service prices the order, the user comes from the validated JWT subject.
- 202, not 201: the order exists but its outcome is decided asynchronously by the Saga. Clients poll
  `GET /api/v1/orders/{id}` until `status` is `COMPLETED` or `CANCELLED`.
- Order statuses: `PENDING`, `RESERVED`, `PAID`, `COMPLETED`, `COMPENSATING`, `CANCELLED`; `reason` (for the last two):
  `OUT_OF_STOCK`, `PAYMENT_FAILED`, `SHIPPING_FAILED`, `TIMEOUT`.
- `GET /api/v1/orders/{id}` returns **404, not 403**, for another user's order, so order ids cannot be probed.

TODO(Day 5): full order and payment response schemas.

## payment-service (`:8084`) — Day 5

No public write endpoints: payments are driven only by Saga events. The read model is exposed as
`GET /api/v1/orders/{id}/payment` (above).

## Operational endpoints (every service, Day 1)

| Method | Path | Description |
|---|---|---|
| GET | `/actuator/health` | aggregate health; `/actuator/health/liveness` and `/actuator/health/readiness` for probes |
| GET | `/actuator/info` | build, Java and app info |
| GET | `/actuator/metrics` | Micrometer metric names and values |
| GET | `/actuator/prometheus` | Prometheus exposition format |

Actuator requests are excluded from traces and from `http.server.requests` metrics. TODO(Day 6): the gateway does not
route `/actuator/**` to the outside.

## Errors

Every error is an RFC 7807 `ProblemDetail` (`application/problem+json`), produced by
`com.shopsage.common.web.GlobalExceptionHandler`:

```json
{
  "type": "urn:shopsage:problem:validation",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/orders",
  "errors": [ { "field": "items[0].quantity", "message": "must be greater than 0" } ]
}
```

| `type` | Status | When |
|---|---|---|
| `urn:shopsage:problem:not-found` | 404 | `ResourceNotFoundException`, including another user's resource |
| `urn:shopsage:problem:business-rule` | 409 | `BusinessRuleException`: a domain rule rejects the request |
| `urn:shopsage:problem:validation` | 400 | bean validation failed; `errors` lists `{field, message}` |
| `urn:shopsage:problem:rate-limited` | 429 + `Retry-After` | gateway rate limiter (Day 6) |
| `about:blank` | 400 / 404 / 405 / 415 / 500 | framework errors (malformed JSON, unknown route, wrong method, unsupported media type) and unexpected server errors; a 500 never echoes the exception message |
| `urn:shopsage:problem:unauthorized` / `urn:shopsage:problem:forbidden` (Day 2) | 401 / 403 | missing or invalid token / insufficient role — produced by each service's Spring Security resource-server chain (and by the gateway for 401 from Day 6) |
| (Day 4) | 422 | `Idempotency-Key` reused with a different body |

TODO(Day 4): the `type` URN for the 422 idempotency conflict.
