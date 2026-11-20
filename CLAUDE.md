# CLAUDE.md

Project: Fuel Supply Intelligence & Resilience Platform — an operator decision-support system over the official BUP Fuel Supply Simulator.

Backend: Spring Boot 3.5.7 (Java 21). Frontend: React 18 + TypeScript (Vite). Database: PostgreSQL 16.

## Before you edit anything

1. Read `docs/contract-notes.md` — it records **measured** simulator behaviour, not assumptions.
2. Read the relevant ADR in `docs/adr/`.
3. Read `docs/acceptance-and-invariants.md` — the invariant list is binding.

## Hard rules

- **REST is the source of truth. SSE is only a hint.** After any stream event, re-GET the affected resource.
- **Never modify the official simulator image or its source.** `asifmahmoud414/bup-fuel-supply-simulator:1.0.0` is pinned.
- **Never allocate from stale or invalid data.** The server-side guard is the control; a disabled button is not.
- **Never trust an HTTP 200.** Check `X-Simulator-Stale` on every `/v1/*` GET.
- **Depot inventory is already net of reservations** (ADR 008). Do not subtract pending/in-transit again. Station headroom *does* reserve inbound fuel.
- **Dispatch allowance is per depot, shared across every fuel and destination.** Reserve it in the working copy.
- **Forecast on `demand_liters`, never `served_liters`** (INV-19). Sort demand **ascending by tick** before any EWMA — the API returns newest-first (INV-20).
- **Support PAUSED and RUNNING.** Approval revalidates against a fresh read and never exceeds the reviewed quantity (ADR 004).
- **Persist the immutable command body and key before the POST.** Retries reuse that exact body and key — never change the quantity while reusing a key.
- **Operator approval is server-enforced** (ADR 003). Do not add an auto-approve threshold.
- **Unique ids never become metric labels** (INV-27). Keep a fixed small label set.
- **A missing field is unknown, not zero** (INV-6).

## Working agreements

- Explain the plan and the assumptions **before** writing code.
- Small focused diffs. One writer per module at a time.
- Run the checks the issue requires and **show the actual output**. Never claim a check passed without its output.
- Challenge one failure case per change — a passing test suite that never asked a hard question proves nothing.
- Update `docs/current-state.md` after a completed issue.
- Update `docs/evidence/` with real measured output, not a summary of what you believe happened.

## Build and run

```powershell
# backend
cd backend
.\mvnw.cmd verify          # compile + unit tests
.\mvnw.cmd spring-boot:run  # run on :8080

# frontend
cd frontend
npm ci
npm run dev                # http://localhost:5173

# whole stack
docker compose up --build -d
```

## The one thing to get right first

`GET /v1/depots` returns inventory **already net of pending and in-transit shipments** — the simulator deducts at POST time, not at departure. Reading that wrong makes the allocator refuse valid shipments or double-count reservations. See ADR 008 and `docs/contract-notes.md` §5.2.
