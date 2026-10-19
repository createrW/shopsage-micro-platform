# Grafana dashboards

Dashboard JSON files in this directory are provisioned into Grafana automatically
(`configs/grafana/provisioning/dashboards/dashboards.yml`, provider `shopsage`); each
sub-directory becomes a Grafana folder. Edit the JSON here, not in the UI (UI edits are disabled).

TODO(Day 8): add the dashboards, all built on the `prometheus` and `jaeger` datasource uids:

- `shopsage/services.json` - RED metrics per service (`http_server_requests_seconds` rate, errors, p50/p95/p99), JVM, Hikari pool.
- `shopsage/saga.json` - Saga throughput, compensation rate, rollback-success %, Kafka consumer lag, DLT counts.
- `shopsage/infra.json` - Postgres, Redis and Kafka broker health; Jaeger span intake/drops.
