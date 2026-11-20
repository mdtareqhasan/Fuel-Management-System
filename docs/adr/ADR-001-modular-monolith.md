# ADR 001 — Modular Monolith

**Status:** Accepted · **Date:** 2026-11-20 · **Context:** Fuel Supply Intelligence & Resilience Platform

## Context

The brief asks for an operator application, backend, intelligence component, deployment, observability, resilience and load testing — inside a 5-day window (2026-11-19 to 2026-11-23, deadline 2026-11-26).

Options considered:

1. **Modular monolith** — one Spring Boot process, modules separated by interfaces.
2. **Microservices** — simulator adapter, risk engine, decision engine, command worker as separate services.
3. **Serverless / managed** — AWS Lambda + managed queues.

The simulator world is tiny: 2 regions, 2 depots, 4 stations, 6 routes, 3 fuels.

## Decision

**Use a modular monolith.** A single Spring Boot process, with the 9 packages from the implementation guide acting as explicit internal boundaries:

`simulator` · `ingestion` · `risk` · `allocation` · `decision` · `command` · `audit` · `web` · `observability`

The boundaries are real: external I/O lives in `simulator`, pure decision logic in `risk`/`allocation`, and mutations flow through `command`. Each boundary is an interface, so the simulator adapter or the forecasting engine can be replaced without touching controllers.

## Alternatives rejected

- **Microservices.** Distributed failure handling, partial outages and network partitions are exactly the problem this project must *demonstrate handling of*. Adding them would mean debugging the platform and the demo at the same time, with no days left. It also multiplies deployment and CI work, which is 15% of the rubric.
- **Serverless / AWS.** AWS was explicitly excluded from the requested scope. It also adds cold-start latency that would pollute the p95 latency measurement the rubric asks for.

## Consequences

- **Positive:** one transaction boundary, one process to start, one log stream to correlate, one build. Deployment is `docker compose up --build`.
- **Positive:** a domain test needs no container. The forecast and allocation logic run as plain JUnit tests over immutable records.
- **Negative:** a single process means one crash affects everything. Mitigated by keeping the write path durable in PostgreSQL so a restart reconciles rather than loses commands.
- **Negative:** the backend must scale vertically. Acceptable — the target is 50 concurrent dashboard users, and the refresh loop is the real bottleneck, not CPU.

## Verification

`./mvnw verify` runs domain tests with no Docker and no simulator. `docker compose up --build` starts the whole system from one command. The vertical slice (read → risk → recommend → approve → ship) runs end to end in one process.
