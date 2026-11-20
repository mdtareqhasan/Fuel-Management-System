# ADR 008 — Depot Inventory Is Already Net of Reservations

**Status:** Accepted · **Date:** 2026-11-20 · **Supersedes the reservation guidance in implementation guide chapter 15**

## Context

The implementation guide chapter 15 says:

> *"Do not subtract PENDING or IN_TRANSIT allocations from depot inventory a second time if the simulator already deducted them at creation; the day one experiment decides this."*

and chapter 14 says:

> *"Do not subtract PENDING or IN_TRANSIT allocations from depot inventory a second time if the simulator already deducted them at creation; the day one experiment decides this."*

The guide was right to flag this as the day-one experiment — but it is worth being precise about *which* answer it got, because the two readings lead to opposite code.

Two readings were possible:

- **Reading A:** the simulator deducts depot inventory at *departure*. Then `GET /v1/depots` returns gross inventory, and the allocator must subtract pending/in-transit reservations itself.
- **Reading B:** the simulator deducts depot inventory at *creation*. Then `GET /v1/depots` already returns net inventory, and subtracting again double-counts.

## The Measurement

Day-1 probe, `depot-gazipur`, DIESEL, allocation of 3000 (`docs/evidence/day-01/` and `docs/contract-notes.md` §5.2):

| Moment | `GET /v1/depots/depot-gazipur` → `inventory.DIESEL` |
|---|---|
| baseline | 60000 |
| immediately after `POST /v1/allocations` (201, status PENDING) | **57000** |
| after replaying the same idempotency key (201, same allocation) | 57000 (no second deduction) |
| after the shipment departed and arrived | 57000 (inventory not restored on arrival — fuel left the depot) |

## Decision

**Reading B is correct. The simulator deducts depot inventory at creation time.**

Therefore:

1. `GET /v1/depots` returns inventory that is **already net of all pending and in-transit shipments**.
2. The allocator uses the returned depot inventory **as-is** for the fuel-availability constraint.
3. The allocator **must not** subtract pending or in-transit quantities again. Doing so would double-count reservations and systematically starve the depot — the app would refuse to ship fuel the simulator is perfectly happy to ship.
4. The only quantities the allocator subtracts are **this application's own commands** that are not yet reflected in the simulator — i.e. commands in state `APPROVED` or `SENDING` for which the simulator has not yet confirmed acceptance.
5. `POST /v1/allocations/{id}/cancel` **refunds** the depot inventory (measured: PETROL 69500 → 70000). So depot inventory can rise again, and the allocator must read it from a fresh response rather than tracking a running total.

## A related measured fact

**Dispatch allowance is per depot and shared across every fuel type and every destination**, and it is consumed at POST time.

| Step | Request | Result |
|---|---|---|
| A | DIESEL 7000 to mirpur | 201 |
| B | PETROL 7000 to mirpur (different fuel, same depot, same tick) | **409** `DISPATCH_CAPACITY_EXCEEDED` |
| C | DIESEL 6000 more (cumulative 13100 > 12000) | **409** `DISPATCH_CAPACITY_EXCEEDED` |
| D | DIESEL 5000 to tongi (different station, same depot, same tick) | **409** `DISPATCH_CAPACITY_EXCEEDED` |

`depot-gazipur.dispatch_capacity_per_tick = 12000`. The allowance is consumed at creation, so the allocator must reserve it in its working copy before planning the next line — across fuels.

## Alternatives rejected

- **Reading A.** Contradicted by direct measurement. Implementing it would make the application refuse valid shipments.
- **Subtract pending/in-transit from the returned inventory "to be safe".** Double-counting. Being conservative in the wrong direction is still wrong: it produces unmet needs that do not exist and recommendations that are too small.
- **Track a running depot total in the application.** The cancel path refunds inventory, and supply arrivals add it, so a running total drifts from the simulator. The simulator's number is authoritative.

## Consequences

- **Positive:** the allocator needs one fewer constraint and one fewer source of bugs.
- **Positive:** the station-side reservation still applies — pending/in-transit fuel *will* land at the station, so the destination headroom check must still reserve for it.
- **Negative:** the constraint is asymmetric and easy to get wrong in both directions. The depot side uses the returned value directly; the station side reserves inbound fuel. This asymmetry must be documented in the allocation module, because it is the single most likely place to introduce a bug.

## Verification

`docs/evidence/day-01/dispatch-capacity-test.txt` holds the raw responses. The allocation unit tests on day 2 must include a case where the depot inventory returned by the snapshot already excludes a pending shipment, and assert the allocator does not subtract it again.
