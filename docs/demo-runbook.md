# Eight-minute release demo runbook

This runbook is a repeatable technical walkthrough. The operator must perform
and record the live rehearsals; this document does not claim they have happened.

## Before the demo

1. Use a disposable local stack. Run `docker compose up --build -d` and wait
   until `docker compose ps` shows all six services healthy.
2. Check `http://localhost:8080/api/system-status`: backend, database and
   simulator `UP`; data `FRESH`; `decision_eligible=true`; breaker `CLOSED`;
   event stream `CONNECTED`.
3. Check `http://localhost:9090/-/healthy` and confirm the eight Prometheus
   rules are loaded. Open Grafana's Fuel Platform resilience dashboard.
4. Reset the simulator only when its current state is disposable:
   `python scripts/sim_control.py reset`.
5. Record the run ID, commit SHA, starting tick and mode in the rehearsal notes.

## Eight-minute sequence

| Time | Action | What to explain |
|---|---|---|
| 0:00–1:00 | Show the operator console status, tick, mode, data age, stations, depots and routes. | Simulator ticks describe the fuel world; freshness and request timeouts use wall-clock time. The simulator owns inventory and shipment truth. |
| 1:00–2:00 | Show the risk and anomaly views, including forecast method/confidence and reasons. | A profile fallback is marked low-data; anomaly thresholds are proposed. A risk flag is decision support, not a shipment. |
| 2:00–3:00 | Create a proposal and open its review. | Creating a proposal does not call the simulator's allocation endpoint. The review shows the quantity envelope, route and unmet needs. |
| 3:00–4:30 | In RUNNING mode, approve a reviewed quantity through the UI, then show the command and simulator shipment statuses separately. | The server rereads and revalidates at approval. It may send less than reviewed, never more. `SENT` and `IN_TRANSIT` describe different systems. |
| 4:30–6:00 | Inject a short unavailable fault with `python scripts/inject_fault.py unavailable 15`; show cached/invalid status and the refused-approval reason. | `/v1/health` alone is not data freshness. The read breaker stops repeated timeout calls; unsafe approval is blocked. |
| 6:00–7:00 | Let the fault clear; show REST polling/SSE refresh, `FRESH`, and the breaker returning to `CLOSED`. | SSE triggers a REST reread; it does not become the source of truth. |
| 7:00–8:00 | Show Grafana/Prometheus, the shipment ledger and one audit event; state the known limits. | One measured high-concurrency read run reached the simulator's SQL connection-pool boundary. The planner comparison is a deterministic sensitivity replay, not a measured service-level gain. |

## Cleanup

Run `python scripts/sim_control.py clear-faults`, then pause with
`python scripts/sim_control.py pause`. Confirm the backend returns to `FRESH`
and decision-eligible. Reset the simulator only if the next demo needs a clean
seed and the current data can be discarded.

## Rehearsal record

Record each actual run in `docs/evidence/day-05/rehearsals.md`: start/end time,
run ID, commit SHA, whether the sequence fit eight minutes, failed step, and
the fix or follow-up. An empty or planned row is not a completed rehearsal.
