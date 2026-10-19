#!/usr/bin/env bash
#
# Bring up the ShopSage local dev stack (Docker Compose) and print where everything is.
#
# Sequence (see docs/design.md, "Deployment"):
#   1. preflight: docker CLI, compose v2, daemon reachable, unzip and VM size warnings
#   2. .env from .env.example if missing
#   3. infra: postgres redis kafka prometheus grafana jaeger [kafka-ui] with --wait
#   4. kafka-init one-shot: wait for it, then verify all 20 topics exist
#   5. apps (--with-apps): build the images (with --build or when missing), then up with --wait
#   6. summary of URLs/credentials + next steps
#
# Infra services are named explicitly instead of a bare `up --wait`, because
# --wait treats the one-shot kafka-init (which exits) as a failure.
#
# Compatible with macOS /bin/bash 3.2: no associative arrays, no mapfile.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

INFRA_SERVICES="postgres redis kafka prometheus grafana jaeger"
APP_SERVICES="user-service inventory-service order-service payment-service"
# Must match Topics.all() in common and TOPICS in docker-compose.yml (kafka-init).
BASE_TOPICS="order.created order.cancelled inventory.reserved inventory.rejected inventory.released payment.captured payment.failed payment.refunded shipping.dispatched shipping.failed"
INFRA_WAIT_TIMEOUT=240
APP_WAIT_TIMEOUT=300

WITH_APPS=false
WITH_TOOLS=false
BUILD=false
PULL=false

# --- output helpers ------------------------------------------------------------
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'; C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_RESET=$'\033[0m'
else
  C_BOLD=""; C_DIM=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_RESET=""
fi

step() { printf '%s==>%s %s\n' "$C_BOLD" "$C_RESET" "$*"; }
ok()   { printf '%s  ok%s  %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%sWARN%s  %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }
die()  { printf '%sERROR%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; exit 1; }

usage() {
  cat <<EOF
Usage: scripts/dev-up.sh [options]

Start the ShopSage local stack: PostgreSQL 16, Redis 7, Kafka (KRaft) + topics,
Prometheus, Grafana and Jaeger. Safe to re-run; already-running containers are kept.

Options:
  --with-apps   also build/start the four services (compose profile "app") and point
                Prometheus at their containers (PROMETHEUS_TARGETS=docker)
  --tools       also start kafka-ui on http://localhost:8089 (compose profile "tools")
  --build       rebuild the service images before starting them (with --with-apps)
  --pull        pull the latest versions of the pinned images first
  -h, --help    show this help

Environment:
  Values in .env (created from .env.example on first run) or the shell override the
  defaults, e.g. GRAFANA_ADMIN_PASSWORD, BIND_HOST, KAFKA_ADVERTISED_HOST,
  PROMETHEUS_TARGETS (host|docker).
EOF
}

# --- flags ---------------------------------------------------------------------
while [ $# -gt 0 ]; do
  case "$1" in
    --with-apps) WITH_APPS=true ;;
    --tools)     WITH_TOOLS=true ;;
    --build)     BUILD=true ;;
    --pull)      PULL=true ;;
    -h|--help)   usage; exit 0 ;;
    *)           usage >&2; echo >&2; die "unknown option: $1" ;;
  esac
  shift
done

if $BUILD && ! $WITH_APPS; then
  warn "--build only affects the service images; add --with-apps to build and start them"
fi

# Reads KEY from the shell environment first, then from .env (what compose does),
# falling back to DEFAULT. Only handles plain KEY=value lines, which is all .env.example uses.
env_value() {
  local key="$1" default="$2" value=""
  if [ -n "${!key:-}" ]; then
    printf '%s' "${!key}"
    return
  fi
  if [ -f .env ]; then
    value="$(grep -E "^[[:space:]]*${key}=" .env | tail -n 1 | sed -E "s/^[[:space:]]*${key}=//; s/^[\"']//; s/[\"']$//" || true)"
  fi
  printf '%s' "${value:-$default}"
}

# --- 1. preflight --------------------------------------------------------------
step "Preflight checks"
command -v docker >/dev/null 2>&1 \
  || die "docker CLI not found on PATH. Install Docker Desktop, OrbStack, or colima + the docker CLI (brew install colima docker docker-compose)."
docker compose version >/dev/null 2>&1 \
  || die "'docker compose' (Compose v2 plugin) is not available. Docker Desktop/OrbStack bundle it; with colima run 'brew install docker-compose' and follow its caveats to register the plugin."
if ! docker info >/dev/null 2>&1; then
  die "cannot reach the Docker daemon (context: $(docker context show 2>/dev/null || echo unknown)).
      Start one: open Docker Desktop or OrbStack, or run 'colima start --cpu 6 --memory 8'."
fi
ok "docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '?'), compose $(docker compose version --short 2>/dev/null || echo '?')"
# Host builds only (./mvnw): the compose/Docker path installs unzip inside the build image, so this is a warning.
command -v unzip >/dev/null 2>&1 \
  || warn "'unzip' not found: ./mvnw on this host will fail its SHA-256 check on first download (install unzip; containers are unaffected)"

# Warn (not fail) on small VMs: Kafka + Postgres + Grafana + Jaeger fit in ~4 GiB,
# the four JVMs on top need ~4 GiB more. MemTotal is what the VM kernel reports,
# a little below the configured size, hence the 10% slack.
read -r MEM_BYTES NCPU <<<"$(docker info --format '{{.MemTotal}} {{.NCPU}}' 2>/dev/null || echo '0 0')"
if $WITH_APPS; then MIN_GIB=8; MIN_CPU=6; else MIN_GIB=6; MIN_CPU=4; fi
MIN_BYTES=$(( MIN_GIB * 1024 * 1024 * 1024 * 9 / 10 ))
case "$MEM_BYTES$NCPU" in
  *[!0-9]*|"") warn "could not read Docker VM size; skipping the resource check" ;;
  *)
    MEM_GIB_X10=$(( MEM_BYTES * 10 / 1024 / 1024 / 1024 ))
    if [ "$MEM_BYTES" -lt "$MIN_BYTES" ] || [ "$NCPU" -lt "$MIN_CPU" ]; then
      warn "Docker VM has $((MEM_GIB_X10 / 10)).$((MEM_GIB_X10 % 10)) GiB / $NCPU CPU; recommended ${MIN_GIB} GiB / ${MIN_CPU} CPU for this mode."
      warn "  colima: colima stop && colima start --cpu 6 --memory 8   (Docker Desktop/OrbStack: Settings > Resources)"
    else
      ok "Docker VM: $((MEM_GIB_X10 / 10)).$((MEM_GIB_X10 % 10)) GiB / $NCPU CPU"
    fi
    ;;
esac

# --- 2. .env -------------------------------------------------------------------
if [ ! -f .env ]; then
  if [ -f .env.example ]; then
    cp .env.example .env
    ok "created .env from .env.example (dev-only defaults; edit it to change passwords/ports binding)"
  else
    warn ".env.example not found; compose will use the built-in defaults"
  fi
fi

# Prometheus scrapes either services on the host (./mvnw spring-boot:run) or in
# compose. An explicit choice in the shell or .env wins; otherwise --with-apps, or
# app containers that are already running (e.g. a later `dev-up.sh --tools`), mean
# the containers, so a re-run never silently stops scraping running services.
# ("host" is the .env.example default, so it is not treated as an explicit choice;
# export PROMETHEUS_TARGETS=host to force it.)
apps_running() {
  # shellcheck disable=SC2086
  [ -n "$(docker compose --profile app ps -q --status running $APP_SERVICES 2>/dev/null || true)" ]
}
if [ -z "${PROMETHEUS_TARGETS:-}" ] && [ "$(env_value PROMETHEUS_TARGETS host)" = "host" ]; then
  if $WITH_APPS || apps_running; then
    export PROMETHEUS_TARGETS=docker
  fi
fi
PROM_MODE="$(env_value PROMETHEUS_TARGETS host)"
# compose mounts configs/prometheus/targets/<value>.json; for a typo Docker would create an empty
# directory of that name and Prometheus would fail with an obscure file-discovery error.
case "$PROM_MODE" in
  host|docker) ;;
  *) die "PROMETHEUS_TARGETS must be 'host' or 'docker', got '$PROM_MODE' (check the shell environment and .env)" ;;
esac

# --- 3. infrastructure ---------------------------------------------------------
# Word-splitting of the service lists is intended (bash 3.2 has no better list type).
infra_args=(up -d --wait --wait-timeout "$INFRA_WAIT_TIMEOUT")
$PULL && infra_args+=(--pull always)
# shellcheck disable=SC2206
infra_args+=($INFRA_SERVICES)
profile_args=()
if $WITH_TOOLS; then
  profile_args=(--profile tools)
  infra_args+=(kafka-ui)
fi

step "Starting infrastructure (waiting up to ${INFRA_WAIT_TIMEOUT}s for healthchecks)"
rc=0
docker compose ${profile_args[@]+"${profile_args[@]}"} "${infra_args[@]}" || rc=$?
if [ "$rc" -ne 0 ]; then
  docker compose ps -a >&2 || true
  die "infrastructure did not become healthy (exit $rc). Inspect with: docker compose logs <service>"
fi
ok "infrastructure healthy (Prometheus targets: $PROM_MODE)"

# --- 4. Kafka topics -----------------------------------------------------------
step "Creating Kafka topics (kafka-init)"
docker compose up -d kafka-init >/dev/null
rc=0
docker compose wait kafka-init >/dev/null 2>&1 || rc=$?
if [ "$rc" -ne 0 ]; then
  docker compose logs --no-color kafka-init >&2 || true
  die "kafka-init failed (exit $rc); its logs are above"
fi

# The container listener: the host listener advertises KAFKA_ADVERTISED_HOST, which need not
# resolve inside the container.
topic_list="$(docker compose exec -T -e KAFKA_HEAP_OPTS=-Xmx64m kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --list 2>/dev/null)" \
  || die "could not list Kafka topics (docker compose exec kafka ... --list failed)"
missing=""
expected=0
for t in $BASE_TOPICS; do
  for name in "$t" "$t.DLT"; do
    expected=$((expected + 1))
    grep -qxF "$name" <<<"$topic_list" || missing="$missing $name"
  done
done
[ -z "$missing" ] || die "Kafka topics missing:$missing"
ok "all $expected Kafka topics present"

# --- 5. applications -----------------------------------------------------------
if $WITH_APPS; then
  # shellcheck disable=SC2206
  app_list=($APP_SERVICES)
  images_missing=false
  for svc in "${app_list[@]}"; do
    docker image inspect "shopsage/$svc:local" >/dev/null 2>&1 || images_missing=true
  done
  # Build as its own step so a compile/Docker failure is reported as such, not as "did not become
  # healthy". The images have pull_policy: never; --pull only refreshes the base images.
  if $BUILD || $images_missing; then
    build_args=(build)
    $PULL && build_args+=(--pull)
    step "Building service images (the first build takes a few minutes)"
    docker compose --profile app "${build_args[@]}" "${app_list[@]}" \
      || die "image build failed (see output above)"
    ok "images built"
  fi
  app_args=(up -d --wait --wait-timeout "$APP_WAIT_TIMEOUT")
  step "Starting services (waiting up to ${APP_WAIT_TIMEOUT}s)"
  rc=0
  docker compose --profile app "${app_args[@]}" "${app_list[@]}" || rc=$?
  if [ "$rc" -ne 0 ]; then
    docker compose --profile app ps -a >&2 || true
    die "services did not become healthy (exit $rc). Inspect with: docker compose logs <service>"
  fi
  ok "services healthy"
fi

# --- 6. summary ----------------------------------------------------------------
BIND_HOST_VALUE="$(env_value BIND_HOST 127.0.0.1)"
case "$BIND_HOST_VALUE" in
  127.0.0.1|0.0.0.0|"") HOST=localhost ;;
  *) HOST="$BIND_HOST_VALUE" ;;
esac
GRAFANA_PW="$(env_value GRAFANA_ADMIN_PASSWORD admin)"

row() { printf '  %-20s %-52s %s\n' "$1" "$2" "${3:-}"; }

echo
printf '%sShopSage dev stack is up%s\n' "$C_BOLD" "$C_RESET"
row "COMPONENT" "URL" "CREDENTIALS / NOTES"
row "---------" "---" "-------------------"
row "Grafana" "http://$HOST:3000" "admin / $GRAFANA_PW (anonymous: Viewer)"
row "Prometheus" "http://$HOST:9090" "targets mode: $PROM_MODE"
row "Jaeger UI" "http://$HOST:16686" "OTLP: grpc :4317, http :4318"
row "Kafka" "$(env_value KAFKA_ADVERTISED_HOST localhost):9092" "in-network: kafka:29092 (PLAINTEXT)"
for svc in user inventory order payment; do
  upper="$(printf '%s' "$svc" | tr '[:lower:]' '[:upper:]')"
  row "Postgres ($svc)" "jdbc:postgresql://$HOST:5432/shopsage_$svc" \
    "shopsage_$svc / $(env_value "${upper}_DB_PASSWORD" "shopsage_$svc")"
done
row "Postgres (admin)" "jdbc:postgresql://$HOST:5432/shopsage" \
  "$(env_value POSTGRES_USER shopsage) / $(env_value POSTGRES_PASSWORD shopsage)"
row "Redis" "redis://$HOST:6379" "cache only, no persistence"
if $WITH_TOOLS; then
  row "kafka-ui" "http://$HOST:8089" "no login; cluster shopsage-local (kafka:29092)"
fi
if $WITH_APPS; then
  port=8081
  for svc in $APP_SERVICES; do
    row "$svc" "http://$HOST:$port" "/actuator/health | info | prometheus"
    port=$((port + 1))
  done
fi

echo
printf '%sNext steps%s\n' "$C_BOLD" "$C_RESET"
cat <<EOF
  source scripts/dev-env.sh                       # JAVA_HOME + DOCKER_HOST for Maven/Testcontainers
  ./mvnw -q -DskipTests install                   # once, and after changing common/
  ./mvnw -pl services/order-service spring-boot:run
  ./mvnw verify                                   # needs Docker; -DskipITs skips integration tests
  ./scripts/dev-down.sh                           # stop (add -v to wipe data)
EOF
if ! $WITH_APPS; then
  printf '%s  Tip: ./scripts/dev-up.sh --with-apps --build runs the services in containers.%s\n' "$C_DIM" "$C_RESET"
fi
