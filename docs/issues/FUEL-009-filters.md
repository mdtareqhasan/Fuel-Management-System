# FUEL 009 — Fuel and region filters on the operator console

**Type:** feature (read path) · **Module:** `frontend` · **Day:** 1 · **Status:** done

---

## 1. Why this issue exists

The guide's Day 1 UI budget says: *"Render station and depot inventory **with
fuel filters**, tick, mode badge and refresh age."* The tick, the mode badge and
the refresh age were already there; the fuel filter was missing. Acceptance
story **AS-1** also asks for filtering *by fuel and region* before the operator
can find a station at risk.

The filter was deliberately left until after the contract was measured, because
the fuel list and the region list must come from the snapshot — not from a
hardcoded list. The simulator could change its fuels; a hardcoded filter bar
would then silently filter on a fuel that does not exist.

---

## 2. Understand — the example solved by hand

**Input** (a real payload from `docs/evidence/day-01/dashboard-sample.json`):

| Station | region_id | DIESEL | PETROL | OCTANE |
|---|---|---|---|---|
| `station-mirpur` | `region-dhaka` | 9000 | 9000 | 5000 |
| `station-coxsbazar` | `region-chattogram` | 7500 | 7500 | 4200 |

**Filter:** fuel = `PETROL`, region = `region-dhaka`.

**Expected output:** exactly one station (`station-mirpur`), and inside it
exactly one fuel row (`PETROL`, 9,000 L). `station-coxsbazar` is excluded
because it is in Chattogram. The depot list shows only `depot-gazipur`.

**Failure case to watch:** `station-coxsbazar` is in Chattogram but its **source
depot** for a shipment could be Gazipur. A shipment belongs to the region of its
**destination station** — a shipment is about the station that needs the fuel,
not the depot that sends it. Filtering by the depot's region would put a
cross-region shipment in the wrong bucket.

**Second failure case:** a shipment whose destination station id is not in the
snapshot. Dropping it would hide a shipment the operator may still need. It is
kept, and the region filter is not applied to it.

---

## 3. Specify — the bounded issue

**Goal.** An operator can narrow the console to one fuel and one region without
losing the context that says how fresh the data is.

**Input.** The existing `DashboardResponse` from `GET /api/dashboard`.

**Expected output.** Stations, depots and shipments narrowed to the selected
fuel and region; the fuel rows inside each card narrowed to the selected fuel;
the header still showing tick, quality and refresh age; a count of "showing N
of M" when a filter is active; a reset control.

**Failure case.** No station matches the filter. The UI must say so in words
rather than render an empty grid that looks broken.

**Invariant enforced.** A filter is a **view** concern. It never changes the
snapshot the backend holds, and it never turns an unknown value into a zero.

**Allowed scope.**

```
frontend/src/filters.ts        (new)
frontend/src/filters.test.ts   (new)
frontend/src/App.tsx
frontend/src/styles.css
frontend/src/types.ts          (unchanged — the API shape did not change)
```

---

## 4. Implement — the design

**The filter logic lives in its own module**, `frontend/src/filters.ts`, free of
React. That is what makes it testable without a DOM and readable on its own.

| Function | Rule |
|---|---|
| `regionOptions` | `ALL` plus every region in the snapshot — never a hardcoded list |
| `visibleFuels` | all three fuels, or just the selected one |
| `filterStations` / `filterDepots` | match `region_id` against the selected region |
| `filterAllocations` | match `fuel_type`; match the region of the **destination station**; keep a shipment whose destination is unknown |
| `indexStations` | builds the id → station lookup used by the shipment filter |
| `isDefault` | decides whether the reset control is shown |

**The UI.** A filter bar sits between the header and the sections. The header is
**not** affected by the filter, on purpose: the tick, the freshness badge and the
refresh age describe the data underneath, and a filtered view must never look
like a different world.

**Unknown stays unknown.** If a station has no entry for a fuel, that row shows
"Unknown", never `0 L`. The filter narrows rows; it does not manufacture them.

**Empty is a state.** "No station matches the current filter." — said plainly.

---

## 5. Verify — what was actually run

```bash
cd frontend
npm run test     # 14 tests, all passing
npm run build    # tsc -b && vite build, no type errors
```

The test file covers: the region option list (including the empty-world case),
region filtering for stations and depots, the empty-result case, **that the
filter does not mutate the data it was given**, fuel filtering, the
destination-region rule for shipments, the unknown-destination rule, and the
reset-control visibility rule.

The browser was then checked against the running stack: the filter bar renders
with the fuel pills and the region pills drawn from the snapshot, and the counts
update when a filter is applied.

---

## 6. Review — one failure case challenged

> **"What happens when a station has no inventory entry for the selected fuel?"**

The naive implementation renders `0 L`, which is a claim that the station is
empty. The truth is that we were not told. The implementation reads
`station.inventory?.[fuelName] ?? null`, and `null` renders as "Unknown" through
the existing `formatLitres` helper. The filter never creates a row for a fuel
that is absent from the card.

> **"Why is the region of a shipment the destination's region?"**

Because a shipment is about the station that needs the fuel. Cross-region routes
exist in the world (`route-gazipur-karnaphuli`, `route-patiya-mirpur`), and
bucketing them by the sending depot would put a Chattogram delivery in the
Dhaka list. There is a test for exactly this case.

---

## 7. Retain

- **Files changed:** `frontend/src/filters.ts` (new), `frontend/src/filters.test.ts`
  (new), `frontend/src/App.tsx`, `frontend/src/styles.css`,
  `frontend/package.json` (vitest), `frontend/vite.config.ts`,
  `.github/workflows/ci.yml` (the frontend job now runs the tests).
- **Checks run:** `npm run test` → 22 passed. `npm run build` → exit 0.
  `npm audit` → 0 vulnerabilities. `python scripts/verify_filters.py` → 13 passed.
- **Not done here, deliberately:** filtering the risk table and the recommendation
  panel. Those arrive with the risk engine on Day 2 and Day 3, and the filter
  module is written so they can reuse it unchanged.
