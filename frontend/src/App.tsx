import { useEffect, useMemo, useState } from 'react'
import { useDashboard } from './useDashboard'
import { useRisk } from './useRisk'
import { RiskPanel } from './RiskPanel'
import {
  FUEL_TYPES,
  type DashboardResponse,
  type Depot,
  type Station,
  type SystemStatus,
} from './types'
import {
  fillFraction,
  formatAge,
  formatLitres,
  formatNumber,
  levelClass,
  statusClass,
} from './format'
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
import './styles.css'

/**
 * The operator console.
 *
 * Everything on this screen comes from the backend's validated snapshot. There
 * is no hardcoded inventory, no placeholder chart and no invented number: if
 * the backend does not know something, the screen says Unknown.
 */
export default function App() {
  const { dashboard, status, error, loading } = useDashboard()
  const { risk, error: riskError } = useRisk()
  const [filters, setFilters] = useState<Filters>(DEFAULT_FILTERS)
  const [theme, setTheme] = useState<'dark' | 'light'>(() => {
    try {
      const saved = window.localStorage.getItem('fuel-platform-theme')
      if (saved === 'dark' || saved === 'light') return saved
    } catch {
      // The system preference still works when browser storage is unavailable.
    }
    return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
  })

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    document.documentElement.style.colorScheme = theme
    try {
      window.localStorage.setItem('fuel-platform-theme', theme)
    } catch {
      // Theme remains active for this page even when it cannot be persisted.
    }
  }, [theme])

  // The filter is a view concern, not a data concern. It narrows what the
  // operator is looking at; it never changes the snapshot the backend holds.
  const stations = useMemo(
    () => (dashboard ? filterStations(dashboard.stations, filters) : []),
    [dashboard, filters],
  )
  const depots = useMemo(
    () => (dashboard ? filterDepots(dashboard.depots, filters) : []),
    [dashboard, filters],
  )
  const allocations = useMemo(
    () =>
      dashboard
        ? filterAllocations(dashboard.allocations, filters, indexStations(dashboard.stations))
        : [],
    [dashboard, filters],
  )

  // The filter lives in the URL, so a filtered view is deep-linkable and the
  // operator can share exactly what they are looking at. The region list comes
  // from the snapshot, so a link naming a region the world does not have falls
  // back to the unfiltered view instead of rendering nothing.
  //
  // The snapshot id changes on every refresh, so the parsed value is compared
  // with the current one and only replaced when it actually differs. Otherwise
  // every two-second refresh would build a new filters object and re-render
  // the whole console for nothing.
  useEffect(() => {
    if (!dashboard) return
    const validRegions = dashboard.regions.map((region) => region.id)
    const parsed = parseFilters(window.location.search, validRegions)
    setFilters((current) =>
      current.fuel === parsed.fuel && current.region === parsed.region ? current : parsed,
    )
  }, [dashboard?.metadata.snapshot_id])

  const applyFilters = (next: Filters) => {
    setFilters(next)
    const query = toSearchParams(next)
    window.history.replaceState(null, '', `${window.location.pathname}${query}`)
  }

  const toggleTheme = () => {
    setTheme((current) => current === 'dark' ? 'light' : 'dark')
  }

  if (loading && !dashboard) {
    return <div className="app app-empty">Loading the current world…</div>
  }

  if (!dashboard) {
    return (
      <div className="app app-empty">
        <h1>Fuel Supply Intelligence</h1>
        <p className="error-text">
          No snapshot yet. {error ?? 'The backend has not read the simulator successfully.'}
        </p>
      </div>
    )
  }

  return (
    <div className="app">
      <Header
        dashboard={dashboard}
        status={status}
        error={error}
        theme={theme}
        onToggleTheme={toggleTheme}
      />
      <FilterBar dashboard={dashboard} filters={filters} onChange={applyFilters} />
      <RiskPanel risk={risk} error={riskError} fuel={filters.fuel} />
      <StationsSection
        stations={stations}
        total={dashboard.stations.length}
        fuel={filters.fuel}
      />
      <DepotsSection depots={depots} total={dashboard.depots.length} fuel={filters.fuel} />
      <EventsSection dashboard={dashboard} />
      <AllocationsSection allocations={allocations} total={dashboard.allocations.length} />
      <Footer dashboard={dashboard} />
    </div>
  )
}

// --------------------------------------------------------------------- header

function Header({
  dashboard,
  status,
  error,
  theme,
  onToggleTheme,
}: {
  dashboard: DashboardResponse
  status: SystemStatus | null
  error: string | null
  theme: 'dark' | 'light'
  onToggleTheme: () => void
}) {
  const meta = dashboard.metadata
  const stale = meta.quality !== 'FRESH'
  const paused = dashboard.simulation_mode === 'PAUSED'

  return (
    <header className="header">
      <div className="header-top">
        <div>
          <h1>Fuel Supply Intelligence</h1>
          <p className="subtitle">
            Operator console &middot; snapshot <code>{meta.snapshot_id?.slice(0, 8) ?? 'none'}</code>
          </p>
        </div>

        <div className="header-actions">
          <button
            type="button"
            className="theme-toggle"
            onClick={onToggleTheme}
            aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
            title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
          >
            {theme === 'dark' ? (
              <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4" /><path d="M12 2v2m0 16v2M4.93 4.93l1.42 1.42m11.3 11.3 1.42 1.42M2 12h2m16 0h2M4.93 19.07l1.42-1.42m11.3-11.3 1.42-1.42" /></svg>
            ) : (
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20.9 13A8.5 8.5 0 0 1 11 3.1 8.5 8.5 0 1 0 20.9 13Z" /></svg>
            )}
            <span>{theme === 'dark' ? 'Light mode' : 'Dark mode'}</span>
          </button>
          <div className="badges">
            {/* The mode badge is the single most important piece of context on
                the screen. In RUNNING the world moves while you read. */}
            <span className={`badge ${paused ? 'badge-paused' : 'badge-running'}`}>
              {paused ? 'PAUSED' : dashboard.simulation_mode}
            </span>
            <span className={`badge ${stale ? 'badge-stale' : 'badge-fresh'}`}>
              {meta.quality}
            </span>
            <span className="badge badge-age" title="Wall-clock age of the current view">
              {formatAge(dashboard.data_age_seconds)}
            </span>
          </div>
        </div>
      </div>

      <div className="header-meta">
        <MetaItem label="Tick" value={formatNumber(meta.tick_end)} />
        <MetaItem label="Tick range" value={tickRange(meta.tick_start, meta.tick_end)} />
        <MetaItem label="Sim time" value={meta.sim_time ?? 'Unknown'} />
        <MetaItem label="Tick length" value={`${dashboard.tick_minutes} min`} />
        <MetaItem label="Backend" value={status?.backend ?? 'Unknown'} />
        <MetaItem label="Database" value={status?.database ?? 'Unknown'} />
        <MetaItem label="Simulator" value={status?.simulator ?? 'Unknown'} />
        <MetaItem label="p95" value={p95Label(status?.p95_latency_ms)} />
        <MetaItem label="Error rate" value={errorRateLabel(status?.error_rate_percent)} />
      </div>

      {meta.warnings.length > 0 && (
        <div className="banner banner-warn">
          <strong>This view may not back a decision.</strong>
          <ul>
            {meta.warnings.map((warning) => (
              <li key={warning}>{warning}</li>
            ))}
          </ul>
        </div>
      )}

      {error && (
        <div className="banner banner-warn">
          Last refresh failed: {error}. Showing the last known data.
        </div>
      )}
    </header>
  )
}

function tickRange(start: number | null, end: number | null): string {
  if (start === null || end === null) return 'Unknown'
  if (start === end) return formatNumber(end)
  return `${formatNumber(start)} → ${formatNumber(end)}`
}

function p95Label(value: number | null | undefined): string {
  if (value === null || value === undefined) return 'Unknown'
  return `${Math.round(value)} ms`
}

function errorRateLabel(value: number | null | undefined): string {
  if (value === null || value === undefined) return 'Unknown'
  return `${value.toFixed(1)}%`
}

function MetaItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="meta-item">
      <span className="meta-label">{label}</span>
      <span className="meta-value">{value}</span>
    </div>
  )
}

// ------------------------------------------------------------------- filters

/**
 * The filter bar.
 *
 * Filtering happens in the browser over the backend's validated view. The
 * snapshot itself is untouched: the operator narrows what they are looking at,
 * and the header keeps showing the tick, the freshness and the age of the data
 * underneath, so a filtered view is never mistaken for a different world.
 */
function FilterBar({
  dashboard,
  filters,
  onChange,
}: {
  dashboard: DashboardResponse
  filters: Filters
  onChange: (next: Filters) => void
}) {
  const regions = regionOptions(dashboard.regions)

  return (
    <section className="filter-bar" aria-label="Filter the operator view">
      <div className="filter-group">
        <span className="filter-label">Fuel</span>
        <div className="filter-pills">
          <FilterPill
            active={filters.fuel === ALL}
            label="All fuels"
            onClick={() => onChange({ ...filters, fuel: ALL })}
          />
          {FUEL_TYPES.map((fuel) => (
            <FilterPill
              key={fuel}
              active={filters.fuel === fuel}
              label={fuel}
              onClick={() => onChange({ ...filters, fuel })}
            />
          ))}
        </div>
      </div>

      <div className="filter-group">
        <span className="filter-label">Region</span>
        <div className="filter-pills">
          {regions.map((region) => (
            <FilterPill
              key={region}
              active={filters.region === region}
              label={region === ALL ? 'All regions' : regionName(dashboard, region)}
              onClick={() => onChange({ ...filters, region })}
            />
          ))}
        </div>
      </div>

      {!isDefault(filters) && (
        <button type="button" className="filter-reset" onClick={() => onChange(DEFAULT_FILTERS)}>
          Reset filters
        </button>
      )}
    </section>
  )
}

/** The human name of a region, falling back to its id. */
function regionName(dashboard: DashboardResponse, regionId: string): string {
  const region = dashboard.regions.find((candidate) => candidate.id === regionId)
  return region?.name ?? regionId
}

function FilterPill({
  active,
  label,
  onClick,
}: {
  active: boolean
  label: string
  onClick: () => void
}) {
  return (
    <button
      type="button"
      className={`filter-pill${active ? ' filter-pill-active' : ''}`}
      aria-pressed={active}
      onClick={onClick}
    >
      {label}
    </button>
  )
}

// ------------------------------------------------------------------- stations

function StationsSection({
  stations,
  total,
  fuel,
}: {
  stations: Station[]
  total: number
  fuel: Filters['fuel']
}) {
  return (
    <section className="section">
      <h2>
        Stations <span className="count">{stations.length}</span>
        {stations.length !== total && <span className="count-subtle">of {total}</span>}
      </h2>
      {stations.length === 0 ? (
        <p className="empty">No station matches the current filter.</p>
      ) : (
        <div className="grid">
          {stations.map((station) => (
            <StationCard key={station.id} station={station} fuel={fuel} />
          ))}
        </div>
      )}
    </section>
  )
}

function StationCard({ station, fuel }: { station: Station; fuel: Filters['fuel'] }) {
  return (
    <article className="card">
      <div className="card-head">
        <div>
          <h3>{station.name}</h3>
          <p className="card-sub">
            {station.region_id} &middot; profile {station.demand_profile}
            {station.demand_multiplier !== null &&
              station.demand_multiplier !== 1 && (
                <>
                  {' '}
                  &middot; demand ×{formatNumber(station.demand_multiplier)}
                </>
              )}
          </p>
        </div>
        <span className={`status ${statusClass(station.status)}`}>{station.status}</span>
      </div>

      <table className="fuel-table">
        <thead>
          <tr>
            <th scope="col">Fuel</th>
            <th scope="col">Inventory</th>
            <th scope="col">Capacity</th>
            <th scope="col">Level</th>
          </tr>
        </thead>
        <tbody>
          {visibleFuels(fuel).map((fuelName) => {
            const inventory = station.inventory?.[fuelName] ?? null
            const capacity = station.capacity?.[fuelName] ?? null
            const fraction = fillFraction(inventory, capacity)
            return (
              <tr key={fuelName}>
                <th scope="row">{fuelName}</th>
                <td>{formatLitres(inventory)}</td>
                <td>{formatLitres(capacity)}</td>
                <td>
                  <div className={`level ${levelClass(fraction)}`}>
                    <div className="level-fill" style={{ width: `${fractionPercent(fraction)}%` }} />
                    <span className="level-text">
                      {fraction === null ? 'Unknown' : `${Math.round(fraction * 100)}%`}
                    </span>
                  </div>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </article>
  )
}

/** Clamps a fraction into a width the browser can render. */
function fractionPercent(fraction: number | null): number {
  if (fraction === null || !Number.isFinite(fraction)) return 0
  return Math.max(0, Math.min(100, fraction * 100))
}

// --------------------------------------------------------------------- depots

function DepotsSection({
  depots,
  total,
  fuel,
}: {
  depots: Depot[]
  total: number
  fuel: Filters['fuel']
}) {
  return (
    <section className="section">
      <h2>
        Depots <span className="count">{depots.length}</span>
        {depots.length !== total && <span className="count-subtle">of {total}</span>}
      </h2>
      {depots.length === 0 ? (
        <p className="empty">No depot matches the current filter.</p>
      ) : (
        <div className="grid">
          {depots.map((depot) => (
            <DepotCard key={depot.id} depot={depot} fuel={fuel} />
          ))}
        </div>
      )}
    </section>
  )
}

function DepotCard({ depot, fuel }: { depot: Depot; fuel: Filters['fuel'] }) {
  return (
    <article className="card">
      <div className="card-head">
        <div>
          <h3>{depot.name}</h3>
          <p className="card-sub">
            {depot.region_id} &middot; dispatch cap{' '}
            {formatLitres(depot.dispatch_capacity_per_tick)} / tick
          </p>
        </div>
        <span className={`status ${statusClass(depot.status)}`}>{depot.status}</span>
      </div>

      <table className="fuel-table">
        <thead>
          <tr>
            <th scope="col">Fuel</th>
            <th scope="col">Inventory</th>
            <th scope="col">Capacity</th>
          </tr>
        </thead>
        <tbody>
          {visibleFuels(fuel).map((fuelName) => (
            <tr key={fuelName}>
              <th scope="row">{fuelName}</th>
              <td>{formatLitres(depot.inventory?.[fuelName] ?? null)}</td>
              <td>{formatLitres(depot.capacity?.[fuelName] ?? null)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <p className="card-note">
        Inventory is already net of pending and in-transit shipments. The simulator
        deducts at dispatch time.
      </p>
    </article>
  )
}

// --------------------------------------------------------------------- events

function EventsSection({ dashboard }: { dashboard: DashboardResponse }) {
  const active = dashboard.events.filter((event) => event.status === 'ACTIVE')
  if (active.length === 0) {
    return (
      <section className="section">
        <h2>
          Active events <span className="count">0</span>
        </h2>
        <p className="empty">No active crisis events in the current snapshot.</p>
      </section>
    )
  }

  return (
    <section className="section">
      <h2>
        Active events <span className="count">{active.length}</span>
      </h2>
      <div className="grid">
        {active.map((event) => (
          <article className="card card-event" key={event.id}>
            <h3>{event.type}</h3>
            <p className="card-sub">
              tick {formatNumber(event.start_tick)} → {formatNumber(event.end_tick)}
            </p>
            <dl className="params">
              {Object.entries(event.parameters ?? {}).map(([key, value]) => (
                <div key={key}>
                  <dt>{key}</dt>
                  <dd>{String(value)}</dd>
                </div>
              ))}
            </dl>
          </article>
        ))}
      </div>
    </section>
  )
}

// ---------------------------------------------------------------- allocations

function AllocationsSection({
  allocations,
  total,
}: {
  allocations: DashboardResponse['allocations']
  total: number
}) {
  const recent = allocations.slice(-8).reverse()

  return (
    <section className="section">
      <h2>
        Recent shipments <span className="count">{allocations.length}</span>
        {allocations.length !== total && <span className="count-subtle">of {total}</span>}
      </h2>
      {recent.length === 0 ? (
        <p className="empty">No shipments match the current filter.</p>
      ) : (
        <div className="table-scroll">
        <table className="alloc-table">
          <thead>
            <tr>
              <th scope="col">Id</th>
              <th scope="col">Route</th>
              <th scope="col">Fuel</th>
              <th scope="col">Quantity</th>
              <th scope="col">Status</th>
              <th scope="col">Arrives</th>
            </tr>
          </thead>
          <tbody>
            {recent.map((allocation) => (
              <tr key={allocation.id}>
                <td>{allocation.id}</td>
                <td>
                  {allocation.source_depot_id} → {allocation.destination_station_id}
                </td>
                <td>{allocation.fuel_type}</td>
                <td>{formatLitres(allocation.quantity)}</td>
                <td>
                  <span className={`status ${statusClass(allocation.status)}`}>
                    {allocation.status}
                  </span>
                </td>
                <td>
                  {allocation.actual_arrival_tick !== null
                    ? `tick ${formatNumber(allocation.actual_arrival_tick)}`
                    : `~tick ${formatNumber(allocation.expected_arrival_tick)}`}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      )}
    </section>
  )
}

// --------------------------------------------------------------------- footer

function Footer({ dashboard }: { dashboard: DashboardResponse }) {
  const meta = dashboard.metadata
  return (
    <footer className="footer">
      <span>
        Run <code>{meta.run_id?.slice(0, 8) ?? 'none'}</code>
      </span>
      <span>
        Observed {meta.observed_at ? new Date(meta.observed_at).toLocaleTimeString() : 'Unknown'}
      </span>
      <span>
        Decision eligible: {meta.decision_eligible ? 'yes' : 'no'}
      </span>
      <span className="footer-note">
        Read-only view. Allocations require an operator approval on the server.
      </span>
    </footer>
  )
}
