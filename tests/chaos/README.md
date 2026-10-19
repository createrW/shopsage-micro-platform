# Chaos tests (Toxiproxy + failure knobs)

TODO(Day 7): scenarios that prove failed Sagas are compensated (target: ≥ 99.5 % of failed or timed-out orders reach
`CANCELLED` with all compensation acks within 60 s). Two kinds of failure are combined:

- **Application knobs** (default 0, 0.15 in chaos runs): `PAYMENT_FAILURE_RATE`, `SHIPPING_FAILURE_RATE`,
  `INVENTORY_FAILURE_RATE`.
- **Network toxics** via Toxiproxy (`ghcr.io/shopify/toxiproxy:2.13.0`, compose profile `chaos`, API on port 8474):
  latency, timeouts and connection resets on Postgres (15432 → postgres:5432), Redis (16379 → redis:6379) and Kafka
  (39092 → a dedicated `CHAOS` listener — Kafka clients follow advertised listeners, so the broker can only be proxied
  through its own listener).

Each scenario script will set the knobs and toxics, drive load with [`tests/load/`](../load/README.md), and then check
order states and compensation acks. The exact failure mix of every run is recorded in
[`docs/experiment_log.md`](../../docs/experiment_log.md). Design: `docs/design.md` §12.
