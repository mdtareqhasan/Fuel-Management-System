# Phase 00 · Day 1 — Bootstrap & Simulator Contract

**Target:** Thursday, 19 November 2026 · **Status:** Complete; progress evidence updated 20 November.

| Milestone | Focus areas | Result |
|:--|:--|:--|
| Bootstrap & simulator contract | Official simulator experiments, typed backend integration, validated read-only console, health/metrics, Compose and CI | Day 1 foundation implemented and documented |

## Completed

- Measured simulator tick speed and tested shipment creation, replay, cancellation, dispatch limits, demand ordering, stale responses, and error formats.
- Implemented the typed simulator adapter, snapshot validation, atomic publication, cached degraded view, dashboard API, and system-status API.
- Built the React read-only operator console and configured Prometheus scraping and Grafana's Prometheus datasource.
- Added Docker Compose and CI workflows; saved verification output under [`../evidence/day-01/`](../evidence/day-01/).

## Evidence and detailed update

- [Day 1 update](../day-01-update.md)
- [Day 1 talking points](../day-01-talking-points.md)
- [Contract notes](../contract-notes.md)
- [Current system architecture](../system-architecture.md)
- [Verification report](../evidence/day-01/verification-report.md)

## Known next work

Risk analysis, forecasts, anomaly detection, recommendations, persistent application records, approval, and shipment command processing are not part of the completed Day 1 slice.
