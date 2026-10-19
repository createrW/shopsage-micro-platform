# Experiment Log

Purpose: the single, dated record behind every number in the README. A result that is not in this file is not claimed.
Each row names the commit and the environment, so any number can be reproduced or challenged. Failed and disappointing
runs are logged too.

Status: **no runs yet.** TODO(Day 7): first baseline, spike and chaos runs. Scripts: [`tests/load/`](../tests/load/README.md),
[`tests/chaos/`](../tests/chaos/README.md).

## Metric definitions

| Metric | Definition |
|---|---|
| QPS | successful requests per second through the gateway (`:8080`) for the k6 scenario mix (k6 `http_reqs` minus failed requests, divided by the steady-state duration; for the spike, the hold window only) |
| p50 / p95 / p99 | percentiles of k6 `http_req_duration` over the same steady-state window (spike: hold window only) |
| Error rate | k6 `http_req_failed` |
| Saga rollback success % | among orders in which any Saga stage failed or timed out (injected or natural), the percentage that reached `CANCELLED` with all required compensation acks (`inventory.released` where stock was or may have been reserved, and `payment.refunded` — outcome `REFUNDED` or `VOIDED` — where money was captured or `order.cancelled` was emitted, i.e. always for a timeout) within 60 s |
| Rollback success % (compensation-required only) | the same percentage over compensation-required Sagas only: orders where stock was or may have been reserved (`PAYMENT_FAILED`, `SHIPPING_FAILED`, `TIMEOUT` in `PENDING` or `RESERVED`). `OUT_OF_STOCK` cancellations need no compensation, cannot fail the metric and are reported as their own count |
| Failed Sagas by reason | counts of `OUT_OF_STOCK` / `PAYMENT_FAILED` / `SHIPPING_FAILED` / `TIMEOUT` |

Expected failure share in chaos runs: with all three knobs at 0.15 and independent, about 38.6 % of Sagas fail
(1 − 0.85³) before any Toxiproxy-induced failures; about 15 points of that are out-of-stock rejects, which need no
compensation.

## Scenarios

| Scenario | Load shape | Failure injection | Question it answers |
|---|---|---|---|
| Baseline | constant 1,000 req/s | none | steady-state latency and resource use |
| Spike | ramp to 5,000 req/s, then hold for N minutes (k6 `constant-arrival-rate` executor); QPS and p99 are measured on the hold window only — a ramp has no steady state | none | does the system meet 5K req/s at p99 ≤ 180 ms, and what saturates first |
| Chaos | constant load, rate chosen on Day 7 | `PAYMENT_FAILURE_RATE`, `SHIPPING_FAILURE_RATE`, `INVENTORY_FAILURE_RATE` = 0.15 plus Toxiproxy toxics on Postgres / Redis / Kafka | does every failed Saga end `CANCELLED` with nothing left held |

TODO(Day 7): the k6 scenario mix (share of catalog reads, order placements, order polls), warm-up / steady-state
durations and the spike's ramp length and hold duration N.

## Environment template

Copy this block for every run (or every series of runs on one setup).

```
Date / commit:              YYYY-MM-DD / <git sha>
Host:                       <CPU model, cores, RAM, OS>
Docker VM:                  <runtime (colima / Docker Desktop / OrbStack)>, <CPU> CPU, <RAM> GiB
Service placement:          compose --profile app | services on host
Services under test:        <replicas per service>
JAVA_OPTS:                  <image default or override>
APP_MEM_LIMIT:              <e.g. 1g>
DB_POOL_SIZE:               <e.g. 20>
Kafka partitions per topic: <e.g. 3>
TRACING_SAMPLING_PROBABILITY: <e.g. 0.1>
BIND_HOST:                  <127.0.0.1>
PROMETHEUS_TARGETS:         <host | docker>
Failure knobs:              PAYMENT=<r> SHIPPING=<r> INVENTORY=<r>
Toxiproxy toxics:           <proxy: toxic type, attributes, toxicity>
k6 version / location:      <version, host or container>
Notes:                      <anything unusual: thermal throttling, other load on the machine>
```

## Results

### Baseline (1K req/s constant)

| Date | Commit | Scenario | Duration | QPS achieved | p50 | p95 | p99 | Error rate | Saga rollback success % |
|---|---|---|---|---|---|---|---|---|---|
| TODO(Day 7) | | baseline | | | | | | | n/a |

### Spike (ramp to 5K req/s, then hold; numbers from the hold window)

| Date | Commit | Scenario | Duration (ramp + hold) | QPS achieved | p50 | p95 | p99 | Error rate | Saga rollback success % |
|---|---|---|---|---|---|---|---|---|---|
| TODO(Day 7) | | spike | | | | | | | n/a |

### Chaos (15 % failure via failure knobs + Toxiproxy)

| Date | Commit | Scenario | Duration | QPS achieved | p50 | p95 | p99 | Error rate | Saga rollback success % | Rollback success % (compensation-required only) | Failed Sagas by reason (OUT_OF_STOCK / PAYMENT_FAILED / SHIPPING_FAILED / TIMEOUT) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| TODO(Day 7) | | chaos | | | | | | | | | |

## Observations and follow-ups

TODO(Day 7): per run, what saturated first (CPU, Hikari pool, Kafka consumer lag, outbox backlog), what was changed, and
the effect of the change — one paragraph each, linked to Grafana screenshots once the Day 8 dashboards exist.
