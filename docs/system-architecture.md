# System Architecture

**Status:** Day 1 implementation, checked against the repository on 2026-11-20.

This document describes what is present in the code and Compose configuration today. Dashed components are planned work, not active application flows.

## Architecture diagram

```mermaid
flowchart TB
  operator[Operator]

  subgraph compose[Docker Compose: fuel-platform / fuel-net]
    subgraph ui[frontend service · Nginx + React + TypeScript · container port 80]
      frontend[Static operator console\nRead-only dashboard]
      nginx[Nginx same-origin proxy\n/api/* and /actuator/*]
      frontend --- nginx
    end

    subgraph app[backend service · Spring Boot · container port 8080]
      web[web\n/api/dashboard\n/api/system-status]
      ingestion[ingestion\nrefresh scheduler · validation · cached snapshot]
      simulatorAdapter[simulator\ntyped REST adapter · error parsing · metrics]
      domain[domain\nWorldSnapshot · SimulationMode · DataQuality]
      observability[observability\nhealth registry · Micrometer metrics]
      planned[Planned: risk, forecasting, anomaly detection,\nrecommendation, approval, command and audit modules]
      web --> ingestion
      ingestion --> simulatorAdapter
      ingestion --> domain
      web --> domain
      simulatorAdapter --> observability
      ingestion --> observability
      planned -. not implemented .-> domain
    end

    simulator[simulator-api service\nOfficial BUP image 1.0.0\ncontainer port 8000\nsource of fuel-world truth]
    postgres[postgres service\nPostgreSQL 16\nDay 1: health probe only; no domain persistence]
    prometheus[prometheus service\nPrometheus v3.5.0\ncontainer port 9090]
    grafana[grafana service\nGrafana 12.0.2\ncontainer port 3000\nPrometheus datasource provisioned; no project dashboard JSON]

    simulatorAdapter -->|REST GET /v1/instance, /v1/regions, /v1/depots, /v1/stations, /v1/routes, /v1/supply-arrivals, /v1/events, /v1/allocations| simulator
    app -->|JDBC SELECT 1 health probe; no application records| postgres
    prometheus -->|Scrape every 10s: GET /actuator/prometheus| app
    grafana -->|Prometheus datasource: query http://prometheus:9090| prometheus
  end

  operator -->|HTTP localhost:5173| frontend
  nginx -->|REST proxy: GET /api/dashboard and /api/system-status| web
  nginx -. optional local metrics proxy .->|GET /actuator/*| observability
```

## Implemented data flow

1. Docker Compose starts six services: `simulator-api`, `postgres`, `backend`, `frontend`, `prometheus`, and `grafana`.
2. The backend refresh scheduler reads the simulator through the typed adapter. It reads the instance, regions, depots, stations, routes, supply arrivals, events, and allocation ledger. The adapter carries the simulator's stale-data header and normalizes its error response shapes.
3. The ingestion service validates the responses and publishes a snapshot atomically. A failed or stale read keeps the last valid view available with degraded quality metadata; it does not make the old data eligible for decisions.
4. The operator's browser loads the React application from the `frontend` service. Nginx forwards `/api/*` requests to the backend. The browser calls `/api/dashboard` and `/api/system-status`; it does not call the simulator.
5. The backend exposes Micrometer/Actuator metrics at `/actuator/prometheus`. Prometheus scrapes the backend every 10 seconds. Grafana has a provisioned Prometheus datasource, but this repository does not currently include a project-specific dashboard definition.

## Data ownership and persistence

The official simulator image, `asifmahmoud414/bup-fuel-supply-simulator:1.0.0`, owns operational fuel inventory, demand, simulation time, events, and shipment state. The backend's `WorldSnapshot` is a validated observation of the simulator, not a second source of operational truth. No browser-to-simulator connection is configured.

PostgreSQL 16 runs in Compose and the backend has datasource configuration and a scheduled `SELECT 1` reachability probe. However, the current backend has no domain entities, repositories, or application-table migrations. Therefore this diagram shows only the health probe to PostgreSQL; it does not claim snapshots, commands, or audit records are persisted. Durable application persistence is planned work.

## The two clocks

- **Simulation clock:** `tick` and `sim_time` come from the simulator. Compose defaults to `TICK_MINUTES=15`, so one tick represents 15 simulated minutes. This clock governs fuel consumption, shipment transit, and supply events.
- **Wall clock:** the computer's ordinary seconds. The backend uses it for request timeouts, polling, and the age of the last successful validated snapshot. Freshness is not inferred from tick movement: a paused simulator can remain on the same tick while its API is healthy.

## Planned modules

The dashed `planned` node marks work not implemented in the current backend: station/depot risk and forecasting, anomaly detection, constrained recommendations, operator approval, shipment command processing, audit persistence, and an SSE event listener. The simulator adapter contains a low-level allocation client method, but there is no application approval/command flow that invokes it, so the diagram does not show shipment dispatch as an implemented feature.

## Repository references

- Compose services, ports, and internal URLs: [`compose.yaml`](../compose.yaml)
- Backend packages: [`backend/src/main/java/com/fuelplatform/`](../backend/src/main/java/com/fuelplatform/)
- Frontend polling and API calls: [`frontend/src/useDashboard.ts`](../frontend/src/useDashboard.ts)
- Nginx API proxy: [`frontend/nginx.conf`](../frontend/nginx.conf)
- Prometheus scrape target: [`infra/prometheus/prometheus.yml`](../infra/prometheus/prometheus.yml)
- Grafana datasource and dashboard provider: [`infra/grafana/provisioning/`](../infra/grafana/provisioning/)
- Day 1 module status: [`docs/current-state.md`](current-state.md)
- Architecture decisions: [`docs/adr/`](adr/)
