# Deployment configuration

Per-environment Helm values for the chart at `deploy/helm/shopsage/`.

TODO(Day 10): add

- `values-minikube.yaml` - local Kubernetes: single replica per service, in-cluster Postgres/Redis/Kafka.
- `values-oke.yaml` - optional Oracle Cloud OKE on Always-Free ARM (Ampere A1); relies on the
  multi-arch (linux/arm64 + linux/amd64) images (ADR-0004).

Both turn off the compose dev-only posture: no default credentials (secrets come from Kubernetes
Secrets, never from this repo), no Grafana anonymous access, no Prometheus lifecycle API, and
pods run as non-root uid 1001 (`runAsNonRoot`).
