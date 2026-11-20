# ADR 002 — The Simulator Owns the Truth

**Status:** Accepted · **Date:** 2026-11-20

## Context

The system shows inventory, risk and shipments. Two possible sources of truth:

1. The application maintains its own authoritative copy of inventory and shipment state.
2. The simulator owns it; the application stores *observations* of it.

The simulator is the world. It decides inventory, demand, shipment status and every failure. The application cannot compute the correct inventory — it can only report what the simulator says.

There is also a reset hazard: `/admin/reset` destroys every row and reloads the baked scenario. `SimulationInstance.id` is always `1`, and ticks and entity ids are reused after a reset.

## Decision

**The simulator owns inventory, demand and shipment status.** The application database stores:

- **Observations** — a bounded history of snapshots, so history and audit survive a restart.
- **Command intent** — the immutable allocation request and its idempotency key, written *before* any POST.
- **Decision evidence** — the recommendation, its reasons, its snapshot id and its review status.

A stored snapshot is **evidence of a past observation, never an alternative operational world**. No code path may mutate stored inventory to represent current state.

Every stored record carries an **application run UUID**. The simulator's instance id is useless for isolation because it is always `1` and reset reuses ticks and entity ids.

On an observed tick rollback or a `simulator.notice` about a reset, the application:
1. Suspends writes.
2. Starts a new application run.
3. Retires old recommendations (they refer to a world that no longer exists).
4. Reconciles any unresolved commands.

## Alternatives rejected

- **Application-owned inventory.** Would require replicating the simulator's demand model, dispatch rules and arrival timing. Any divergence produces confidently wrong recommendations — worse than showing stale data honestly.
- **Key everything by simulator instance id.** `id` is always `1`. After a reset, old commands would look like they belong to the new world, and a retry could duplicate a shipment that already happened.

## Consequences

- **Positive:** the application can never be "confidently wrong" about the world. It either shows a validated observation, or it shows nothing and says why.
- **Positive:** a reset is handled as a first-class event rather than a corruption.
- **Negative:** the application cannot answer a question about the world faster than one refresh cycle.
- **Negative:** storage grows. Mitigated by keeping a bounded number of snapshots and deduplicating demand rows on `(run_id, station_id, fuel_type, tick)`.

## Verification

`docs/contract-notes.md` §5.2 records the measured proof that the simulator deducts depot inventory at POST time. The application therefore treats the depot `inventory` field as **already net of pending and in-transit reservations** and must not subtract them again.
