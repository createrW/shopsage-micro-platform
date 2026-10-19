#!/usr/bin/env bash
#
# Stop the ShopSage local dev stack (all profiles, so app and tools containers go too).
# Data volumes are kept unless -v/--volumes is given.
#
# Compatible with macOS /bin/bash 3.2.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

WIPE_VOLUMES=false
ASSUME_YES=false

usage() {
  cat <<EOF
Usage: scripts/dev-down.sh [options]

Stop and remove the ShopSage containers and network (profiles app + tools included).

Options:
  -v, --volumes   also DELETE the named volumes (Postgres, Kafka, Grafana, Prometheus data)
  -y, --yes       do not ask for confirmation before deleting volumes
  -h, --help      show this help
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    -v|--volumes) WIPE_VOLUMES=true ;;
    -y|--yes)     ASSUME_YES=true ;;
    -h|--help)    usage; exit 0 ;;
    *)            usage >&2; echo >&2; echo "ERROR unknown option: $1" >&2; exit 1 ;;
  esac
  shift
done

command -v docker >/dev/null 2>&1 || { echo "ERROR docker CLI not found on PATH" >&2; exit 1; }
docker info >/dev/null 2>&1 || {
  echo "ERROR cannot reach the Docker daemon (start Docker Desktop, OrbStack, or 'colima start')" >&2
  exit 1
}

down_args=(--profile app --profile tools down --remove-orphans)

if $WIPE_VOLUMES; then
  echo "WARNING: this deletes the ShopSage volumes: all Postgres databases, Kafka topics and" >&2
  echo "         messages, Grafana state and Prometheus metrics history. This cannot be undone." >&2
  if ! $ASSUME_YES; then
    if [ ! -t 0 ]; then
      echo "ERROR refusing to delete volumes without confirmation; re-run with --yes" >&2
      exit 1
    fi
    printf "Type 'yes' to continue: " >&2
    answer=""
    read -r answer || true
    if [ "$answer" != "yes" ]; then
      echo "Aborted; nothing was stopped." >&2
      exit 1
    fi
  fi
  down_args+=(--volumes)
fi

docker compose "${down_args[@]}"

if $WIPE_VOLUMES; then
  echo "Stack stopped and volumes removed. The next ./scripts/dev-up.sh starts from empty databases."
else
  echo "Stack stopped; data volumes kept. Start again with ./scripts/dev-up.sh"
fi
