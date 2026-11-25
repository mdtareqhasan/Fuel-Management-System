# Demo rehearsal record

The intended eight-minute operator sequence is in [`docs/demo-runbook.md`](../../demo-runbook.md).
The records below are **agent-executed technical rehearsals** against the local
Compose stack. They validate the API, simulator, database, fault guard and
recovery paths; they do not count as a human operator presenting the UI or
responding to a surprise without prior knowledge. The project owner has now
confirmed that the three human checks were completed. Exact times, durations,
presenter/observer names and commit used were not provided, so those details
are intentionally left unrecorded rather than inferred.

Commit `b04538f` was the base SHA; the working tree contained uncommitted Day 5
changes during these runs. Simulator resets preserve the same simulator run ID
in this integration, while the app/database accumulates audit and shipment test
records. All runs were local.

| Run | Date/time (Asia/Dhaka) | Run ID | Commit SHA | Duration | Result / follow-up |
|---|---|---|---|---|---|
| 1 · end-to-end replay | 23 Nov 2026, 19:47–19:52 | `e547d915-2a76-4045-85dd-35977a13ff23` | `b04538f` + dirty tree | 5m 23s · **20/20 passed** | Agent-run technical replay. See [`rehearsal-run-1.txt`](rehearsal-run-1.txt). |
| 2 · end-to-end replay | 23 Nov 2026, 19:53–19:58 | `e547d915-2a76-4045-85dd-35977a13ff23` | `b04538f` + dirty tree | 5m 28s · **21/21 passed** | Agent-run technical replay. See [`rehearsal-run-2.txt`](rehearsal-run-2.txt). |
| 3 · unavailable-fault drill | 23 Nov 2026, 19:59–20:00 | `e547d915-2a76-4045-85dd-35977a13ff23` | `b04538f` + dirty tree | 1m 23s | Agent-triggered technical drill; it is not the human surprise check. See [`rehearsal-run-3-surprise-fault-drill.txt`](rehearsal-run-3-surprise-fault-drill.txt). |
| Human UI walkthrough 1 | Complete — owner confirmed 25 Nov 2026 | Not provided | Not provided | Not provided | User-confirmed complete; detailed run metadata not supplied. |
| Human UI walkthrough 2 | Complete — owner confirmed 25 Nov 2026 | Not provided | Not provided | Not provided | User-confirmed complete; detailed run metadata not supplied. |
| Human UI walkthrough 3 + surprise check | Complete — owner confirmed 25 Nov 2026 | Not provided | Not provided | Not provided | User-confirmed complete; detailed run metadata not supplied. |

## Still needed for release sign-off

- Push the candidate and record the GitHub-hosted CI result; tag only the
  verified release SHA.
