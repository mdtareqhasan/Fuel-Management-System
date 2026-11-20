# ADR 007 — Local Docker Compose Deployment, No Cloud

**Status:** Accepted · **Date:** 2026-11-20

## Context

The brief requires: *"The system must be reproducibly runnable, preferably containerized"* and lists deployment as a required deliverable. DevOps is 15% of the rubric. AWS was explicitly excluded from the requested scope by the user.

Options:

1. Local Docker Compose (simulator, backend, frontend, postgres, prometheus, grafana).
2. AWS (ECS / EKS / App Runner).
3. A documented manual run without containers.

## Decision

**Ship a local Docker Compose deployment as the reproducible path.**

Compose services: `simulator-api`, `backend`, `frontend`, `postgres`, `prometheus`, `grafana`.

| Rule | Reason |
|---|---|
| Multi-stage Dockerfiles | Build with Maven Wrapper, run the jar on a small JRE image; build React with `npm ci`, serve static output through Nginx proxying `/api` to the backend. |
| Pin every dependency and image version | A reproducible build is the point. Unpinned tags make "it worked yesterday" impossible to diagnose. |
| PostgreSQL uses a named volume | Test artefacts and evidence survive `docker compose down`. |
| Backend and database ports stay internal | Only the frontend, simulator admin and monitoring publish on `127.0.0.1`. |
| Health checks + `service_healthy` | Compose starts containers in order but does **not** wait for readiness on its own. |
| The backend still retries connections | It must survive starting before PostgreSQL is ready, and a simulator outage must degrade reads and block approvals — not prevent startup. |
| Prometheus is P0, Grafana is P1 | The metrics endpoint is required from day one; the Grafana dashboard follows once the status panel works. |

The **official simulator image is used unmodified**, pinned to `asifmahmoud414/bup-fuel-supply-simulator:1.0.0`. Judges run their own submission against the published image, so the simulator source is never edited.

## Alternatives rejected

- **AWS.** Excluded from scope by request. It also adds cold starts and network hops that would distort the p95 latency and error-rate numbers the rubric asks for, and it introduces a failure mode (credentials, egress rules, quota) that cannot be debugged in the remaining time.
- **No containers.** A documented manual run is weaker against "reproducibly runnable" and loses the containerisation signal in the rubric.
- **Kubernetes / Helm / Terraform.** Explicitly optional in brief section 14, and the brief warns: *"Do not introduce infrastructure complexity unless it improves your solution."* It would consume day 4 and 5 entirely.

## Consequences

- **Positive:** a fresh clone runs with a documented `.env` and `docker compose up --build -d`. A reviewer can reproduce every result.
- **Positive:** the deployment, CI and demo runbook all exercise the same artefact — the image that was tested is the image that ships.
- **Negative:** Windows and macOS need Docker Desktop. Accepted; the target environment is a Linux container.
- **Negative:** a fresh build pulls the full Maven and npm dependency trees, which takes several minutes the first time. Mitigated by committing the Maven Wrapper and the npm lockfile, and by CI caching.
- **Negative:** image digests must be recorded for the demo so the exact artefacts are identifiable. This is a task, not a freebie.

## Verification

Day 1 delivers the compose skeleton and a documented startup path. Day 4 completes the health checks, the local port restrictions, and a recorded **clean** Compose startup. Day 5 verifies from a fresh clone and records the release SHA that was built.
