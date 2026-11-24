# Day 5 evidence â€” Evaluation & Release Rehearsal

Captured on 23 November 2026 against the local Compose stack and the current
Java planner. Read the limitations beside each result; synthetic planner
fixtures are not simulator outcomes.

| Artifact | Result | Notes |
|---|---|---|
| [`policy-comparison.json`](policy-comparison.json) | Eight deterministic topology-based fixtures per policy across normal, 1.8Ã— demand, route disruption and combined scenarios; tick 0 and a stepped tick 4. Both policies had 0 dispatch-cap violations. | Production `AllocationPlanner`; baseline is 4-hour cover/no reserve and candidate is 6-hour cover/200 L reserve. Candidate proposed 78,454 L vs 24,082 L baseline (+225.78%). This is proposal-volume sensitivity, not proof of better realized service. Scenario transformations are not live injected events. |
| [`anomaly-replay.txt`](anomaly-replay.txt) | Quiet profile: 0 false flags across 12 station/fuel series. Injected Dhaka 1.8Ã— event: R1 and R2 detected two ticks after injection; backend independently reported 6 event-attributed flags. | Ran against simulator seed 12345. The verifier was fixed to evaluate each fuel series separately; the prior version incorrectly combined fuels within one tick. |
| [`load-summary.json`](load-summary.json) | One-shot concurrent GET stages: PAUSED 1/5/10/25 VUs and RUNNING 25 VUs, 0 HTTP errors; simulator returned to PAUSED, fresh and decision-eligible. | Windows 11, AMD64, 12 logical CPUs. At 25 RUNNING VUs, `/api/risk` reached 11.77 s p95 in this burst. This is a short concurrency probe, not a sustained soak or browser-rendering test. |
| [`load-test-report.md`](load-test-report.md) | Detailed workload, methodology, stage/endpoint latency table, interpretation and follow-up plan. | Interpret small-sample percentiles cautiously; separate repeated stress run hit simulator pool limits. |
| [`simulator-overload-logs.txt`](simulator-overload-logs.txt) | A prior repeated 25-user stress attempt exceeded the simulator's SQLAlchemy pool (`size 5 + overflow 10`), producing connection checkout timeouts. | The load was stopped. Simulator and backend containers were restarted; the backend recovered to `FRESH`, breaker `CLOSED`, stream `CONNECTED`. This is a measured simulator capacity boundary. |
| [`backend-verification.txt`](backend-verification.txt) | `mvn clean verify`: **155 tests passed**, no failures/errors/skips, `BUILD SUCCESS`. | Includes the Day 5 production-planner scenario comparison test. |
| [`frontend-verification.txt`](frontend-verification.txt) | Frontend tests and production build. | 31 tests; clean TypeScript/Vite build. |
| [`command-reconciliation.txt`](command-reconciliation.txt) | Current end-to-end verifier: **18/18 checks passed**, including duplicate approval refusal, reviewed-quantity bounds, lost-response `UNKNOWN` reconciliation without a second shipment, terminal arrival, and the `APPROVAL`/`COMMAND_RECONCILED` audit rows. | The verifier resets and mutates the simulator and database. Simulator was reset to PAUSED tick 0 afterward. |
| [`compose-startup.txt`](compose-startup.txt) | Compose config, both image builds, full service recreation with named volumes retained, Flyway validation/current schema, and the dashboard/status/metrics smoke endpoints. | All six services healthy; dashboard returned four stations, two depots and six routes; status was `FRESH` and decision-eligible. |
| [`final-stack-state.txt`](final-stack-state.txt) | All six Compose services running; backend/database/simulator/frontend healthy; PAUSED tick 77, FRESH and decision-eligible. | Captured after the three agent-run technical rehearsals and cleanup. |
| [`rehearsals.md`](rehearsals.md) | Two agent-run end-to-end replays passed (20/20 and 21/21); unavailable-fault drill recovered after refusing unsafe approval. | Technical rehearsals only; three human UI walkthroughs and unannounced surprise drill remain pending. See [run 1](rehearsal-run-1.txt), [run 2](rehearsal-run-2.txt), [fault drill](rehearsal-run-3-surprise-fault-drill.txt), and the [initial permission-limited attempt](rehearsal-run-1-initial-attempt.txt). |
| [`rehearsal-run-1.txt`](rehearsal-run-1.txt) | **20/20** end-to-end checks passed in 5m 23s. | Includes approval, bounds, reconciliation, arrival and audit checks. |
| [`rehearsal-run-2.txt`](rehearsal-run-2.txt) | **21/21** end-to-end checks passed in 5m 28s. | Second agent-run replay. |
| [`rehearsal-run-3-surprise-fault-drill.txt`](rehearsal-run-3-surprise-fault-drill.txt) | Fault invalidated the data view and blocked approval; recovery returned to FRESH/CLOSED. | Agent-triggered technical drill, not an unannounced human surprise drill. |
| [`rehearsal-run-1-initial-attempt.txt`](rehearsal-run-1-initial-attempt.txt) | Initial **18/19** attempt. | Final DB check could not access Docker under the sandbox; successful rerun is recorded above. |

## Reproduce

```powershell
mvn.cmd -B clean verify                    # from backend/
npm.cmd run test; npm.cmd run build        # from frontend/
python scripts/day5_load_test.py           # from repository root; changes simulator mode
python scripts/verify_anomaly_detection.py # mutates simulator tick and injects an event
python scripts/verify_day3_end_to_end.py    # resets and mutates simulator/database
```

The load harness restores the simulator's original PAUSED/RUNNING mode. The
anomaly verifier advances ticks and schedules an event; use a disposable local
simulator and reset it before preserving a demo state.


