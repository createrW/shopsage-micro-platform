# ShopSage Micro Platform: Project Memory

English | [简体中文](memory.zh-CN.md)

**Last updated:** 2026-10-23 · **Read with:** [plan.md](plan.md) (what comes next)

Handoff notes for whoever works on this repository next, the author or an AI coding agent. Read this file first, then
`plan.md`. Update both language versions at the end of every session (section 11).

## 1. Snapshot (2026-10-23)

| Item | State |
|---|---|
| Progress | Day 1 of 11 is done (2026-10-18). Days 2-11 and the optional cloud phase have not started. |
| Git | Branch `main`: the Day 1 commit `84aa6d5`, then a docs commit of 2026-10-23 that adds these planning files and links them from the README and the design doc. No remote yet; the author pushes to `createrW/shopsage-micro-platform`. |
| Build | Clean-clone `./mvnw verify` was green on 2026-10-18 (27 unit and 15 integration tests, none skipped). The unit tests were re-run offline and passed on 2026-10-23. |
| Measured numbers | None yet. The first runs come on Day 7. |
| Cloud | Nothing created. Oracle cut its free ARM allowance to 2 OCPU / 12 GB in June 2026, and OKE probably needs a Pay As You Go upgrade or the trial; the author picks a path at step C0 (`plan.md` 5.2-5.3). |

## 2. What exists

| Area | Contents | Paths |
|---|---|---|
| Build | Maven parent on Spring Boot 3.2.12 with Spring Cloud 2023.0.6, CVE overrides, enforcer, JaCoCo and Failsafe; Maven Wrapper 3.3.4 pinning Maven 3.9.16 (SHA-256 checked) | `pom.xml`, `mvnw`, `.mvn/wrapper/` |
| Shared library | `DomainEvent`, `Topics`, `GlobalExceptionHandler` (RFC 7807), `ResourceNotFoundException` (404), `BusinessRuleException` (409), web and observability auto-configurations; 27 unit tests | `common/` |
| Services | user 8081, inventory 8082, order 8083, payment 8084; each has an application class, `application.yml`, an empty `db/migration/` and one smoke integration test | `services/*-service/` |
| Local stack | Postgres 16 with four databases and roles, Redis 7, Kafka 3.9.2 in KRaft mode with `kafka-init` creating 20 topics, Prometheus, Grafana, Jaeger 2.20.0; kafka-ui in profile `tools`; the four services in profile `app` | `docker-compose.yml`, `configs/`, `.env.example` |
| Images | one Dockerfile selected by `ARG SERVICE`, ready for multi-arch builds, running as uid 1001 | `Dockerfile`, `.dockerignore` |
| Scripts | start the stack, stop it, set up the shell | `scripts/dev-up.sh`, `scripts/dev-down.sh`, `scripts/dev-env.sh` |
| Docs | README, design (16 sections), API spec, experiment-log template, ADR-0001 to ADR-0005, placeholder READMEs for later days | `README.md`, `docs/`, `tests/load/`, `tests/chaos/`, `data/samples/`, `configs/deploy/`, `configs/grafana/dashboards/` |
| Planning | this file and the plan, in English and Chinese | `plan.md`, `plan.zh-CN.md`, `memory.md`, `memory.zh-CN.md` |

## 3. What does not exist yet

| Missing | Planned for |
|---|---|
| Business code in every service: controllers, entities, repositories, MapStruct mappers, Flyway migrations | Days 2-5 |
| Spring Security, JWT issuance and key handling, the JWKS endpoint, ADMIN bootstrap, resource-server configuration | Day 2 |
| Redis cache code; the seed catalog in `data/samples/` | Day 3 |
| `POST /api/v1/orders`; the `outbox` and `processed_events` tables, the relay, dead-letter handling | Day 4 |
| Saga consumers, the order state machine, the timeout sweeper, the mock payment provider, the failure knobs | Day 5 |
| `services/gateway/` | Day 6 |
| k6 scripts, the Toxiproxy service (profile `chaos`), chaos scenarios, any measured number | Day 7 |
| Grafana dashboards, alert rules, SLOs, the sampling preset, the `X-Trace-Id` header | Day 8 |
| `.github/` (CI workflow, Dependabot), `.trivyignore.yaml`, images on GHCR | Day 9 |
| `deploy/helm/shopsage/`, `configs/deploy/values-minikube.yaml`, a draft `configs/deploy/values-oke.yaml` (finished at step C2) | Day 10 |
| Demo GIF, demo video, final README numbers | Day 11 |
| An Oracle Cloud account and any cloud resources | `plan.md` section 5 |

## 4. Decisions to keep

Later days build on these names. Change one only through a new ADR.

| Topic | Decision | Source |
|---|---|---|
| Databases | one PostgreSQL 16 instance with four databases and roles: `shopsage_user`, `shopsage_inventory`, `shopsage_order`, `shopsage_payment`; CONNECT revoked from PUBLIC | ADR-0001 |
| Saga | choreography, not orchestration. Order states `PENDING → RESERVED → PAID → COMPLETED`; failures go to `COMPENSATING`, then to `CANCELLED(reason)` only after the compensation acks arrive; reasons `OUT_OF_STOCK`, `PAYMENT_FAILED`, `SHIPPING_FAILED`, `TIMEOUT`; a 30 s sweeper (`SAGA_TIMEOUT_SECONDS`) | ADR-0002 |
| Prices | inventory-service owns prices; clients never send them | ADR-0003 (design §3, §5.3) |
| Topics | `order.created`, `order.cancelled`, `inventory.reserved`, `inventory.rejected`, `inventory.released`, `payment.captured`, `payment.failed`, `payment.refunded`, `shipping.dispatched`, `shipping.failed`, each with a `.DLT` twin: 20 topics, created explicitly (broker auto-create is off) | ADR-0003 |
| Messages | key = orderId; value = the JSON `DomainEvent` envelope (String serializer); headers `eventId`, `eventType`, `schemaVersion`, `traceparent`; transactional outbox plus a `processed_events(consumer, event_id)` dedupe table | ADR-0003 |
| Money | integer minor units plus an ISO-4217 currency; field names end in `Minor` | ADR-0003 (`docs/api_spec.md` conventions) |
| Auth | RS256 JWT issued by user-service and published through JWKS; every service validates the token itself; `X-User-Id` is never trusted for authorization | design §9 |
| Ports | gateway 8080, user 8081, inventory 8082, order 8083, payment 8084; Kafka `localhost:9092` from the host and `kafka:29092` inside compose; reserved for Toxiproxy: 8474, 15432, 16379, 39092 | design §3, §11, §12; README "Services and ports" |
| Versions | Spring Boot 3.2.12 with CVE overrides; Jaeger pinned to 2.20.0; multi-arch images only | ADR-0004, ADR-0005 |
| user-service | HTTP only, no Kafka client | ADR-0003 (design §3) |

## 5. Working rules for every session

- Build only the day the author names. Do not substitute the stack. Never push; the author pushes.
- Record a changed decision in a new ADR instead of rewriting an accepted one.
- Keep the topic list identical in `Topics.java`, the `TOPICS` list of the compose `kafka-init` service and `BASE_TOPICS`
  in `scripts/dev-up.sh`. `TopicsTest` fails when they drift.
- Put a number in the README only after it is logged in `docs/experiment_log.md`.
- Use only images published for both `linux/amd64` and `linux/arm64`. Never `eclipse-temurin:17-jre-alpine`
  (amd64-only) and no Bitnami images.
- Integration tests fail without Docker rather than skip. Skip them on purpose with `-DskipITs`. Change test
  expectations when behaviour changes; never delete a test to get green.
- Never commit secrets or keys: `.env`, `**/secrets/`, `*.pem` and `*.key` are gitignored, so generate test keys in
  code.
- End every session by ticking the README roadmap, updating `plan.md` and this file in both languages, and committing
  locally.

## 6. How to resume

The author's Mac (Apple Silicon, 16 GB) runs Docker through colima. JDK 17 comes from Homebrew (`openjdk@17`). Maven
is not installed globally; use `./mvnw`.

```bash
colima start --cpu 6 --memory 8           # Docker VM; also switches the docker context to colima
source scripts/dev-env.sh                 # JAVA_HOME (JDK 17) plus DOCKER_HOST and the Testcontainers socket override
./scripts/dev-up.sh                       # Postgres, Redis, Kafka (+ 20 topics), Prometheus, Grafana, Jaeger
./mvnw -q -DskipTests install             # once, and again after every change to common/
./mvnw verify                             # unit + Testcontainers integration tests; -DskipITs for unit tests only
./mvnw -pl services/user-service spring-boot:run   # run one service on the host
./scripts/dev-up.sh --with-apps --build   # or run the four services as containers
./scripts/dev-down.sh                     # stop; -v also deletes data after a confirmation
colima stop                               # give the 8 GB back
```

Local URLs: Grafana http://localhost:3000, Prometheus http://localhost:9090, Jaeger http://localhost:16686,
kafka-ui http://localhost:8089 (with `--tools`), services on ports 8081-8084.

## 7. Lessons from Day 1

| Problem | Fix |
|---|---|
| Testcontainers tests were skipped silently under colima | removed `disabledWithoutDocker`; `scripts/dev-env.sh` exports `DOCKER_HOST` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` |
| `/actuator/prometheus` returned 404 or 500 in `@SpringBootTest` | `@AutoConfigureObservability(tracing = false)` on the tests |
| Grafana could not query Jaeger 2.22 | Jaeger pinned to 2.20.0, because 2.21 removed the v1 query API Grafana 13.2 calls |
| The image build failed the Maven checksum check | the build stage installs `unzip`; without it the wrapper downloads the `.tar.gz`, whose hash does not match |
| A bare `docker compose up --wait` treats the one-shot `kafka-init`, which exits, as a failure | `dev-up.sh` names the six infrastructure services in `up --wait`, then starts `kafka-init`, runs `docker compose wait kafka-init` and checks that all 20 topics exist |
| Postgres reported healthy while init scripts were still running | the healthcheck runs `pg_isready -h 127.0.0.1` over TCP |
| `.dockerignore` entry `target/` matched only the root folder | `**/target/`, plus fixed jar names through `finalName` |
| Two Prometheus jobs double-counted or showed a permanently down job | one `file_sd` job; `PROMETHEUS_TARGETS` selects `host` or `docker` |
| Log lines printed the application name twice | the correlation pattern adds only `[traceId,spanId]` |

## 8. Open items

- **Push:** no remote yet. One way: `gh repo create createrW/shopsage-micro-platform --public --source=. --push`. The
  commits carry the author's university email; `plan.md` section 7 shows how to switch to the GitHub no-reply address
  before the first push.
- **Oracle Cloud:** sign-up status unknown. The hosting plan treats the account as required for the whole portfolio,
  and activation can take 1-3 days. Its 4 OCPU / 24 GB figure is out of date: Oracle now documents 2 OCPU / 12 GB as
  free, and OKE probably needs Pay As You Go or the trial. The author chooses path A, B or C (`plan.md` 5.3), and
  decides on path B before signing up, because sign-up starts the 30-day trial clock.
- **Heads-up for Day 2:** once the services become resource servers, anonymous calls to `/actuator/metrics` and to
  unknown routes return 401, so the Day 1 smoke tests need new expectations (`plan.md`, Day 2). The permit list must
  cover `/actuator/health/readiness` and `/liveness`, because compose healthchecks and Kubernetes probes call them
  without a token.
- **Open design questions:** listed in `plan.md` section 8.
- **Spring Boot 3.2 is past open-source end of life.** Trivy (Day 9) may flag new CVEs; handle them as ADR-0005
  describes.

## 9. References outside this repository

These are the author's private planning files. They are not in the repo; `plan.md` restates what it uses from them.

- `master_plan_2026-10-17.md`: the original brief, the 11-day plan, the metric targets (including CI ≤ 5 min) and the
  GitHub handle.
- `cloud_hosting_plan_2026-10-17.md`: hosting choices for all of the author's portfolio projects; `plan.md` section 5
  follows it and records where Oracle's current terms differ.
- `code_standard.md`: the author's repository layout standard; the README maps it onto this Maven repo.

## 10. Session log

| Date | Session | Result |
|---|---|---|
| 2026-10-18 | Day 1 | Maven monorepo, compose stack, scripts, design docs and ADR-0001 to ADR-0005; verified from a clean clone; commit `84aa6d5` |
| 2026-10-23 | Planning | Added `plan.md` and `memory.md` in English and Chinese. Checked Oracle's current free tier (A1 halved to 2 OCPU / 12 GB in June 2026; OKE likely needs Pay As You Go or the trial) and adjusted the cloud plan. Two rounds of independent review (five checkers, then three); fixes applied. Moved `POST /api/v1/orders` to Day 4 in the API spec, design §3 and §5.3, the ADR-0003 status line and the README. Unit tests re-run green. |

## 11. How to update this file

At the end of each session:

1. Update the snapshot (section 1) and the "last updated" date.
2. Move finished items from section 3 to section 2.
3. Add any new decision to section 4 together with its ADR, and any hard-won fix to section 7.
4. Add a row to the session log.
5. Make the same edits in `memory.zh-CN.md` and update the status columns in both plan files.
