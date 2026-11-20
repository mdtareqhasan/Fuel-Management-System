# Phase 02 · Day 3 — Persistence & Operator Decisions

**Target:** Saturday, 21 November 2026 · **Status:** Planned.

| Milestone | Focus areas | Intended result |
|:--|:--|:--|
| Persistence & operator decisions | Durable records, recommendation review, safe approval, command reconciliation | Operator can review and approve a proposal once, then follow its shipment lifecycle |

## Planned work

- Add database migrations and persist snapshots, recommendations, anomaly flags, commands, and audit events.
- Build the risk/recommendation review UI with alternatives and decision explanations.
- Save command intent and an idempotency key before contacting the simulator.
- Re-read and revalidate the reviewed proposal at approval time in both PAUSED and RUNNING modes.
- Reconcile uncertain outcomes and show shipment status from simulator state.

## Acceptance evidence

- Duplicate approval results in one simulator allocation.
- An accepted request with a lost response is reconciled without a duplicate shipment.
- Approved quantity never exceeds the reviewed quantity; stale or invalid snapshots block approval.
- End-to-end evidence follows a proposal through approval and shipment status.

Detailed schedule: [Implementation guide, Day 3](../../Fuel%20Management%20System%20md%20file/Fuel_Management_Implementation_Guide_v2.md).
