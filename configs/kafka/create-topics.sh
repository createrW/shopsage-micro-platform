#!/usr/bin/env bash
# Creates the Saga topics and their dead-letter twins, then verifies all of them exist.
# Run by the one-shot `kafka-init` compose service (apache/kafka image) after the broker is healthy.
# The broker has auto-create disabled, so this script is the single place topics come from.
#
# Env:
#   KAFKA_BOOTSTRAP  internal listener, e.g. kafka:29092                       (required)
#   TOPICS           space-separated base topics (= Topics.all() in common)    (required)
#   PARTITIONS       partitions per topic; key = orderId keeps per-order order (default 3)
#   REPLICATION      replication factor; 1 on the single-node dev broker       (default 1)
#
# PARTITIONS and REPLICATION only apply to topics this script creates: changing PARTITIONS does
# not alter existing topics (use kafka-topics.sh --alter, or ./scripts/dev-down.sh -v to recreate).
set -euo pipefail

: "${KAFKA_BOOTSTRAP:?KAFKA_BOOTSTRAP is required}"
: "${TOPICS:?TOPICS is required}"
PARTITIONS="${PARTITIONS:-3}"
REPLICATION="${REPLICATION:-1}"

# Not on PATH in the official image.
readonly KAFKA_TOPICS=/opt/kafka/bin/kafka-topics.sh
# Spring Kafka's DeadLetterPublishingRecoverer default; must match Topics.DLT_SUFFIX.
readonly DLT_SUFFIX=.DLT
readonly MAX_ATTEMPTS=30

kt() { "$KAFKA_TOPICS" --bootstrap-server "$KAFKA_BOOTSTRAP" "$@"; }

# The broker healthcheck already probes this listener; the loop guards against a broker that is
# still (re)starting. Its successful --list is the one snapshot used below: every CLI call starts
# a JVM (~1 s), so re-runs list once instead of issuing 20 --create calls.
attempt=1
until existing="$(kt --list 2>/dev/null)"; do
  if ((attempt >= MAX_ATTEMPTS)); then
    echo "kafka-init: broker ${KAFKA_BOOTSTRAP} unreachable after ${MAX_ATTEMPTS} attempts" >&2
    exit 1
  fi
  echo "kafka-init: waiting for ${KAFKA_BOOTSTRAP} (${attempt}/${MAX_ATTEMPTS})"
  attempt=$((attempt + 1))
  sleep 2
done

read -r -a base_topics <<<"$TOPICS"
expected=()
for topic in "${base_topics[@]}"; do
  expected+=("$topic" "${topic}${DLT_SUFFIX}")
done

# Create only what is missing. --if-not-exists still covers a concurrent creator (e.g. two
# `docker compose up` runs racing).
created=0
for topic in "${expected[@]}"; do
  if ! grep -qxF -- "$topic" <<<"$existing"; then
    kt --create --if-not-exists --topic "$topic" \
      --partitions "$PARTITIONS" --replication-factor "$REPLICATION"
    created=$((created + 1))
  fi
done
echo "kafka-init: created ${created} missing topic(s); $((${#expected[@]} - created)) already existed"

# Re-list only if something was created; otherwise the snapshot above is current.
if ((created > 0)); then
  existing="$(kt --list)"
fi
echo "kafka-init: topics on ${KAFKA_BOOTSTRAP}:"
echo "$existing"

missing=0
for topic in "${expected[@]}"; do
  if ! grep -qxF -- "$topic" <<<"$existing"; then
    echo "kafka-init: MISSING topic ${topic}" >&2
    missing=$((missing + 1))
  fi
done

if ((missing > 0)); then
  echo "kafka-init: ${missing} of ${#expected[@]} expected topics are missing" >&2
  exit 1
fi
echo "kafka-init: all ${#expected[@]} topics present"
