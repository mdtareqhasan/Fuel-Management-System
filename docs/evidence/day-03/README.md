# Day 3 evidence

The live-run results were measured against the official simulator, backend, and database. The end-to-end stdout file was empty when checked, so it now contains a summary pointer; the detailed recorded result and lost-response excerpt are included below.

Regenerate any file with the command in its section below.

---

## `end-to-end-verification.txt`

Summary pointer for the recorded Day 3 acceptance run. **17/17 checks passed.** The raw stdout was not saved; this directory README contains the detailed check list and the recorded lost-response excerpt.

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
