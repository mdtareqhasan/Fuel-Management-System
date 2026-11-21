import {
  formatLitres,
  formatNumber,
} from './format'
import type {
  AllocationProposal,
  AnomalyFlag,
  DepotRisk,
  RiskLevel,
  RiskResponse,
  StationRisk,
} from './types'

/**
 * The intelligence panel: forecasts, risk, anomalies and shipment proposals.
 *
 * Three rules hold everywhere in this panel:
 *
 * 1. **Unknown is not zero.** A null stockout tick means no stockout is
 *    projected. The panel never renders a missing number as 0.
 * 2. **Every number carries its basis.** The forecast says which method made it
 *    and how confident it is, because a profile fallback and a learned forecast
 *    do not deserve the same trust.
 * 3. **Every proposal carries its reasoning.** The quantity alone would hide
 *    the constraint that decided it, and the operator has to be able to check it.
 */

export function RiskPanel({
  risk,
  error,
  fuel,
}: {
  risk: RiskResponse | null
  error: string | null
  fuel: string
}) {
  if (!risk) {
    return (
      <section className="section">
        <h2>Intelligence</h2>
        <p className="empty">
          {error ?? 'The risk view is not available yet.'}
        </p>
      </section>
    )
  }

  const stationRisks = risk.station_risks.filter((row) =>
    fuel === 'ALL' ? true : row.fuel === fuel,
  )
  const depotRisks = risk.depot_risks.filter((row) =>
    fuel === 'ALL' ? true : row.fuel === fuel,
  )
  const flags = risk.anomaly_flags.filter((flag) =>
    fuel === 'ALL' ? true : flag.fuel === fuel,
  )
  const proposals = risk.proposals.filter((proposal) =>
    fuel === 'ALL' ? true : proposal.fuel === fuel,
  )
  const unmet = risk.unmet_needs.filter((need) =>
    fuel === 'ALL' ? true : need.fuel === fuel,
  )

  return (
    <>
      <RiskSummary risk={risk} stationRisks={stationRisks} flags={flags} />

      <AnomalySection flags={flags} />

      <StationRiskSection
        rows={stationRisks}
        currentTick={risk.tick}
        tickMinutes={risk.tick_minutes}
      />

      <DepotRiskSection rows={depotRisks} />

      <ProposalSection proposals={proposals} unmet={unmet} />
    </>
  )
}

// -------------------------------------------------------------------- summary

function RiskSummary({
  risk,
  stationRisks,
  flags,
}: {
  risk: RiskResponse
  stationRisks: StationRisk[]
  flags: AnomalyFlag[]
}) {
  const critical = stationRisks.filter((row) => row.level === 'CRITICAL').length
  const warning = stationRisks.filter((row) => row.level === 'WARNING').length
  const unexplained = flags.filter((flag) => flag.cause === 'UNEXPLAINED').length
  const fallbacks = Object.values(risk.forecasts).filter(
    (f) => f.method === 'PROFILE_ESTIMATE',
  ).length

  return (
    <section className="section">
      <h2>
        Intelligence <span className="count">{stationRisks.length}</span>
      </h2>

      <div className="summary-row">
        <SummaryTile
          label="Critical"
          value={String(critical)}
          tone={critical > 0 ? 'bad' : 'good'}
          hint="stations projected to run dry within the horizon"
        />
        <SummaryTile
          label="Warning"
          value={String(warning)}
          tone={warning > 0 ? 'warn' : 'good'}
          hint="stations at risk but not yet critical"
        />
        <SummaryTile
          label="Anomaly flags"
          value={String(flags.length)}
          tone={flags.length > 0 ? 'warn' : 'good'}
          hint={`${unexplained} without a known cause`}
        />
        <SummaryTile
          label="Profile fallback"
          value={String(fallbacks)}
          tone={fallbacks > 0 ? 'warn' : 'good'}
          hint="forecasts using the documented estimate, not history"
        />
        <SummaryTile
          label="Horizon"
          value={`${risk.horizon_hours} h`}
          tone="neutral"
          hint={`projection horizon at ${risk.tick_minutes} min per tick`}
        />
        <SummaryTile
          label="Detector"
          value={risk.detector_status}
          tone={risk.detector_status === 'AVAILABLE' ? 'good' : 'warn'}
          hint={risk.policy_version ? `policy ${risk.policy_version}` : undefined}
        />
      </div>

      {risk.detector_notes.length > 0 && (
        <ul className="notes">
          {risk.detector_notes.map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      )}
    </section>
  )
}

function SummaryTile({
  label,
  value,
  tone,
  hint,
}: {
  label: string
  value: string
  tone: 'good' | 'warn' | 'bad' | 'neutral'
  hint?: string
}) {
  return (
    <div className={`tile tile-${tone}`} title={hint}>
      <span className="tile-label">{label}</span>
      <span className="tile-value">{value}</span>
      {hint && <span className="tile-hint">{hint}</span>}
    </div>
  )
}

// -------------------------------------------------------------------- anomaly

function AnomalySection({ flags }: { flags: AnomalyFlag[] }) {
  return (
    <section className="section">
      <h2>
        Demand anomalies <span className="count">{flags.length}</span>
      </h2>
      {flags.length === 0 ? (
        <p className="empty">
          No station is off its documented demand profile.
        </p>
      ) : (
        <div className="table-scroll">
        <table className="risk-table">
          <thead>
            <tr>
              <th scope="col">Scope</th>
              <th scope="col">Station</th>
              <th scope="col">Fuel</th>
              <th scope="col">Ratio</th>
              <th scope="col">Ticks</th>
              <th scope="col">Cause</th>
              <th scope="col">Detected</th>
              <th scope="col">Reason</th>
            </tr>
          </thead>
          <tbody>
            {flags.map((flag) => (
              <tr
                key={`${flag.station_id}-${flag.fuel}-${flag.detected_at_tick}`}
                className={flag.cause === 'UNEXPLAINED' ? 'row-flagged' : undefined}
              >
                <td>
                  <span className={`status ${flag.severity === 'REGIONAL' ? 'status-warn' : 'status-bad'}`}>
                    {flag.severity}
                  </span>
                </td>
                <td>{flag.station_id}</td>
                <td>{flag.fuel}</td>
                <td>{formatNumber(flag.ratio)}×</td>
                <td>{flag.consecutive_ticks}</td>
                <td>
                  <span className={`status ${flag.cause === 'UNEXPLAINED' ? 'status-warn' : 'status-good'}`}>
                    {flag.cause}
                  </span>
                </td>
                <td>tick {formatNumber(flag.detected_at_tick)}</td>
                <td className="cell-reason">{flag.reason}</td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      )}
    </section>
  )
}

// --------------------------------------------------------------- station risk

function StationRiskSection({
  rows,
  currentTick,
  tickMinutes,
}: {
  rows: StationRisk[]
  currentTick: number
  tickMinutes: number
}) {
  return (
    <section className="section">
      <h2>
        Station risk <span className="count">{rows.length}</span>
      </h2>
      {rows.length === 0 ? (
        <p className="empty">No station matches the current fuel filter.</p>
      ) : (
        <div className="table-scroll">
        <table className="risk-table">
          <thead>
            <tr>
              <th scope="col">Level</th>
              <th scope="col">Station</th>
              <th scope="col">Fuel</th>
              <th scope="col">Stock</th>
              <th scope="col">Runs dry</th>
              <th scope="col">In</th>
              <th scope="col">Inbound</th>
              <th scope="col">Forecast</th>
              <th scope="col">Why</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={`${row.station_id}-${row.fuel}`}>
                <td>
                  <span className={`status ${riskLevelClass(row.level)}`}>{row.level}</span>
                </td>
                <td>{row.station_id}</td>
                <td>{row.fuel}</td>
                <td>{formatLitres(row.current_inventory_liters)}</td>
                <td>
                  {row.central_stockout_tick === null ? (
                    <span className="muted">no stockout projected</span>
                  ) : (
                    <>
                      tick {formatNumber(row.central_stockout_tick)}
                      <span className="cell-sub">
                        {formatNumber(row.central_stockout_hours)} h away
                      </span>
                    </>
                  )}
                </td>
                <td>
                  {row.central_stockout_tick === null
                    ? '—'
                    : formatMinutesUntil(row.central_stockout_tick, currentTick, tickMinutes)}
                </td>
                <td>
                  {row.inbound_arrivals.length === 0 ? (
                    <span className="muted">none</span>
                  ) : (
                    row.inbound_arrivals.map((shipment) => (
                      <div key={shipment.allocation_id} className="cell-sub">
                        {formatLitres(shipment.quantity_liters)} · tick{' '}
                        {formatNumber(shipment.expected_arrival_tick)}
                        {shipment.arrival_is_estimate && ' (est.)'}
                      </div>
                    ))
                  )}
                </td>
                <td>{forecastLabel(row)}</td>
                <td className="cell-reason">
                  {row.reasons.length > 0 ? row.reasons.join(' ') : '—'}
                  {row.blocking_constraints.length > 0 && (
                    <div className="cell-sub cell-block">
                      blocked: {row.blocking_constraints.join('; ')}
                    </div>
                  )}
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

/**
 * The forecast basis for one row.
 *
 * The method and the confidence are shown together with the rate, because a
 * rate without its basis is not actionable: a profile fallback rate and a
 * learned EWMA rate look identical on the page otherwise.
 */
function forecastLabel(row: StationRisk) {
  const forecast = row.forecast
  if (!forecast) return <span className="muted">no forecast</span>
  const rate =
    forecast.central_liters_per_hour === null
      ? 'Unknown'
      : `${formatNumber(forecast.central_liters_per_hour)} L/h`
  return (
    <>
      {rate}
      <span className="cell-sub">
        {forecast.method} · {forecast.confidence} · n={forecast.sample_count}
        {forecast.method === 'PROFILE_ESTIMATE' ? ' · profile estimate' : ''}
      </span>
    </>
  )
}

// ----------------------------------------------------------------- depot risk

function DepotRiskSection({ rows }: { rows: DepotRisk[] }) {
  return (
    <section className="section">
      <h2>
        Depot risk <span className="count">{rows.length}</span>
      </h2>
      {rows.length === 0 ? (
        <p className="empty">No depot matches the current fuel filter.</p>
      ) : (
        <div className="table-scroll">
        <table className="risk-table">
          <thead>
            <tr>
              <th scope="col">Level</th>
              <th scope="col">Depot</th>
              <th scope="col">Fuel</th>
              <th scope="col">Stock</th>
              <th scope="col">Shortfall</th>
              <th scope="col">Replenish</th>
              <th scope="col">Demand rate</th>
              <th scope="col">Why</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={`${row.depot_id}-${row.fuel}`}>
                <td>
                  <span className={`status ${riskLevelClass(row.level)}`}>{row.level}</span>
                </td>
                <td>{row.depot_id}</td>
                <td>{row.fuel}</td>
                <td>{formatLitres(row.current_inventory_liters)}</td>
                <td>
                  {row.shortfall_tick === null ? (
                    <span className="muted">none projected</span>
                  ) : (
                    <>
                      tick {formatNumber(row.shortfall_tick)}
                      <span className="cell-sub">{formatNumber(row.shortfall_hours)} h away</span>
                    </>
                  )}
                </td>
                <td>{formatLitres(row.required_replenishment_liters)}</td>
                <td>
                  {row.demand_rate_liters_per_hour === null
                    ? 'Unknown'
                    : `${formatNumber(row.demand_rate_liters_per_hour)} L/h`}
                </td>
                <td className="cell-reason">{row.reasons.join(' ')}</td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      )}
    </section>
  )
}

// ------------------------------------------------------------------ proposals

function ProposalSection({
  proposals,
  unmet,
}: {
  proposals: AllocationProposal[]
  unmet: RiskResponse['unmet_needs']
}) {
  return (
    <section className="section">
      <h2>
        Recommended shipments <span className="count">{proposals.length}</span>
      </h2>
      <p className="section-note">
        These are proposals. Nothing is dispatched until an operator approves it on
        the server.
      </p>

      {proposals.length === 0 ? (
        <p className="empty">No shipment is recommended right now.</p>
      ) : (
        <div className="grid">
          {proposals.map((proposal) => (
            <ProposalCard key={proposal.line_id} proposal={proposal} />
          ))}
        </div>
      )}

      {unmet.length > 0 && (
        <>
          <h3 className="subheading">
            Needs that could not be met <span className="count">{unmet.length}</span>
          </h3>
          <div className="table-scroll">
          <table className="risk-table">
            <thead>
              <tr>
                <th scope="col">Station</th>
                <th scope="col">Fuel</th>
                <th scope="col">Need</th>
                <th scope="col">Met</th>
                <th scope="col">Reason</th>
              </tr>
            </thead>
            <tbody>
              {unmet.map((need) => (
                <tr key={`${need.station_id}-${need.fuel}`}>
                  <td>{need.station_id}</td>
                  <td>{need.fuel}</td>
                  <td>{formatLitres(need.need_liters)}</td>
                  <td>{formatLitres(need.met_liters)}</td>
                  <td>
                    <span className="status status-bad">{need.reason_code}</span>
                    <div className="cell-sub">{need.reason}</div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        </>
      )}
    </section>
  )
}

function ProposalCard({ proposal }: { proposal: AllocationProposal }) {
  return (
    <article className="card card-proposal">
      <div className="card-head">
        <div>
          <h3>
            {proposal.station_id} <span className="card-fuel">{proposal.fuel}</span>
          </h3>
          <p className="card-sub">
            {proposal.depot_id} → {proposal.route_id}
          </p>
        </div>
        <span className="status status-good">PROPOSED</span>
      </div>

      <dl className="params">
        <div>
          <dt>Quantity</dt>
          <dd>{formatLitres(proposal.quantity_liters)}</dd>
        </div>
        <div>
          <dt>Need</dt>
          <dd>{formatLitres(proposal.need_liters)}</dd>
        </div>
        <div>
          <dt>Arrives</dt>
          <dd>
            tick {formatNumber(proposal.estimated_arrival_tick)}
            <span className="cell-sub">
              {formatNumber(proposal.estimated_arrival_hours)} h away
            </span>
          </dd>
        </div>
        <div>
          <dt>Cover</dt>
          <dd>
            {formatNumber(proposal.cover_before_hours)} →{' '}
            {formatNumber(proposal.cover_after_hours)} h
          </dd>
        </div>
      </dl>

      {proposal.reasons.length > 0 && (
        <>
          <h4 className="card-subheading">Why</h4>
          <ul className="reasons">
            {proposal.reasons.map((reason) => (
              <li key={reason}>{reason}</li>
            ))}
          </ul>
        </>
      )}

      {proposal.constraints.length > 0 && (
        <>
          <h4 className="card-subheading">Constraints applied</h4>
          <ul className="reasons">
            {proposal.constraints.map((constraint) => (
              <li key={constraint}>{constraint}</li>
            ))}
          </ul>
        </>
      )}

      {proposal.alternatives.length > 0 && (
        <>
          <h4 className="card-subheading">Alternatives</h4>
          <ul className="reasons">
            {proposal.alternatives.map((alternative) => (
              <li key={`${alternative.reason_code}-${alternative.route_id ?? 'none'}`}>
                {alternative.route_id ? (
                  <>
                    <strong>{alternative.route_id}</strong> ·{' '}
                    {formatLitres(alternative.quantity_liters)} ·{' '}
                    <span className="muted">[{alternative.reason_code}]</span>
                  </>
                ) : (
                  <span className="muted">[{alternative.reason_code}]</span>
                )}
                <div className="cell-sub">{alternative.reason}</div>
              </li>
            ))}
          </ul>
        </>
      )}
    </article>
  )
}

// --------------------------------------------------------------------- helpers

/**
 * The time from the current tick to a future tick, in minutes.
 *
 * The tick length comes from the response, not from a constant, because the
 * simulator's tick length is configuration and a hardcoded 15 would quietly
 * be wrong on a run that uses a different one.
 */
function formatMinutesUntil(
  targetTick: number,
  currentTick: number,
  tickMinutes: number,
): string {
  const ticks = targetTick - currentTick
  const minutes = ticks * tickMinutes
  if (minutes < 60) return `${formatNumber(minutes)} min`
  return `${formatNumber(minutes / 60)} h`
}

/** Picks a colour class for a risk level. */
function riskLevelClass(level: RiskLevel): string {
  switch (level) {
    case 'CRITICAL':
      return 'status-bad'
    case 'WARNING':
      return 'status-warn'
    case 'WATCH':
      return 'status-warn'
    case 'SAFE':
      return 'status-good'
    default:
      return 'status-unknown'
  }
}
