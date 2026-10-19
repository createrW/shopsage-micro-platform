# ADR-0004: Multi-arch images for the ARM free tier

- **Status:** Accepted (2026-10-18); CI image builds Day 9, Helm Day 10
- **Deciders:** Yi Zhao

## Context

Development happens on Apple Silicon (arm64). CI runners are typically amd64. The optional cloud deployment targets
Oracle Cloud's always-free Ampere A1 (arm64) capacity through OKE. An image that exists for only one architecture either
fails to start or runs under slow emulation on the others.

## Decision

- Every image the stack uses must publish `linux/amd64` **and** `linux/arm64`. Third-party pins were chosen accordingly:
  `postgres:16.15-alpine`, `redis:7.4.11-alpine`, `apache/kafka:3.9.2` (official ASF image, KRaft),
  `jaegertracing/jaeger:2.20.0`, `prom/prometheus:v3.15.0`, `grafana/grafana:13.2.4`, `ghcr.io/kafbat/kafka-ui:v1.5.0`,
  `ghcr.io/shopify/toxiproxy:2.13.0`. Not used: Bitnami images (catalog moved behind a paywall in 2025) and
  `eclipse-temurin:17-jre-alpine` (amd64-only).
- Our services use one root `Dockerfile`: build stage `eclipse-temurin:17-jdk-noble` with
  `FROM --platform=$BUILDPLATFORM`, so Maven always runs natively and the JVM bytecode is reused for every target
  architecture; runtime stage `eclipse-temurin:17-jre-noble`, selected per service by `ARG SERVICE`.
- The runtime runs as numeric uid/gid 1001 (satisfies Kubernetes `runAsNonRoot`) over root-owned, read-only
  application files, forwards container arguments to the application, and sizes the heap with
  `-XX:MaxRAMPercentage=60` (headroom for non-heap memory), so the same image fits compose `mem_limit` and Kubernetes
  limits.
- Day 9 CI builds both platforms with `docker buildx`; Day 10 Helm values select images by tag only.

## Consequences

- **Positive:** one chart and one set of tags run on a laptop, on amd64 CI and on ARM cloud nodes.
- **Positive:** cross-building is cheap — only the small runtime stage is architecture-specific.
- **Negative:** image choices are constrained; any new dependency image must be checked for arm64 before adoption.
- **Negative:** multi-arch builds and pushes take longer in CI and need a registry that supports manifest lists.
