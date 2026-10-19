# ADR-0003: Messaging contract, price authority and idempotency

- **Status:** Accepted (2026-10-18); outbox and dedupe Day 4, payloads Day 5
- **Deciders:** Yi Zhao

## Context

Saga participants exchange events through Kafka. We need: per-order ordering within a topic (not across topics;
consumers tolerate cross-topic reordering); at-least-once delivery that survives a
crash between a database commit and a publish; no duplicate side effects; independent deployability (no shared Java
event classes); one distributed trace per Saga; and trustworthy money — a client must not be able to set a price or
act as another user.

## Decision

- **Wire format:** record value = JSON text of the `DomainEvent` envelope (`eventId`, `eventType`, `schemaVersion`,
  `aggregateId`, `occurredAt`, `payload`), produced with `StringSerializer`. Headers `eventId`, `eventType`,
  `schemaVersion`, `traceparent`. Key = `orderId`.
- **Consumers** use `ErrorHandlingDeserializer` → `StringDeserializer` and parse by `eventType` into consumer-owned DTOs.
  No Java type headers, no shared producer classes. Additive changes keep `schemaVersion`; breaking ones bump it.
- **Money:** every amount is an integer in minor units with an ISO-4217 `currency` beside it (`3998` + `USD` =
  39.98 USD), `long` in Java, named `amountMinor` / `unitPriceMinor` / `totalAmountMinor`. No floating point or
  decimal strings on the wire, so totals are exact and every consumer parses them identically. The REST API uses the
  same representation.
- **Participants:** inventory, order and payment. user-service is HTTP-only and has no Kafka client.
- **Topics:** ten base topics, each with a `<topic>.DLT` twin, created up front (20 topics); broker auto-creation off.
  Day 4: `DefaultErrorHandler` (3 retries, exponential backoff) + `DeadLetterPublishingRecoverer`.
- **Transactional outbox:** `outbox(id, aggregate_type, aggregate_id, event_type, payload jsonb, headers jsonb,
  created_at, published_at)` written in the same transaction as the state change; `headers` captures the W3C trace
  context (`traceparent`, plus `tracestate`/baggage) at write time via the Micrometer `Propagator`.
- **Relay:** one active relay per service (`pg_try_advisory_xact_lock`; other replicas skip the cycle) publishes
  unpublished rows `ORDER BY created_at, id`, one after another with the idempotent producer, sets `published_at` after
  each ack and stops the batch at the first failed send, so per-aggregate order is preserved. It never copies the stored
  `traceparent` as a raw header: it extracts the stored context as the parent of an `outbox.relay` span and sends inside
  it, so the KafkaTemplate observation injects the one `traceparent` the consumer reads (spring-kafka 3.1.10 appends with
  `Headers.add`, the receiver reads `Headers.lastHeader`). From Day 4, relay polls (`tasks.scheduled.execution`) will be excluded from
  tracing by extending the `common` `ObservationPredicate`. Details: design.md §7.
- **Idempotent consumers:** `processed_events` with primary key `(consumer, event_id)` — one service may run several
  listeners for the same event — inserted in the consumer's business transaction. Every `@KafkaListener` sets an
  explicit, stable `id = "<service>.<purpose>"` (e.g. `order-service.payment-failed-compensation`). Because `idIsGroup`
  defaults to true, the id is also the consumer group, so each listener gets every partition of its topic; the same id
  is the `consumer` column. Never set `idIsGroup = false` and never share a group id between two listeners on one topic;
  the service-level `group-id: ${spring.application.name}` is only a fallback for tooling and ad-hoc consumers.
- **Price authority and context:** inventory-service owns catalog and prices. Clients never send prices; `userId` comes
  from the subject of a JWT the service validates itself (RS256 via JWKS, design.md §9), never from a body or from
  `X-User-Id`. `order.created` carries only `{orderId, userId,
  items[{sku, quantity}]}`; `inventory.reserved` carries lines with `unitPriceMinor`, `totalAmountMinor` and `currency` and is
  consumed by payment (capture) and order (authoritative total). `payment.refunded` is the payment side's compensation
  ack (`{orderId, paymentId?, amountMinor, currency, outcome}`, outcome `REFUNDED` or `VOIDED`) and is always sent in answer
  to `order.cancelled`. Every Saga event carries `orderId`, `userId` and the
  money/lines its consumers need, so no participant calls another synchronously.

## Consequences

- **Positive:** at-least-once delivery plus dedupe gives exactly-once *effects* without Kafka transactions (which would
  not cover the Postgres writes anyway).
- **Positive:** services evolve and deploy independently; a malformed record is a handled error and a DLT entry, not a
  stuck partition.
- **Positive:** price tampering is impossible by construction: clients never send prices, and inventory-service is the
  only source of `unitPriceMinor`/`totalAmountMinor`. User impersonation is prevented by JWT validation, not by network position:
  from Day 2 every service validates the token itself and reads the caller from its subject, so the `X-User-Id` header
  (stripped and re-set by the gateway from Day 6) is never an authorization input. Services are additionally
  ClusterIP-only behind a NetworkPolicy (Day 10). In the compose dev stack, services 8081-8084 are published on
  `${BIND_HOST:-127.0.0.1}` for debugging; until Day 2 they have no business endpoints and their actuator endpoints are
  unauthenticated.
- **Negative:** every service carries an outbox table, a relay and a dedupe table; relay polling adds publish latency
  (measured on Day 7).
- **Negative:** events duplicate some data (lines, amounts); payloads must stay small and versioned.
- **Negative:** per-order ordering caps parallelism at the partition count (3 locally).
- **Negative:** one active relay per service caps publish throughput. To scale, shard by `hash(aggregate_id) % N`, with
  each shard still processed in order. Do not use plain `SKIP LOCKED` across replicas, because it breaks per-aggregate
  order.
