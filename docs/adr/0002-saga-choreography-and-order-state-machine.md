# ADR-0002: Saga choreography and the order state machine

- **Status:** Accepted (2026-10-18); implementation Day 5
- **Deciders:** Yi Zhao

## Context

Checkout spans three databases (inventory, payment, order). Distributed transactions (2PC/XA) are unavailable across
Kafka and Postgres in practice and would couple availability of all participants. A Saga — local transactions plus
compensations — is required. It can be **orchestrated** (a coordinator sends commands and tracks state) or
**choreographed** (participants react to each other's events). The system must also survive participants that never
answer, events that arrive late or twice, and 15 % injected failures.

## Decision

**Choreography** over Kafka, with **order-service owning the authoritative order state machine**:

- Stages: reserve (inventory; compensation: release) → capture (payment; compensation: refund) → ship (simulated in
  order-service). Events and consumers are listed in `docs/design.md` §5.1.
- States: `PENDING → RESERVED → PAID → COMPLETED`; any failure or timeout goes `→ COMPENSATING(reason) → CANCELLED(reason)`
  with reasons `OUT_OF_STOCK`, `PAYMENT_FAILED`, `SHIPPING_FAILED`, `TIMEOUT`. `inventory.reserved` and
  `payment.captured` arrive on different topics in either order, so `PENDING → PAID` is also valid (capture first): the
  total comes from whichever arrives first (equal by construction), and a later `inventory.reserved` only fills in
  `reservationId` and the lines (no-op on `COMPENSATING`/`CANCELLED`).
- An order becomes `CANCELLED` **only after all required compensation acks arrived**: `inventory.released` if stock was or
  may have been reserved, `payment.refunded` if money was captured **or `order.cancelled` was emitted** (a capture may be
  in flight). payment-service therefore always answers `order.cancelled` with `payment.refunded`, outcome `REFUNDED`
  (it had captured) or `VOIDED` (it had not, and it records the void so a late `inventory.reserved` is never captured),
  idempotent per `orderId` — mirroring inventory's empty-lines `inventory.released`. No extra topic is needed.
- **Timeouts:** an order-service sweeper emits `order.cancelled {orderId, reason: TIMEOUT}` for orders `PENDING` or
  `RESERVED` longer than `SAGA_TIMEOUT_SECONDS` (default 30), using a conditional update on state so it cannot race a
  `→ PAID` transition. Both acks (`inventory.released` and `payment.refunded` with either outcome) are required before
  `CANCELLED(TIMEOUT)`. inventory-service releases on `order.cancelled` and also expires reservations itself after
  `SAGA_TIMEOUT_SECONDS + 15`.
- **Late events:** a `payment.captured` for an order in `COMPENSATING` or `CANCELLED` makes order-service re-emit
  `order.cancelled` so payment refunds; in `PENDING` or `RESERVED` it is a normal capture (→ `PAID`); in `PAID` or
  `COMPLETED` it is a duplicate and a no-op. With the payment ack required this should no longer fire after `CANCELLED`;
  it stays as defence in depth and counts as a rollback failure if it does. Release-before-reserve and duplicate acks are
  no-ops (idempotent consumers, ADR-0003).

## Consequences

- **Positive:** no coordinator to deploy, scale or lose; each participant only knows the events it consumes, so adding a
  step (e.g. a notification service) does not touch the others.
- **Positive:** the state machine gives one place to answer "where is this order?" and defines the rollback metric
  precisely (CANCELLED with all acks within 60 s).
- **Negative:** the flow is implicit — it is only visible through the topic table, the state machine and traces. This is
  why one trace must span the whole Saga (`traceparent` in Kafka headers and in the outbox).
- **Negative:** cyclic event dependencies are easy to introduce; the topic table in design.md is the review checklist.
- **Revisit if** the Saga grows beyond ~5 stages or needs human steps — orchestration (or a workflow engine) becomes
  easier to reason about at that point.
