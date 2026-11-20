/**
 * Wire types mirroring the backend's dashboard response.
 *
 * The backend returns `unknown` for any value it does not actually know. The UI
 * must never turn an unknown into a zero, so the numeric fields are nullable
 * and the render helpers below decide how to show them.
 */

export type FuelType = 'DIESEL' | 'PETROL' | 'OCTANE'

export const FUEL_TYPES: FuelType[] = ['DIESEL', 'PETROL', 'OCTANE']

export type SimulationMode = 'PAUSED' | 'RUNNING' | 'UNKNOWN'

export interface SnapshotMetadata {
  snapshot_id: string | null
  run_id: string | null
  tick_start: number | null
  tick_end: number | null
  sim_time: string | null
  observed_at: string | null
  quality: 'FRESH' | 'STALE' | 'INVALID' | 'UNAVAILABLE'
  decision_eligible: boolean
  warnings: string[]
}

export interface Region {
  id: string
  name: string
  demand_factor: number | null
}

export interface Depot {
  id: string
  name: string
  region_id: string
  status: string
  dispatch_capacity_per_tick: number | null
  capacity: Record<string, number>
  inventory: Record<string, number>
}

export interface Station {
  id: string
  name: string
  region_id: string
  status: string
  demand_profile: string
  demand_multiplier: number | null
  capacity: Record<string, number>
  inventory: Record<string, number>
}

export interface Route {
  id: string
  source_depot_id: string
  destination_station_id: string
  transit_ticks: number | null
  max_shipment: number | null
  status: string
}

export interface SupplyArrival {
  id: string
  depot_id: string
  fuel_type: string
  quantity: number | null
  planned_tick: number | null
  actual_tick: number | null
  status: string
}

export interface DomainEvent {
  id: number
  type: string
  start_tick: number | null
  end_tick: number | null
  status: string
  parameters: Record<string, unknown>
}

export interface Allocation {
  id: number
  idempotency_key: string
  source_depot_id: string
  destination_station_id: string
  route_id: string
  fuel_type: string
  quantity: number | null
  created_tick: number | null
  departure_tick: number | null
  expected_arrival_tick: number | null
  actual_arrival_tick: number | null
  status: string
  failure_reason: string | null
}

export interface DashboardResponse {
  metadata: SnapshotMetadata
  simulation_mode: SimulationMode
  tick_minutes: number
  regions: Region[]
  depots: Depot[]
  stations: Station[]
  routes: Route[]
  supply_arrivals: SupplyArrival[]
  events: DomainEvent[]
  allocations: Allocation[]
  data_age_seconds: number
}

export interface SystemStatus {
  backend: string
  database: string
  simulator: string
  simulation_mode: string
  engine_mode: string
  data_quality: string
  decision_eligible: boolean | null
  refresh_age_seconds: number | null
  p95_latency_ms: number | null
  error_rate_percent: number | null
  max_age_seconds: number
  tick: number | null
  sim_time: string | null
}
