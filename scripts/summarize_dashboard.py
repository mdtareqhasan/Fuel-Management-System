"""Prints a compact summary of a dashboard JSON payload.

Used during day-1 verification to confirm that the values on screen come from
the simulator and are not hardcoded in the application.
"""

import json
import sys


def main(path: str) -> int:
    with open(path, encoding="utf-8") as handle:
        payload = json.load(handle)

    meta = payload.get("metadata", {})
    print(f"quality      : {meta.get('quality')}")
    print(f"eligible     : {meta.get('decisionEligible')}")
    print(f"mode         : {payload.get('simulationMode')}")
    print(f"tickMinutes  : {payload.get('tickMinutes')}")
    print(f"tick range   : {meta.get('tickStart')} -> {meta.get('tickEnd')}")
    print(f"simTime      : {meta.get('simTime')}")
    print(f"ageSeconds   : {payload.get('dataAgeSeconds')}")
    print()

    print(f"regions  : {len(payload.get('regions', []))}")
    for region in payload.get("regions", []):
        print(f"  {region['id']:20} {region['name']:16} factor={region.get('demandFactor')}")
    print()

    print(f"depots   : {len(payload.get('depots', []))}")
    for depot in payload.get("depots", []):
        print(f"  {depot['id']:16} {depot['status']:6} cap/tick={depot.get('dispatchCapacityPerTick')} inv={depot.get('inventory')}")
    print()

    print(f"stations : {len(payload.get('stations', []))}")
    for station in payload.get("stations", []):
        print(f"  {station['id']:18} {station['status']:6} profile={station.get('demandProfile')} mult={station.get('demandMultiplier')}")
        print(f"      inv={station.get('inventory')}")
        print(f"      cap={station.get('capacity')}")
    print()

    print(f"routes   : {len(payload.get('routes', []))}")
    for route in payload.get("routes", []):
        print(f"  {route['id']:24} {route.get('sourceDepotId')} -> {route.get('destinationStationId')} transit={route.get('transitTicks')} max={route.get('maxShipment')} {route.get('status')}")
    print()

    print(f"events     : {len(payload.get('events', []))}")
    for event in payload.get("events", []):
        print(f"  {event.get('type')} {event.get('status')} ticks {event.get('startTick')}->{event.get('endTick')} params={event.get('parameters')}")
    print()

    print(f"allocations: {len(payload.get('allocations', []))}")
    for allocation in payload.get("allocations", []):
        print(f"  #{allocation.get('id')} {allocation.get('sourceDepotId')}->{allocation.get('destinationStationId')} {allocation.get('fuelType')} {allocation.get('quantity')} {allocation.get('status')}")
    print()

    print(f"supplyArrivals: {len(payload.get('supplyArrivals', []))}")
    for arrival in payload.get("supplyArrivals", []):
        print(f"  {arrival.get('id')} {arrival.get('depotId')} {arrival.get('fuelType')} {arrival.get('quantity')} tick={arrival.get('plannedTick')} {arrival.get('status')}")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "dashboard_probe.json"))
