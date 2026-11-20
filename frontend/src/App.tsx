import { useDashboard } from './useDashboard'
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
      <Header dashboard={dashboard} status={status} error={error} />
      <StationsSection dashboard={dashboard} />
      <DepotsSection dashboard={dashboard} />
      <EventsSection dashboard={dashboard} />
      <AllocationsSection dashboard={dashboard} />
      <Footer dashboard={dashboard} />
    </div>
  )
}

// --------------------------------------------------------------------- header

function Header({
  dashboard,
  status,
  error,
}: {
  dashboard: DashboardResponse
  status: SystemStatus | null
  error: string | null
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

        <div className="badges">
          {/* The mode badge is the single most important piece of context on the
              screen. In RUNNING the world moves while you read, which changes what
              an approval means. */}
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

// ------------------------------------------------------------------- stations

function StationsSection({ dashboard }: { dashboard: DashboardResponse }) {
  return (
    <section className="section">
      <h2>
        Stations <span className="count">{dashboard.stations.length}</span>
      </h2>
      <div className="grid">
        {dashboard.stations.map((station) => (
          <StationCard key={station.id} station={station} />
        ))}
      </div>
    </section>
  )
}

function StationCard({ station }: { station: Station }) {
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
          {FUEL_TYPES.map((fuel) => {
            const inventory = station.inventory?.[fuel] ?? null
            const capacity = station.capacity?.[fuel] ?? null
            const fraction = fillFraction(inventory, capacity)
            return (
              <tr key={fuel}>
                <th scope="row">{fuel}</th>
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

function DepotsSection({ dashboard }: { dashboard: DashboardResponse }) {
  return (
    <section className="section">
      <h2>
        Depots <span className="count">{dashboard.depots.length}</span>
      </h2>
      <div className="grid">
        {dashboard.depots.map((depot) => (
          <DepotCard key={depot.id} depot={depot} />
        ))}
      </div>
    </section>
  )
}

function DepotCard({ depot }: { depot: Depot }) {
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
          {FUEL_TYPES.map((fuel) => (
            <tr key={fuel}>
              <th scope="row">{fuel}</th>
              <td>{formatLitres(depot.inventory?.[fuel] ?? null)}</td>
              <td>{formatLitres(depot.capacity?.[fuel] ?? null)}</td>
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

function AllocationsSection({ dashboard }: { dashboard: DashboardResponse }) {
  const recent = dashboard.allocations.slice(-8).reverse()

  return (
    <section className="section">
      <h2>
        Recent shipments <span className="count">{dashboard.allocations.length}</span>
      </h2>
      {recent.length === 0 ? (
        <p className="empty">No shipments yet in this run.</p>
      ) : (
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
