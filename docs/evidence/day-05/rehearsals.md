# Demo rehearsal record

The intended eight-minute operator sequence is in [`docs/demo-runbook.md`](../../demo-runbook.md).
The records below are **agent-executed technical rehearsals** against the local
Compose stack. They validate the API, simulator, database, fault guard and
recovery paths; they do not count as a human operator presenting the UI or
responding to a surprise without prior knowledge. Human demo sign-off remains
pending.

Commit `b04538f` was the base SHA; the working tree contained uncommitted Day 5
changes during these runs. Simulator resets preserve the same simulator run ID
in this integration, while the app/database accumulates audit and shipment test
records. All runs were local.

| Run | Date/time (Asia/Dhaka) | Run ID | Commit SHA | Duration | Result / follow-up |
|---|---|---|---|---|---|
| 1 · end-to-end replay | 23 Nov 2026, 19:47–19:52 | `e547d915-2a76-4045-85dd-35977a13ff23` | `b04538f` + dirty tree | 5m 23s · **20/20 passed** | Proposal no-write, duplicate approval guard, reviewed-quantity bound, lost-response reconciliation, arrival and audit checks passed. An initial sandbox-limited attempt was 18/19 because the verifier could not access Docker for its final DB assertion; the elevated rerun passed. See [`rehearsal-run-1.txt`](rehearsal-run-1.txt) and [`rehearsal-run-1-initial-attempt.txt`](rehearsal-run-1-initial-attempt.txt). |
| 2 · end-to-end replay | 23 Nov 2026, 19:53–19:58 | `e547d915-2a76-4045-85dd-35977a13ff23` | `b04538f` + dirty tree | 5m 28s · **21/21 passed** | Same decision and shipment lifecycle checks passed on a second run. See [`rehearsal-run-2.txt`](rehearsal-run-2.txt). |
| 3 · unavailable-fault drill | 23 Nov 2026, 19:59–20:00 | `e547d915-2a76-4045-85dd-35977a13ff23` | `b04538f` + dirty tree | 1m 23s | Platform changed `FRESH/eligible/CLOSED` → `INVALID/ineligible/OPEN`; approval returned HTTP 409 `STALE_OR_INVALID_SNAPSHOT`; after clearing, it recovered to `FRESH/eligible/CLOSED`. `/v1/health` remained HTTP 200. This was agent-triggered, so it is **not** an unannounced human surprise drill. See [`rehearsal-run-3-surprise-fault-drill.txt`](rehearsal-run-3-surprise-fault-drill.txt). |

## Still needed for release sign-off

- A human presenter performs and times the three UI walkthroughs, including a
  surprise fault initiated by a second person who does not warn the presenter.
- Push the candidate and record the GitHub-hosted CI result; tag only the
  verified release SHA.
