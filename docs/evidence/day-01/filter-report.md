# Day 01 filter verification report

Generated: 2026-11-21T08:00:20.180540+00:00

Every result below was produced by running the script, not asserted by hand.

**13 passed, 0 failed**

## 1. The filter options come from the snapshot

- **PASS** — frontend served
  - HTTP 200 from http://localhost:5173
- **PASS** — frontend bundle referenced
  - 1 script(s)
- **PASS** — the fuel names are in the bundle
  - ['DIESEL', 'OCTANE', 'PETROL']
- **PASS** — no region id is hardcoded in the bundle
  - the region list is read from the snapshot at runtime
- **PASS** — no station id is hardcoded in the bundle
  - the station list is read from the snapshot at runtime

## 2. The filter rules, computed against the live snapshot

- region `region-dhaka` holds 2 of 4 stations and 1 of 2 depots
- **PASS** — a region filter narrows the station list
  - 2 of 4
- **PASS** — a region filter narrows the depot list
  - 1 of 2
- **PASS** — a fuel filter does not change which stations match a region
  - every station in `region-dhaka` carries DIESEL

## 3. The deep link reaches the console

- **PASS** — filtered deep link is served
  - HTTP 200 for /?fuel=DIESEL&region=region-dhaka
- **PASS** — an unknown deep link is still served
  - HTTP 200 — a bad link renders a working console, not an error page

## 4. The backend view is untouched by any filter

- **PASS** — the API still returns every station
  - 4 stations
- **PASS** — the API still returns every depot
  - 2 depots
- **PASS** — the snapshot is still decision eligible
  - quality=FRESH
