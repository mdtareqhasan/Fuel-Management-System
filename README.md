# Fuel Supply Intelligence & Resilience Platform

An operator decision-support system over the official BUP Fuel Supply Simulator. It
reads the simulated fuel world, shows the operator which stations are at risk and
why, and requires a human approval before anything is dispatched.

**Backend:** Spring Boot 3.5.7 (Java 21) · **Frontend:** React 18 + TypeScript (Vite) ·
**Database:** PostgreSQL 16 · **Deployment:** Docker Compose · **Monitoring:** Prometheus + Grafana

---

## Quick start

```bash
cp .env.example .env
docker compose up --build -d
```

Then open:

| What | Where |
|---|---|
| Operator console | http://localhost:5173 |
| Backend API | http://localhost:8080/api/dashboard |
| System status | http://localhost:8080/api/system-status |
| Prometheus metrics | http://localhost:8080/actuator/prometheus |
| Grafana | http://localhost:3000 (admin / admin) |
| Simulator admin | http://localhost:8000/admin |

Check that everything is alive:

```bash
docker compose ps
python scripts/verify_day1.py
```

> **Port note.** This machine already runs PostgreSQL on 5432, so the compose
> database publishes on **5434**. The backend talks to it over the compose network
> by service name, so the published port only matters for host-side access. Change
> `DB_PORT` in `.env` if 5434 is taken too.

---

## Repository layout

```
backend/          Spring Boot backend, 9 packages with explicit boundaries
  src/main/java/com/fuelplatform/
    simulator/    Typed DTOs, REST client, error parsing, metrics
    ingestion/    Validation, atomic snapshot store, refresh scheduling
    domain/       WorldSnapshot, SimulationMode, DataQuality
    risk/         Demand rate, stockout trajectory, anomaly detection
    allocation/   Feasibility, policy, deterministic ranking
    decision/     Recommendation lifecycle and approval
    command/      Durable intent, worker lease, retry, reconciliation
    audit/        Decision and operator activity trail
    web/          Controllers, response shaping
    observability/ Component status, metric helpers
frontend/         React + TypeScript operator console
infra/            Prometheus and Grafana provisioning
scripts/          Verification and simulator control
docs/             ADRs, contract notes, acceptance criteria, evidence
tests/            Integration tests and k6 workloads
```

---

## How it works

```
Browser ──poll──▶ Backend ──REST──▶ Simulator
                    │  │
                    │  └── validated snapshot (atomic swap)
                    └── metrics ──▶ Prometheus ──▶ Grafana
```

- The **backend** owns one refresh loop against the simulator. The browser reads the
  cached, validated view, so a hundred dashboards cause one polling loop, not a
  hundred.
- A snapshot is **published atomically** and only after full validation. A reader
  sees the whole previous view or the whole new one.
- On a failed read the last valid snapshot **stays visible** with a warning, and
  approval is blocked. Losing the view would be worse than showing it honestly.
- The simulator owns the truth. The application stores *observations* of it, never
  an alternative world.

### The two states that must never be confused

| | Meaning |
|---|---|
| **Simulator UP / DOWN** | Can we reach the simulator at all |
| **Data FRESH / STALE / INVALID** | Is the view we hold fresh enough to act on |

`/v1/health` bypasses fault injection, so a simulator can be perfectly healthy
while every station read fails. Both are reported separately, and the status panel
shows them as separate rows.

---

## The rules that matter

These are enforced in code, not just documented. See `docs/acceptance-and-invariants.md`
for the full list of 30 invariants and `docs/adr/` for the reasoning.

1. **REST is the truth; SSE is only a hint.** Re-read after any stream event.
2. **Never allocate from stale or invalid data.** The server-side guard is the
   control — a disabled button is not.
3. **Depot inventory is already net of reservations.** The simulator deducts at
   dispatch time, so subtracting pending and in-transit again double-counts. See
   ADR 008.
4. **Dispatch allowance is per depot, shared across every fuel and destination.**
5. **Forecast on `demand_liters`, never `served_liters`.** Sort demand ascending by
   tick before any EWMA — the API returns newest first.
6. **Operator approval is server-enforced.** No auto-approval threshold exists.
7. **A missing value is unknown, never zero.** The UI shows "Unknown".
8. **Unique ids never become metric labels.**

---

## Running the tests

```bash
# Backend: 30 unit tests, no Docker and no simulator needed
cd backend && ./mvnw verify

# Frontend: TypeScript strict check and production build
cd frontend && npm ci && npm run build

# End-to-end against the running stack
python scripts/verify_day1.py
```

---

## Driving the simulator

```bash
python scripts/sim_control.py instance                  # current clock and mode
python scripts/sim_control.py step 5                    # advance 5 ticks
python scripts/sim_control.py run                       # switch to RUNNING
python scripts/sim_control.py pause                     # switch to PAUSED
python scripts/sim_control.py inject-fault stale_data 15
python scripts/sim_control.py inject-fault unavailable 30
python scripts/sim_control.py clear-faults
python scripts/sim_control.py event demand_spike 0 4 '{"multiplier": 1.8}'
```

---

## Documentation

| Document | What it holds |
|---|---|
| `docs/adr/` | Eight architecture decision records with the rejected alternatives |
| `docs/acceptance-and-invariants.md` | Seven acceptance stories and thirty invariants |
| `docs/contract-notes.md` | **Measured** simulator behaviour, not assumptions |
| `docs/evidence/day-01/` | Day 1 verification report and recorded outputs |
| `CLAUDE.md` | Working agreements for anyone (or any agent) editing this repo |

---

## Licence and attribution

The fuel supply simulator is the official
`asifmahmoud414/bup-fuel-supply-simulator:1.0.0` image, used **unmodified**.
This repository contains only the application built against it.
