# Day 3 evidence

The live-run results were measured against the official simulator, backend, and database. Every number in this directory came from a run of the script that produces the file beside it.

Regenerate any file with the command in its section below.

---

## `end-to-end-verification.txt`

**20/20 checks passed.** The raw stdout of the run is in this file, and the verifier exits non-zero if any check fails.

```powershell
python scripts/verify_day3_end_to_end.py
```

What it proves, in order:

1. The plan has at least one feasible shipment to review.
2. **Creating a proposal performs no simulator write** — allocations before = 0, after = 0.
3. The first approval succeeds (HTTP 200).
4. The approval created **exactly one** simulator allocation per proposal line.
5. A duplicate approval is refused with `ALREADY_APPROVED` (HTTP 409) and creates **no** second allocation.
6. A quantity above the reviewed amount is refused (HTTP 422).
7. A reduced quantity is accepted, and no line ships more than was reviewed.
8. A timed-out simulator write is observed as **UNKNOWN** before reconciliation.
9. Reconciling creates **no** second shipment.
10. The reconciled command points at the shipment that already existed.
11. The ledger shows every command with its application status and the simulator's status separately.
12. At least one command reached `SENT`, and the shipment reached a terminal state.
13. The ledger's simulator column tracks the simulator's own status.
14. The audit trail recorded the decisions.

> **This verifier mutates the live simulator and the live database.** Run it against the disposable local stack, not against a demo state you want to preserve.

### Why the verifier pauses the simulator

The review stages run against a **paused** world, and that is not a convenience — it is the only way the rules can be observed at all.

The approval path is mode-aware by design (ADR 004). The measurements that forced this:

| Measurement | Value | Consequence |
|---|---|---|
| Simulator tick rate | **~5.46 ticks/sec** | The world moves ~11 ticks during a single request cycle |
| Simulator request latency | **~2.05 s** (clean, no fault) | Each approval spans ~11 ticks of world movement |
| Backend read timeout | **2.0 s** (`SIMULATOR_RESPONSE_TIMEOUT_MS`) | A clean simulator read sits right on the timeout boundary |

In RUNNING mode a proposal is stale before an operator can approve it, so the approval is refused with `PROPOSAL_CHANGED` — and that refusal happens **before** the quantity rule is reached. A verifier that measured "the quantity is refused" in RUNNING mode would actually have been measuring the freshness guard.

In PAUSED mode the tick only moves when the operator steps it, so a proposal stays reviewable. The verifier therefore pauses the simulator before the review stages and steps the tick between them.

### Why the verifier steps the tick between stages

The depot's dispatch allowance is **shared per depot** across fuels and destinations, and it is **consumed at POST time and reset each tick**. The first approval in a run ships the whole network ceiling (23,000 L), so without a tick step every later stage finds the allowance already spent and every line comes back `CHANGED` for the wrong reason.

The verifier therefore steps one tick and **waits for the backend's cached snapshot to reach that tick** before it asks for a proposal. Without that wait the backend still holds the previous tick — where the allowance is spent — and the stage measures the budget instead of the rule.

### Three verifier defects found and fixed

Each of these made the verifier report a failure that was not a product defect, or hide a real one.

| # | What happened | Why it mattered | Fix |
|---|---|---|---|
| 1 | The quantity stages picked `pending[0]` — the **oldest** pending proposal | The approval path checks freshness first, so the stage measured `PROPOSAL_CHANGED` instead of `QUANTITY_EXCEEDS_REVIEWED`. The rule under test was never reached. | `fresh_pending_proposal()` returns a proposal for the **current** tick. |
| 2 | The lost-response stage asked for a hardcoded **1,000 L** | When the line was smaller, the simulator answered `422 QUANTITY_EXCEEDS_REVIEWED` — the quantity rule, not a lost response. | The stage now asks for the line's **own** reviewed quantity. |
| 3 | The latency fault window was **20 s** with `delay_ms: 4000` | The approval path makes several simulator reads before it writes. At ~10 s per request under the fault, the window expired mid-approval and the write came back `200`. The check measured nothing. | The window is **120 s** with `delay_ms: 8000`, so the write is genuinely lost. |

The lost-response stage now produces exactly the behaviour it is named for:

```
command 8bb2f313 status=UNKNOWN attempts=1
  last_error=I/O error on POST request for "http://simulator-api:8000/v1/allocations": Request cancelled
simulator allocations with the key before reconciling: 1
simulator allocations with the key after reconciling:  1
after reconciling: command=SENT simulator=PENDING allocation=11
```

The write timed out, the application recorded `UNKNOWN` rather than `REJECTED`, reconciliation found the shipment that already existed, and **no second shipment was created**.

---

## `backend-tests.txt`

The backend suite. **126 tests passed**, 0 failures, 0 errors.

```powershell
cd backend
mvn test
```

Day 3 added 26 tests over the Day 1–2 baseline of 100:

| Test class | Tests | Covers |
|---|--:|---|
| `RevalidatorTest` | 12 | Fresh-world revalidation, the shared dispatch budget, the quantity ceiling |
| `FreshnessPolicyTest` | 7 | Mode-aware freshness: exact tick in `PAUSED`, lag tolerance in `RUNNING` |
| `ApprovalServiceTest` | 13 | Duplicate approval, quantity ceiling, reduced approval, no-write-on-creation |
| `CommandWorkerTest` | 6 | `SENT` on confirm, `UNKNOWN` on a lost response, `REJECTED` on a domain refusal, reconciliation |
| `AuditRepositoryTest` | 5 | Audit details are always valid JSON, including a message containing a double quote |

`AuditRepositoryTest` is a regression test for a real production failure. Its first case reproduces the exact message that broke the build:

```
I/O error on POST request for "http://simulator-api:8000/v1/allocations": Request cancelled
```

---

## `frontend-tests.txt`

The frontend suite and the production build. **31 tests passed**, build clean.

```powershell
cd frontend
npm test -- --run
npm run build
```

| Test file | Tests | Covers |
|---|--:|---|
| `filters.test.ts` | 22 | Fuel and region filters (Day 2) |
| `decisionTypes.test.ts` | 9 | The decision types, the status vocabulary, the unknown-outcome distinction |

The build output in the same file records the artefact sizes:

```
dist/assets/index-Eq5v1d4E.css   17.00 kB │ gzip:  3.86 kB
dist/assets/index-CCmhqFR7.js   182.90 kB │ gzip: 55.78 kB
✓ built in 1.91s
```

---

## `frontend-checks.txt`

Two checks against the **served** bundle — the one the browser actually downloads, not the one on disk.

```powershell
python scripts/verify_frontend_day3_ui.py
python scripts/verify_frontend_day3_css.py
```

**`verify_frontend_day3_ui.py`** confirms all 12 Day 3 UI strings are in the bundle: the `Decisions` heading, `Waiting for review`, `Shipment ledger`, `Maximum to ship`, `Outcome unknown`, `Re-review required`, `Approved with a reduction`, the `simulator_status` and `command_status` columns, the `Approve up to` column, the `Routes` section and the `Max shipment` column.

**`verify_frontend_day3_css.py`** confirms the Day 3 table rules are present *and* that the `.grid` card layout is intact:

```
  PASS  decision-table rule present
  PASS  decision-table th/td rule present
  PASS  decision-table hover rule present
  PASS  .grid is still display:grid (card layout)
  PASS  no .grid th/.grid td table rules
```

That second script is the guard against the bug that made the tables render wrong: `.grid` already existed as a CSS grid container for the card layouts in `App.tsx` and `RiskPanel.tsx`, and the Day 3 table rules redefined it as a table. The two fought, and the table rendered as a grid — headers bunched left, cells pushed far right.

---

## `schema-and-ledger.txt`

The Day 3 schema **as the database actually holds it**, plus the live ledger contents.

```powershell
python scripts/inspect_day3_schema.py
```

It prints the tables, the columns, the constraints, the indexes, the row counts, the status breakdowns, and the Flyway migration history.

The constraints that carry the Day 3 rules:

```
commands       | uq_command_line        | UNIQUE (proposal_line_id)
commands       | uq_command_key         | UNIQUE (idempotency_key)
proposal_lines | chk_final_not_above_reviewed | CHECK ((final_quantity IS NULL) OR (final_quantity <= reviewed_quantity))
proposal_lines | uq_proposal_line        | UNIQUE (proposal_id, line_id)
```

These are structural, not procedural. A second command for one proposal line cannot be stored, and a final quantity above the reviewed quantity cannot be stored.

---

## `backend-log-during-verifier.txt`

The raw backend log captured during the run that exposed the audit bug.

```powershell
docker compose logs backend --tail 200 > docs/evidence/day-03/backend-log-during-verifier.txt
```

The relevant lines:

```
ERROR ... threw exception [Request processing failed:
org.springframework.dao.DataIntegrityViolationException:
PreparedStatementCallback; SQL [INSERT INTO audit_events ];
ERROR: invalid input syntax for type json
  Where: JSON data, line 1: ...SEL","error":"I/O error on POST
  request for "http...
  at com.fuelplatform.audit.AuditRepository.record(AuditRepository.java:54)
  at com.fuelplatform.command.CommandWorker.send(CommandWorker.java:158)
```

The error message contains a double-quoted URL. Concatenated into a JSON string, it produced invalid JSON. The insert failed, and because it shared a transaction with the command's status update, the failure rolled the status update back — leaving the command at `APPROVED` when the truth was `UNKNOWN`.

The fix is in [ADR 009](../../adr/ADR-009-decision-record-and-shipment-ledger.md): the audit details are a `Map` serialised by Jackson, and the audit write runs in its own transaction.
