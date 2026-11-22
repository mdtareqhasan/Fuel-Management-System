# Phase 02 · Day 3 — Persistence & Operator Decisions

**Target:** Sunday, 22 November 2026 · **Status:** Complete and verified — 20/20 live end-to-end checks, 126 backend tests, 31 frontend tests, clean production build.

| Milestone | Focus areas | Intended result |
|:--|:--|:--|
| Persistence & operator decisions | Durable records, recommendation review, safe approval, command reconciliation | Operator can review and approve a proposal once, then follow its shipment lifecycle |

## Executive summary

Day 3 connected the advisory planning flow to a durable, operator-controlled decision process. The application records snapshots, proposals, proposal lines, shipment commands, and audit events in PostgreSQL. A proposal does not dispatch fuel: an operator must review and approve it, and approval revalidates the proposal against a fresh simulator read before sending.

The command worker saves the idempotency key and exact request body before sending. If the simulator may have accepted a request but the response is lost, the command is marked `UNKNOWN` and reconciled by key instead of being submitted again. The end-to-end verifier confirmed this behavior along with duplicate-approval refusal, reviewed-quantity limits, shipment tracking, and audit records: **20/20 checks passed**. The backend suite passed **126 tests**, the frontend suite passed **31 tests**, and the frontend production build completed cleanly.

## Delivered

### Persistence

- `V1__day3_persistence_and_decisions.sql` — five tables: `snapshots`, `proposals`, `proposal_lines`, `commands`, `audit_events`.
- The database enforces what the code promises: `uq_command_line (proposal_line_id)` makes one-command-per-line structural, `uq_command_key (idempotency_key)` makes one-key-per-shipment structural, and `chk_final_not_above_reviewed` makes a quantity that rises above what was reviewed impossible to store.
- Every table carries `run_id` as `TEXT`, matching the opaque string the domain uses. A simulator reset starts a new application run, so old records stay readable but never mix with new ones.

### The decision loop

- `ProposalService` builds a proposal from the current plan and **performs no simulator write** — creating a proposal reads the world, it never mutates it.
- `FreshnessPolicy` decides whether a proposal is still worth approving, and the rule is mode-aware (ADR 004): in `PAUSED` the tick must match exactly; in `RUNNING` a tick lag is allowed because approval revalidates against a fresh read.
- `Revalidator` checks every line against a fresh world. A line may be sent **less**, never more. If the route, station, depot or snapshot changed, the line sends **nothing** and the operator is told which line changed and why.
- `Revalidator.DispatchBudget` re-reserves the depot's shared dispatch allowance at approval time, so a six-line proposal does not produce six shipments the simulator will refuse.
- `ApprovalService` refuses a duplicate approval with `ALREADY_APPROVED` before it looks at freshness — "you already approved this" is the answer the operator needs, not "the world moved on".

### The command and its ledger

- `CommandWorker` persists the idempotency key and the **exact** request body before any POST (INV-10), and a retry sends those same bytes (INV-11).
- A timeout is recorded as `UNKNOWN`, never `REJECTED`. The shipment may exist, and an operator must be able to see that it might.
- `CommandWorker.reconcile` resolves an unknown command by matching the idempotency key against `GET /v1/allocations` — never by sending again.
- `ShipmentTracker` advances the simulator's own status (`PENDING → IN_TRANSIT → ARRIVED`) while the application command stays `SENT`. The ledger shows both columns, because they are different facts.

### The operator console

- `DecisionPanel.tsx` — the proposal review list, the review dialog with an explicit upper bound, the approval error reasons, and the shipment ledger with the two status columns.
- `useDecisions.ts` / `decisionTypes.ts` — the data layer, with the decision types kept in one place so the UI and the API cannot drift apart.
- The simulator route inventory table was added to the dashboard, so route IDs, endpoints, transit ticks, capacity and status are visible without an existing shipment.

## Bugs found and fixed during Day 3

Each of these was found by running the thing, not by reading it.

| Bug | Symptom | Fix |
|---|---|---|
| `.grid` CSS name collision | The Day 3 tables rendered as a CSS **grid container** instead of a table: headers bunched left, data pushed far right. The class name already existed as a card layout in `App.tsx` and `RiskPanel.tsx`. | Renamed the table class to `.decision-table`. The card grid keeps `.grid`. Guarded by `verify_frontend_day3_css.py`. |
| Audit details built by string concatenation | A simulator error message contains a double-quoted URL. Concatenating it into JSON produced invalid JSON, the audit insert failed, and — because it shared a transaction with the command's status update — the failure rolled the status update back. The command stayed `APPROVED` when the truth was `UNKNOWN`. | Audit details are now a `Map` serialised by Jackson, which cannot produce invalid JSON. Guarded by `AuditRepositoryTest`. |
| Audit write shared the decision's transaction | Every **refused** approval rolled its own `APPROVAL_REJECTED` record back with it, so the trail showed only the successes. | `AuditRepository.record` now runs with `Propagation.REQUIRES_NEW`. |
| `run_id` typed `UUID` in the schema | The domain carries the run id as an opaque string, so the write failed with a `ClassCastException`. | The column is `TEXT`; the migration comment records why. |
| Missing `::jsonb` casts | `alternatives` and `request_body` were inserted as text and rejected by PostgreSQL. | Explicit casts in the repository SQL. |
| `ClassCastException` reading a `TEXT` run id | The repository read it back as a `UUID`. | `rs.getString`. |
| Duplicate command insert on a concurrent approval | The second insert hit the unique constraint and surfaced as an unhandled exception. | The duplicate is caught and reported as an already-existing command, which is what makes a duplicate approval a no-op. |
| The frontend image was never rebuilt | The UI showed none of the Day 3 work even though the code was correct. | `docker compose build frontend` before `docker compose up -d frontend`. |

## Verification evidence

All of it is in [`docs/evidence/day-03/`](../evidence/day-03/), and every number in it was measured against the running stack.

| Evidence | What it shows |
|---|---|
| [`end-to-end-verification.txt`](../evidence/day-03/end-to-end-verification.txt) | **20/20 checks passed.** Proposal creation performs no simulator write · duplicate approval refused with `ALREADY_APPROVED` and no second allocation · a quantity above the reviewed one refused with HTTP 422 · a reduced quantity accepted · a timed-out write observed as `UNKNOWN` before reconciliation · reconciling creates no second shipment · the shipment reaches a terminal state · the audit trail records the decisions. |
| [`backend-tests.txt`](../evidence/day-03/backend-tests.txt) | `mvn test` — **126 tests passed**, 0 failures, 0 errors. |
| [`frontend-tests.txt`](../evidence/day-03/frontend-tests.txt) | `npm test` — **31 tests passed**; `npm run build` — clean. |
| [`frontend-checks.txt`](../evidence/day-03/frontend-checks.txt) | All **12 Day 3 UI strings** present in the served bundle, and the `.decision-table` CSS rules present with `.grid` still a grid container. |
| [`schema-and-ledger.txt`](../evidence/day-03/schema-and-ledger.txt) | The schema as the database actually holds it: five tables, the unique constraints, the `final_quantity <= reviewed_quantity` check, and the live ledger contents. |
| [`backend-log-during-verifier.txt`](../evidence/day-03/backend-log-during-verifier.txt) | The raw backend log from the run that exposed the invalid-JSON failure. |
| [`README.md`](../evidence/day-03/README.md) | What each evidence file is and how to reproduce it. |

### The lost-response case, in full

This is the case the whole day was built around, so it is worth showing end to end:

```
latency fault injected -> HTTP 201
approval under latency -> HTTP 500
command fcbdca78 status=UNKNOWN attempts=1
  last_error=I/O error on POST request for "http://simulator-api:8000/v1/allocations": Request cancelled
[PASS] a timed-out simulator write is observed as UNKNOWN before reconciliation
simulator allocations with the key before reconciling: 1
simulator allocations with the key after reconciling:  1
[PASS] reconciling does not create a second shipment
after reconciling: command=SENT simulator=PENDING allocation=7
```

The shipment existed. The application did not know that. It said `UNKNOWN` rather than `REJECTED`, and it resolved the uncertainty by matching the key — one shipment before, one shipment after.

## Known limitations

- The end-to-end verifier mutates the live simulator and database. It must be run against the disposable local stack, not against a demo state that is meant to be preserved.
- The anomaly detector's thresholds are proposed, not tuned. A spike shorter than three ticks is not detected.
- `REQUIRES_NEW` on every audit write costs one extra commit per decision. That is paid deliberately, but it is a real cost.
- The command worker sends on the request thread. A high volume of approvals would want a queue behind it.
- The frontend must be rebuilt (`docker compose build frontend`) after any frontend change, or the container keeps serving the previous bundle.

## Related documents

- [ADR 009 — the decision record and the shipment ledger are two different things](../adr/ADR-009-decision-record-and-shipment-ledger.md)
- [ADR 003 — operator approval](../adr/ADR-003-operator-approval.md)
- [ADR 004 — mode-aware approval](../adr/ADR-004-mode-aware-approval.md)
- [Acceptance and invariants](../acceptance-and-invariants.md) — INV-37 to INV-46
- [Current state](../current-state.md)

Detailed schedule: [Implementation guide, Day 3](../../Fuel%20Management%20System%20md%20file/Fuel_Management_Implementation_Guide_v2.md).
