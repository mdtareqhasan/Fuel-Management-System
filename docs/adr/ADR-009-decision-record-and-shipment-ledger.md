# ADR 009 — The Decision Record and the Shipment Ledger Are Two Different Things

**Status:** Accepted · **Date:** 2026-11-22 · **Extends ADR 003 and ADR 004**

## Context

ADR 003 established that no allocation reaches the simulator without a server-enforced operator approval, and ADR 004 established that a proposal is a *reviewed envelope* — an upper bound that may fall but never rise.

Day 3 built the machinery that carries those rules. Two decisions were made along the way that are not covered by ADR 003 or ADR 004, and both are easy to get wrong in ways that are not obvious from the code alone.

### Decision 1 — The audit write must not share a transaction with the decision

ADR 003 says the approval must be *recorded*. It does not say what happens when the recording itself fails.

The obvious implementation is to write the audit record inside the same transaction as the decision. That is wrong for two reasons, and both were observed in this build:

**First, a refused approval rolled its own audit record back.** Every refusal path in `ApprovalService` writes an `APPROVAL_REJECTED` event and then throws. The throw rolls back the transaction — and with it, the event. So the trail showed only the decisions that succeeded. An audit trail that only records successes is not an audit trail; it is a success log.

**Second, and far more dangerous, a malformed audit detail rolled back the *command status*.** The audit details were built by string concatenation:

```java
"{\"key\":\"" + command.idempotencyKey() + "\",\"error\":\"" + failure.detail() + "\"}"
```

The simulator's transport error message contains the URL of the failed request, and that URL is wrapped in double quotes:

```
I/O error on POST request for "http://simulator-api:8000/v1/allocations": Request cancelled
```

Concatenating that into a JSON string produces invalid JSON. The insert failed with `invalid input syntax for type json`. Because the audit insert shared a transaction with the command's status update, the failure rolled back the status update too. The command stayed `APPROVED` when the truth was `UNKNOWN`.

That distinction is the one the whole design exists to protect. `UNKNOWN` means *the shipment may exist*. `APPROVED` means *not sent yet*. An operator reading `APPROVED` would reasonably approve again, and the second approval would create a second shipment — exactly the duplicate that INV-12 and the idempotency key exist to prevent.

The measured failure is recorded in `docs/evidence/day-03/backend-log-during-verifier.txt`:

```
ERROR: invalid input syntax for type json
  Where: JSON data, line 1: ...SEL","error":"I/O error on POST
  request for "http...
    at com.fuelplatform.audit.AuditRepository.record(AuditRepository.java:54)
    at com.fuelplatform.command.CommandWorker.send(CommandWorker.java:158)
```

## Decision

**1. The audit write runs in its own transaction.**

`AuditRepository.record` is annotated `@Transactional(propagation = Propagation.REQUIRES_NEW)`. The audit record commits independently of the decision it describes. A failed decision still leaves a record of the attempt, and a failed audit write cannot roll back the decision it was describing.

**2. Audit details are serialised, never concatenated.**

`record` now takes a `Map<String, Object>` and serialises it with Jackson. Jackson cannot produce invalid JSON, so the entire class of bug is closed rather than patched. `AuditRepository.details(...)` is a small builder that tolerates null values, because a null that says "we do not know" must stay visible rather than be dropped.

**3. The command status is updated before the audit record is written.**

The status write and the audit write are now separate transactions with separate failure modes. The status is the thing an operator acts on, so it is the thing that must not be lost.

### Decision 2 — Application command status and simulator shipment status are separate columns

The shipment ledger shows two statuses side by side, and the reason they are not the same column is worth stating.

They answer different questions:

| Column | Question | Who owns it |
|---|---|---|
| `commands.status` | What did **the application** do? | The application |
| `commands.simulator_status` | What is **the shipment** doing now? | The simulator |

The application command is `SENT` the moment the simulator confirms the write. It stays `SENT` forever. That is a fact about the application, and it does not change when the truck arrives.

The shipment moves `PENDING → IN_TRANSIT → ARRIVED`. That is a fact about the simulator, and it changes over time.

Collapsing them into one column would mean a command that was sent but whose shipment later failed would look identical to a command that was rejected before it was sent — and those demand opposite operator responses. Keeping them apart means the ledger can show "we sent this, and it is still in transit" without lying about either half.

`ShipmentTracker` polls the simulator and advances `simulator_status` only. It never touches `commands.status`.

### Decision 3 — The dispatch allowance is re-reserved at approval time

This is the extension of ADR 008 that ADR 008 did not cover.

ADR 008 measured that dispatch allowance is per depot, shared across every fuel and every destination, and consumed at POST time. The allocation planner therefore reserves it in its working copy as it plans (INV-17).

But the allowance is consumed **per tick**, and a proposal may be reviewed across a tick boundary. A proposal planned at tick 70 might be approved at tick 71, where the allowance has reset — or, worse, where it has been partly consumed by something else.

So the allowance is reserved **again** at approval time, by `Revalidator.DispatchBudget`, against a fresh read of the world. The budget is shared across every line of the proposal and every fuel and destination, and it includes fuel already dispatched at the current tick. A line is only approved for the quantity that is left after the lines already checked have taken their share.

This is why a six-line proposal does not produce six shipments the simulator will refuse with `DISPATCH_CAPACITY_EXCEEDED`.

## Alternatives rejected

- **Keep the audit write inside the decision transaction.** Rejected by measurement: it hid every refused decision and, on one occasion, replaced `UNKNOWN` with `APPROVED` on the very command whose outcome was in doubt.
- **Escape the quotes when building the audit JSON by hand.** Rejected. It fixes one character class and leaves the next one — a newline, a backslash, a control character — as a live bug. Serialising a map removes the class.
- **Write the audit record to the log file instead of the database.** Rejected. A log line is not queryable, cannot be joined to the command it describes, and does not survive the container.
- **One status column for the ledger.** Rejected. "Sent and still travelling" and "refused before it was sent" would be indistinguishable, and they need opposite responses.
- **Reuse the allocation planner's reserved budget at approval time.** Rejected. The planner's working copy describes the world at planning time. Approval happens against a different, later world.

## Consequences

- **Positive:** the audit trail now records refused decisions, which is the point of an audit trail.
- **Positive:** a simulator error message can no longer corrupt the record or the command status it describes.
- **Positive:** the ledger can show both halves of a shipment's life without either half overwriting the other.
- **Negative:** `REQUIRES_NEW` means every audit write is a separate transaction. That is a real cost — one extra commit per decision — and it is paid deliberately.
- **Negative:** the application now holds two kinds of truth about a shipment. The UI has to present both, and the operator has to read both columns.
- **Negative:** the approval path re-reads the world to rebuild the dispatch budget, so approval is more expensive than a simple status flip.

## Verification

- `docs/evidence/day-03/backend-tests.txt` — 126 backend tests passing, including `AuditRepositoryTest`, which reproduces the exact production failure (an error message containing a double quote) and asserts the details are still valid JSON and the message survives intact.
- `docs/evidence/day-03/end-to-end-verification.txt` — 17/17 live checks. The lost-response case now records `status=UNKNOWN attempts=1` with the quoted URL preserved in `last_error`, and reconciling produces no second shipment.
- `docs/evidence/day-03/schema-and-ledger.txt` — the constraints as the database actually holds them: `uq_command_line`, `uq_command_key`, `chk_final_not_above_reviewed`.
