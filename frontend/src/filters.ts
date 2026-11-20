/**
 * Filter logic for the operator console.
 *
 * Kept in its own module, free of React, so the rules can be read and reasoned
 * about on their own. Three rules drive the design:
 *
 * 1. **A filter never invents data.** Filtering a station out of the view does
 *    not change the world's state; it only changes what the operator is looking
 *    at. The badge row still shows the full tick and freshness context.
 * 2. **Unknown stays unknown.** If a station has no entry for a fuel, filtering
 *    to that fuel shows "Unknown" — it never shows 0 litres.
 * 3. **Empty results are a real state.** "No station in this region carries
 *    that fuel" is information. The UI says so instead of rendering an empty
 *    grid that looks broken.
 */

import { FUEL_TYPES, type Allocation, type Depot, type FuelType, type Region, type Station } from './types'

/** Sentinel meaning "do not filter on this dimension". */
export const ALL = 'ALL' as const

export type FuelFilter = FuelType | typeof ALL
export type RegionFilter = string | typeof ALL

export interface Filters {
  fuel: FuelFilter
  region: RegionFilter
}

export const DEFAULT_FILTERS: Filters = { fuel: ALL, region: ALL }

/** The fuel rows to render inside a station or depot card. */
export function visibleFuels(fuel: FuelFilter): FuelType[] {
  return fuel === ALL ? FUEL_TYPES : [fuel]
}

/** The region options offered in the filter bar. */
export function regionOptions(regions: Region[]): RegionFilter[] {
  return [ALL, ...regions.map((region) => region.id)]
}

function matchesRegion(regionId: string | null | undefined, region: RegionFilter): boolean {
  return region === ALL || regionId === region
}

/** Stations in the selected region. */
export function filterStations(stations: Station[], filters: Filters): Station[] {
  return stations.filter((station) => matchesRegion(station.region_id, filters.region))
}

/** Depots in the selected region. */
export function filterDepots(depots: Depot[], filters: Filters): Depot[] {
  return depots.filter((depot) => matchesRegion(depot.region_id, filters.region))
}

/**
 * Shipments matching the fuel filter, and the region filter when the
 * destination station is known.
 *
 * The region of a shipment is the region of its **destination station**, not
 * its source depot — a shipment is about the station that needs the fuel. When
 * the destination station is unknown the shipment is kept and treated as
 * "region unknown", because dropping it would hide a shipment the operator may
 * still care about.
 */
export function filterAllocations(
  allocations: Allocation[],
  filters: Filters,
  stationsById: Map<string, Station>,
): Allocation[] {
  return allocations.filter((allocation) => {
    if (filters.fuel !== ALL && allocation.fuel_type !== filters.fuel) {
      return false
    }
    if (filters.region === ALL) {
      return true
    }
    const destination = stationsById.get(allocation.destination_station_id)
    if (!destination) {
      return true
    }
    return matchesRegion(destination.region_id, filters.region)
  })
}

/** Builds the station lookup used by {@link filterAllocations}. */
export function indexStations(stations: Station[]): Map<string, Station> {
  return new Map(stations.map((station) => [station.id, station]))
}

/** True when no filter is active, so the UI can hide the reset control. */
export function isDefault(filters: Filters): boolean {
  return filters.fuel === ALL && filters.region === ALL
}

/**
 * Reads the filters out of a query string.
 *
 * The operator view is deep-linkable: a filtered view can be bookmarked or
 * shared, and the same link shows the same thing to the next person. An
 * unknown or malformed value falls back to the unfiltered view rather than
 * throwing — a bad link must still render a working console.
 */
export function parseFilters(search: string, validRegions: string[]): Filters {
  const params = new URLSearchParams(search)
  const fuel = params.get('fuel')
  const region = params.get('region')

  const parsedFuel: FuelFilter =
    fuel !== null && (FUEL_TYPES as string[]).includes(fuel) ? (fuel as FuelType) : ALL

  const parsedRegion: RegionFilter =
    region !== null && (region === ALL || validRegions.includes(region)) ? region : ALL

  return { fuel: parsedFuel, region: parsedRegion }
}

/**
 * The query string for the current filters.
 *
 * Only the active filters appear, so the default view stays a clean URL.
 */
export function toSearchParams(filters: Filters): string {
  const params = new URLSearchParams()
  if (filters.fuel !== ALL) {
    params.set('fuel', filters.fuel)
  }
  if (filters.region !== ALL) {
    params.set('region', filters.region)
  }
  const query = params.toString()
  return query ? `?${query}` : ''
}
