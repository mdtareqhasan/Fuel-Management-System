# Phase 04 · Day 5 — Evaluation & Release Rehearsal

**Target:** Tuesday, 24 November 2026 · **Technical status:** Complete and
locally verified on 23 November · **Release rehearsal:** Agent-run technical
replays and a fault drill are recorded; three human UI walkthroughs and an
unannounced human surprise drill remain pending.

| Milestone | Focus areas | Result |
|:--|:--|:--|
| Evaluation & release rehearsal | Policy comparison, detector replay, concurrency, clean startup, docs and demo | Technical checks and evidence recorded; live rehearsals remain open |

## Executive summary

The production `AllocationPlanner` was evaluated with the same deterministic
topology fixtures for both policy settings across normal, 1.8× demand, route
disruption and combined scenarios. Tick 0 and a simulated tick-4 inventory
step were compared. The 4-hour/no-reserve baseline proposed **24,082 L**; the
6-hour/200 L candidate proposed **78,454 L** (**+225.78%**). Both had zero
shared-depot dispatch-cap violations and zero estimated unmet need in these
fixtures. This is a proposal-volume sensitivity result; it does not establish
that the candidate improves realized service or forecast accuracy. Full inputs
and all 16 policy rows are in
[`policy-comparison.json`](../evidence/day-05/policy-comparison.json).

The detector replay recorded **0 false flags across 12 station/fuel series**
in the quiet profile and detected an injected Dhaka 1.8× spike at **2 ticks**
for both R1 and R2. The backend independently reported six event-attributed
flags. The replay exposed and fixed a verifier bug that had mixed the three
fuels into one consecutive-tick sequence.

The bounded direct-IPv4 HTTP burst completed PAUSED at 1, 5, 10 and 25 users,
then RUNNING at 25 users, with zero HTTP errors and restored the simulator to
PAUSED/FRESH. In the RUNNING 25-user stage, `/api/risk` reached **11.77 s p95**.
A separate repeated stress attempt exceeded the official simulator's
SQLAlchemy connection pool (`size 5 + overflow 10`) and produced connection
checkout timeouts. The simulator and backend were restarted; recovery to a
fresh, eligible snapshot was confirmed. The workload and limit are documented
in [`load-summary.json`](../evidence/day-05/load-summary.json) and
[`simulator-overload-logs.txt`](../evidence/day-05/simulator-overload-logs.txt).

The final local verification passed **155 backend tests**, **31 frontend
tests**, the TypeScript/Vite production build, Docker Compose image builds,
clean service recreation, migration validation, and endpoint smoke checks.
All six services returned healthy; the dashboard showed four stations, two
depots and six routes. GitHub-hosted CI has not been triggered for these
uncommitted changes, and no release tag has been created.

The current approval/reconciliation verifier also passed **18/18 checks** on
the rebuilt stack, including the duplicate-approval guard, reviewed quantity
bound, lost-response reconciliation, terminal arrival and persisted
`COMMAND_RECONCILED` audit event. The script resets the simulator and writes
test rows to the local database; the simulator was returned to PAUSED tick 0
afterward.

I then ran two more timed end-to-end technical rehearsals: **20/20 checks in
5m 23s** and **21/21 checks in 5m 28s**. A third unavailable-fault drill took
**1m 23s** and observed `FRESH/eligible/CLOSED` → `INVALID/ineligible/OPEN`, an
HTTP 409 approval refusal, then recovery to `FRESH/eligible/CLOSED`. These
were executed by the coding agent through the local API, not presented by a
human through the UI; they do not satisfy human rehearsal sign-off. The
replays added test shipment and audit records to the local database.

## Delivered and measured

| Work | Status | Evidence |
|---|---|---|
| Baseline/candidate comparison in the production allocation planner | Done as deterministic fixture sensitivity analysis | [`policy-comparison.json`](../evidence/day-05/policy-comparison.json) |
| Quiet and injected-spike anomaly replay | Done; verifier now evaluates each station/fuel sequence separately | [`anomaly-replay.txt`](../evidence/day-05/anomaly-replay.txt), `scripts/verify_anomaly_detection.py` |
| PAUSED stages (1/5/10/25 VUs) and RUNNING 25-VU stage | Done as one-shot simultaneous read bursts; zero HTTP errors; RUNNING risk path was slow | [`load-summary.json`](../evidence/day-05/load-summary.json), [`day5_load_test.py`](../../scripts/day5_load_test.py) |
| Approval, command reconciliation and arrival regression | Done; 18/18 current end-to-end checks passed | [`command-reconciliation.txt`](../evidence/day-05/command-reconciliation.txt) |
| Simulator capacity boundary and recovery | Pool exhaustion captured during repeated stress; simulator and backend recovered after restart | [`simulator-overload-logs.txt`](../evidence/day-05/simulator-overload-logs.txt), [`compose-startup.txt`](../evidence/day-05/compose-startup.txt) |
| Backend/front-end verification | Done; 155 backend tests, 31 frontend tests, clean build | [`backend-verification.txt`](../evidence/day-05/backend-verification.txt), [`frontend-verification.txt`](../evidence/day-05/frontend-verification.txt) |
| Compose image build, clean recreation, Flyway check and HTTP smoke | Done without deleting named volumes | [`compose-startup.txt`](../evidence/day-05/compose-startup.txt) |
| Demo runbook and evidence index | Done | [`demo-runbook.md`](../demo-runbook.md), [Day 5 evidence index](../evidence/day-05/README.md) |
| Agent-run technical rehearsals | Two end-to-end replays passed; unavailable-fault drill recovered | [`rehearsals.md`](../evidence/day-05/rehearsals.md), run logs in the evidence folder |
| Three eight-minute human UI walkthroughs and surprise drill | Pending actual presenter and observer | [`rehearsals.md`](../evidence/day-05/rehearsals.md) |
| Hosted CI result and release tag | Pending a push/authorized release action | CI workflow: `.github/workflows/ci.yml` |

## Limits and interpretation

- The policy replay uses deterministic scenario transformations with the
  production planner; it does not inject all four crises into the live
  simulator and compare realized service metrics.
- The load harness is a bounded concurrency burst, not a sustained soak or a
  browser-rendering benchmark. The repeated stress attempt did reveal the
  simulator's database-pool boundary; do not infer unlimited 25-user capacity.
- The threshold set remains proposed, not tuned. The replay verifies the
  implemented quiet/spike behavior, not general real-world predictive quality.
- The Compose stack was rebuilt and recreated from this working tree with
  named volumes preserved. This is not a fresh Git clone.
- Run the three operator walkthroughs and surprise drill before calling the
  release rehearsal complete. Record duration, run ID, commit SHA, and any
  repair in [`rehearsals.md`](../evidence/day-05/rehearsals.md).

## Reproduce the local checks

```powershell
# from backend/
mvn.cmd -B clean verify

# from frontend/
npm.cmd run test
npm.cmd run build

# from the repository root; changes simulator mode/time
python scripts/day5_load_test.py
python scripts/verify_anomaly_detection.py
python scripts/verify_day3_end_to_end.py   # resets/mutates simulator and writes DB rows

# clean service recreation; keeps named volumes
docker compose config -q
docker compose build
docker compose up -d --force-recreate
```

## Related documents

- [Eight-minute demo runbook](../demo-runbook.md)
- [Day 5 evidence index](../evidence/day-05/README.md)
- [Current state](../current-state.md)
- [Implementation guide, Day 5](../../Fuel%20Management%20System%20md%20file/Fuel_Management_Implementation_Guide_v2.md)
