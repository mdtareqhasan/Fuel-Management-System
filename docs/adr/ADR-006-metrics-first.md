# ADR 006 — Metrics First, Prometheus from Day One

**Status:** Accepted · **Date:** 2026-11-20

## Context

Observability is **10% of the rubric**, and every later feature needs a measurement: the forecast needs an error metric, the approval path needs an outcome counter, the resilience work needs a recovery time.

The natural order is "build the feature, add observability at the end". That order fails here for a specific reason: **at the end there is no time left to add metrics, so the features ship unmeasured** — and then the day-5 comparison has no numbers to compare.

The brief also asks for a status panel showing p95 latency and error rate (section 16), which means the application needs those figures at runtime, not just in a Prometheus scrape.

## Decision

**The Prometheus endpoint and the status panel ship on day one.** Each metric is added in the *same change* as the feature it measures, never in a later cleanup pass.

The metric set is short and fixed. Unbounded label values (station ids, correlation ids, allocation ids) are **never** used as metric labels — they would create an unbounded number of time series and are kept in logs instead.

| Metric | Type | Question it answers |
|---|---|---|
| `http_server_requests` | Actuator, with percentiles | Is the application becoming slow or failing? |
| `fuel_simulator_call_seconds` | Timer, tag `outcome` | Is the dependency healthy on real data paths? |
| `fuel_data_age_seconds` | Gauge | How old is the view the operator sees? |
| `fuel_stale_responses_total` | Counter | How often did the simulator serve stale data? |
| `fuel_rejected_payloads_total` | Counter | Which data quality problems occurred? |
| `fuel_sim_mode` | Gauge | PAUSED or RUNNING right now? |
| `fuel_sim_ticks_per_second` | Gauge | How fast is the clock really going? |
| `fuel_forecast_fallback_total` | Counter | Is the fallback method active? |
| `fuel_anomaly_flags_total` | Counter, tag `rule` | How active is detection? |
| `fuel_approval_total` | Counter, tag `outcome` | Did revalidation approve, reduce, change or block? |
| `fuel_commands_unknown` | Gauge | Is any write outcome still uncertain? |

The **status panel** reads from the same registry and reports: backend, database, simulator reachability, simulation mode, engine mode, refresh age, recent p95 latency and recent error rate. One backend instance means a Micrometer timer with published percentiles gives p95 directly — label it *recent*, because the window decays.

The error rate is server errors ÷ requests over the same recent window. Grafana computes the same figure from histogram buckets. **If the two differ, the difference is explained** (different window, different client overhead) rather than one being hidden.

## Alternatives rejected

- **Observability as a final phase.** No time on day 5, and the policy comparison would have no measured error rate to compare. This is the failure this ADR exists to prevent.
- **Full metric catalog.** A large catalog is unreadable, and half of it would never be looked at. Sixteen well-chosen metrics that answer a specific question beat a hundred that are never queried.
- **Grafana-only.** Grafana is P1 and needs Prometheus behind it. The status panel is the P0 requirement — it must work from the application's own registry without a separate stack.
- **Unbounded labels.** `fuel_station_id="station-mirpur"` looks useful but creates a series per station forever. Station ids go in logs; the metric keeps a fixed small label set.
- **Actuator exposed publicly.** Management endpoints are restricted to the local monitoring network.

## Consequences

- **Positive:** every feature from day 1 onward arrives with its own measurement. Day 5's comparison and load report have real numbers.
- **Positive:** the status panel is a working artefact on day 1, not a day-4 scramble.
- **Negative:** a little more setup on day 1. Accepted — this is the P0 item the implementation guide explicitly protects from being cut.
- **Negative:** `micrometer-registry-prometheus` adds a dependency. It is the standard Spring Boot approach and needs no extra server-side code.

## Verification

Day 1 acceptance: `curl http://localhost:8080/actuator/prometheus` returns metrics from the running backend, including at least one custom counter for simulator calls. Day 4 adds the Grafana panels and the status panel screenshot showing p95 and error rate rising during a fault and recovering afterwards.
