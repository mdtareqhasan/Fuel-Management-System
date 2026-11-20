import { describe, expect, it } from 'vitest'
import {
  ALL,
  DEFAULT_FILTERS,
  filterAllocations,
  filterDepots,
  filterStations,
  indexStations,
  isDefault,
  parseFilters,
  regionOptions,
  toSearchParams,
  visibleFuels,
  type Filters,
} from './filters'
import type { Allocation, Depot, Region, Station } from './types'

/**
 * Tests for the operator console's filter rules.
 *
 * These are the rules that decide what an operator can and cannot see, so they
 * are pinned with tests rather than left to inspection.
 */

const regions: Region[] = [
  { id: 'region-dhaka', name: 'Dhaka Division', demand_factor: 1.0 },
  { id: 'region-chattogram', name: 'Chattogram Division', demand_factor: 1.08 },
]

const stations: Station[] = [
  {
    id: 'station-mirpur',
    name: 'Mirpur',
    region_id: 'region-dhaka',
    status: 'OPEN',
    demand_profile: 'urban_high',
    demand_multiplier: 1,
    capacity: { DIESEL: 15000, PETROL: 14000, OCTANE: 9000 },
    inventory: { DIESEL: 9000, PETROL: 9000, OCTANE: 5000 },
  },
  {
    id: 'station-coxsbazar',
    name: "Cox's Bazar",
    region_id: 'region-chattogram',
    status: 'OPEN',
    demand_profile: 'regional',
    demand_multiplier: 1,
    capacity: { DIESEL: 12000, PETROL: 12000, OCTANE: 7000 },
    inventory: { DIESEL: 7500, PETROL: 7500, OCTANE: 4200 },
  },
]

const depots: Depot[] = [
  {
    id: 'depot-gazipur',
    name: 'Gazipur',
    region_id: 'region-dhaka',
    status: 'OPEN',
    dispatch_capacity_per_tick: 12000,
    capacity: { DIESEL: 90000, PETROL: 70000, OCTANE: 45000 },
    inventory: { DIESEL: 60000, PETROL: 45000, OCTANE: 26000 },
  },
  {
    id: 'depot-patiya',
    name: 'Patiya',
    region_id: 'region-chattogram',
    status: 'OPEN',
    dispatch_capacity_per_tick: 11000,
    capacity: { DIESEL: 85000, PETROL: 65000, OCTANE: 40000 },
    inventory: { DIESEL: 55000, PETROL: 42000, OCTANE: 24000 },
  },
]

function allocation(overrides: Partial<Allocation>): Allocation {
  return {
    id: 1,
    idempotency_key: 'key-1',
    source_depot_id: 'depot-gazipur',
    destination_station_id: 'station-mirpur',
    route_id: 'route-gazipur-mirpur',
    fuel_type: 'DIESEL',
    quantity: 3000,
    created_tick: 2,
    departure_tick: null,
    expected_arrival_tick: null,
    actual_arrival_tick: null,
    status: 'PENDING',
    failure_reason: null,
    ...overrides,
  }
}

function filters(fuel: Filters['fuel'], region: Filters['region']): Filters {
  return { fuel, region }
}

describe('regionOptions', () => {
  it('offers the unfiltered option plus every region', () => {
    expect(regionOptions(regions)).toEqual([ALL, 'region-dhaka', 'region-chattogram'])
  })

  it('still offers the unfiltered option when the world has no regions', () => {
    expect(regionOptions([])).toEqual([ALL])
  })
})

describe('filterStations', () => {
  it('returns every station when nothing is filtered', () => {
    expect(filterStations(stations, DEFAULT_FILTERS)).toHaveLength(2)
  })

  it('keeps only the stations in the selected region', () => {
    const result = filterStations(stations, filters(ALL, 'region-chattogram'))
    expect(result.map((station) => station.id)).toEqual(['station-coxsbazar'])
  })

  it('returns an empty list rather than null when nothing matches', () => {
    expect(filterStations(stations, filters(ALL, 'region-unknown'))).toEqual([])
  })

  it('does not change the station data it was given', () => {
    const before = JSON.stringify(stations)
    filterStations(stations, filters(ALL, 'region-dhaka'))
    expect(JSON.stringify(stations)).toBe(before)
  })
})

describe('filterDepots', () => {
  it('keeps only the depots in the selected region', () => {
    const result = filterDepots(depots, filters(ALL, 'region-dhaka'))
    expect(result.map((depot) => depot.id)).toEqual(['depot-gazipur'])
  })
})

describe('visibleFuels', () => {
  it('lists every fuel when no fuel is selected', () => {
    expect(visibleFuels(ALL)).toEqual(['DIESEL', 'PETROL', 'OCTANE'])
  })

  it('lists only the selected fuel', () => {
    expect(visibleFuels('PETROL')).toEqual(['PETROL'])
  })
})

describe('filterAllocations', () => {
  const byId = indexStations(stations)

  it('keeps every allocation when nothing is filtered', () => {
    const all = [allocation({}), allocation({ id: 2, fuel_type: 'PETROL' })]
    expect(filterAllocations(all, DEFAULT_FILTERS, byId)).toHaveLength(2)
  })

  it('filters by fuel', () => {
    const all = [allocation({}), allocation({ id: 2, fuel_type: 'PETROL' })]
    const result = filterAllocations(all, filters('PETROL', ALL), byId)
    expect(result.map((entry) => entry.id)).toEqual([2])
  })

  it('uses the destination station region, not the source depot region', () => {
    // The depot is in dhaka, the destination is in chattogram. Filtering by
    // chattogram must keep it; filtering by dhaka must drop it.
    const crossing = allocation({
      source_depot_id: 'depot-gazipur',
      destination_station_id: 'station-coxsbazar',
    })
    expect(filterAllocations([crossing], filters(ALL, 'region-chattogram'), byId)).toHaveLength(1)
    expect(filterAllocations([crossing], filters(ALL, 'region-dhaka'), byId)).toHaveLength(0)
  })

  it('keeps a shipment whose destination station is unknown', () => {
    // Dropping it would hide a shipment the operator may still care about.
    const orphan = allocation({ destination_station_id: 'station-deleted' })
    expect(filterAllocations([orphan], filters(ALL, 'region-dhaka'), byId)).toHaveLength(1)
  })
})

describe('isDefault', () => {
  it('is true only when no filter is active', () => {
    expect(isDefault(DEFAULT_FILTERS)).toBe(true)
    expect(isDefault(filters('DIESEL', ALL))).toBe(false)
    expect(isDefault(filters(ALL, 'region-dhaka'))).toBe(false)
  })
})

describe('parseFilters', () => {
  const validRegions = ['region-dhaka', 'region-chattogram']

  it('reads both filters out of the query string', () => {
    const parsed = parseFilters('?fuel=PETROL&region=region-dhaka', validRegions)
    expect(parsed).toEqual({ fuel: 'PETROL', region: 'region-dhaka' })
  })

  it('falls back to the unfiltered view for an empty query string', () => {
    expect(parseFilters('', validRegions)).toEqual(DEFAULT_FILTERS)
  })

  it('ignores a fuel the world does not have', () => {
    // A link must never filter on a fuel that does not exist and silently
    // show an empty console.
    expect(parseFilters('?fuel=KEROSENE', validRegions).fuel).toBe(ALL)
  })

  it('ignores a region the world does not have', () => {
    expect(parseFilters('?region=region-sylhet', validRegions).region).toBe(ALL)
  })

  it('keeps the fuel when only the region is unknown', () => {
    const parsed = parseFilters('?fuel=OCTANE&region=region-sylhet', validRegions)
    expect(parsed).toEqual({ fuel: 'OCTANE', region: ALL })
  })
})

describe('toSearchParams', () => {
  it('produces an empty string when nothing is filtered', () => {
    expect(toSearchParams(DEFAULT_FILTERS)).toBe('')
  })

  it('writes only the active filters', () => {
    expect(toSearchParams(filters('DIESEL', ALL))).toBe('?fuel=DIESEL')
    expect(toSearchParams(filters(ALL, 'region-dhaka'))).toBe('?region=region-dhaka')
  })

  it('round-trips through parseFilters', () => {
    const original = filters('PETROL', 'region-chattogram')
    const parsed = parseFilters(toSearchParams(original), ['region-dhaka', 'region-chattogram'])
    expect(parsed).toEqual(original)
  })
})
