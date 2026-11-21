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

// ------------------------------------------------------------------- risk view

/**
 * The risk view's wire types.
 *
 * These mirror the backend's /api/risk response. As with the dashboard types, a
 * value the backend does not know is `null` rather than zero: a null stockout
 * tick means "no stockout is projected", and a zero means "it runs dry right
 * now". Those are different facts and the UI must not blur them.
 */

export type RiskLevel = 'CRITICAL' | 'WARNING' | 'WATCH' | 'SAFE' | 'UNKNOWN'

export type ForecastMethod = 'EWMA' | 'NAIVE' | 'PROFILE_ESTIMATE'

export type DataConfidence = 'GOOD' | 'LOW_DATA' | 'STALE' | 'UNKNOWN'

export interface DemandForecast {
  station_id: string
  fuel: string
  method: ForecastMethod
  confidence: DataConfidence
  sample_count: number
  horizon_hours: number
  central_liters_per_hour: number | null
  stress_liters_per_hour: number | null
  recent_absolute_error: number | null
  baseline_absolute_error: number | null
  known: boolean
  reasons: string[]
  warnings: string[]
}

export interface InboundShipment {
  allocation_id: number
  route_id: string | null
  quantity_liters: number | null
  expected_arrival_tick: number | null
  arrival_is_estimate: boolean
  status: string | null
}

export interface StationRisk {
  station_id: string
  fuel: string
  level: RiskLevel
  current_inventory_liters: number | null
  forecast: DemandForecast | null
  central_stockout_tick: number | null
  central_stockout_hours: number | null
  stress_stockout_tick: number | null
  stress_stockout_hours: number | null
  projected_unmet_liters: number | null
  inbound_arrivals: InboundShipment[]
  blocking_constraints: string[]
  reasons: string[]
}

export interface DepotRisk {
  depot_id: string
  fuel: string
  level: RiskLevel
  current_inventory_liters: number | null
  required_replenishment_liters: number | null
  shortfall_tick: number | null
  shortfall_hours: number | null
  demand_rate_liters_per_hour: number | null
  supply_changes: unknown[]
  reasons: string[]
}

export interface AnomalyFlag {
  station_id: string
  fuel: string
  severity: 'STATION' | 'REGIONAL'
  cause: 'KNOWN_EVENT' | 'UNEXPLAINED'
  ratio: number
  consecutive_ticks: number
  detected_at_tick: number
  reason: string
}

export interface AllocationAlternative {
  route_id: string | null
  depot_id: string | null
  quantity_liters: number | null
  expected_arrival_tick: number | null
  cover_after_hours: number | null
  reason_code:
    | 'LATER_ARRIVAL'
    | 'DISPATCH_RESERVED_FOR_MORE_URGENT'
    | 'LOWER_SOURCE_COVER'
    | 'NO_ALTERNATIVE_ROUTE'
  reason: string
}

export interface AllocationProposal {
  line_id: string
  station_id: string
  fuel: string
  depot_id: string
  route_id: string
  quantity_liters: number | null
  need_liters: number | null
  estimated_arrival_tick: number | null
  estimated_arrival_hours: number | null
  cover_before_hours: number | null
  cover_after_hours: number | null
  reasons: string[]
  constraints: string[]
  alternatives: AllocationAlternative[]
}

export interface UnmetNeed {
  station_id: string
  fuel: string
  need_liters: number | null
  met_liters: number | null
  reason_code: string
  reason: string
}

export interface RiskResponse {
  metadata: SnapshotMetadata
  tick: number
  sim_time: string | null
  tick_minutes: number
  horizon_hours: number
  quality: { label: string; eligible: boolean; reasons: string[] } | string
  detector_status: string
  detector_notes: string[]
  forecasts: Record<string, DemandForecast>
  station_risks: StationRisk[]
  depot_risks: DepotRisk[]
  anomaly_flags: AnomalyFlag[]
  proposals: AllocationProposal[]
  unmet_needs: UnmetNeed[]
  policy_version: string | null
}
