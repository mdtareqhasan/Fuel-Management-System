# ADR 003 — Operator Approval Is Mandatory and Server Enforced

**Status:** Accepted · **Date:** 2026-11-20

## Context

The brief's section 26 requires: *"preserve human review for consequential simulated decisions."* Section 9 requires recommendations to be inspectable with reasons, constraints, expected impact, uncertainty and alternatives.

There is also a practical pressure: an autonomous dispatcher would score better on `service_level`, because it would allocate without waiting for a human.

Options:

1. Fully autonomous allocation — the application decides and ships.
2. Operator reviews and approves every proposal.
3. Operator approves, but the application auto-approves under some threshold.

## Decision

**No allocation reaches the simulator without an operator approval, and the approval is enforced on the server.**

- A recommendation is *generated* without any simulator write. `POST /api/recommendations` never touches `/v1/allocations`.
- `POST /api/recommendations/{id}/approve` re-reads the world, revalidates the reviewed envelope, and only then persists a command. A later worker performs the POST.
- The frontend's approve button is a convenience. Disabling it is **not** the security control — the server re-checks freshness, eligibility and the reviewed quantity on every call.
- The simulator's `POST /v1/allocations` is reachable from the application only through the `command` module.

No threshold-based auto-approval exists in the first release.

## Alternatives rejected

- **Fully autonomous.** Violates an explicit brief constraint and removes the operator review the rubric awards marks for under "Decision Support" and "Demo and understanding".
- **Threshold auto-approval.** A small quantity is still a dispatch. It also introduces a silent path where the human-review invariant quietly stops holding, and it is very hard to explain in a demo.
- **LLM-approved.** The deterministic validator and the operator are both stronger guarantees. A generative model may *explain* a decision but may never authorise one.

## Consequences

- **Positive:** every shipment in the audit trail has a named human actor, a proposal id, the snapshot it was based on, and the quantity the human actually saw.
- **Positive:** the demo has a natural narrative — inspect, reason, approve, watch it arrive.
- **Negative:** the service level will be worse than an autonomous dispatcher during a fast crisis. This is an accepted, documented trade-off, and the baseline comparison on day 5 measures it rather than hiding it.
- **Negative:** the operator must be present. Acceptable for a simulated operations console.

## Verification

Day 3 delivers the duplicate-approval test (one proposal line → exactly one simulator allocation) and the mode-aware revalidation tests in both PAUSED and RUNNING. The audit record stores actor, proposal id, snapshot id, reviewed quantity and final quantity.
