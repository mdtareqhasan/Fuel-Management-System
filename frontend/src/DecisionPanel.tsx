import { useMemo, useState } from 'react'
import { formatLitres } from './format'
import {
  commandStatusLabel,
  isPartial,
  isUnknown,
  proposalStatusLabel,
  type ApprovalErrorCode,
  type Proposal,
  type ProposalLine,
  type Shipment,
} from './decisionTypes'
import type { ApproveOutcome } from './useDecisions'

/**
 * The decision panel: the review list, the approval dialog and the shipment
 * ledger.
 *
 * Three rules hold everywhere in this panel:
 *
 * 1. **The operator sees an upper bound.** The quantity in the review dialog is
 *    the most that will be sent. It may end up lower — never higher — so the
 *    input's `max` is the reviewed quantity and the label says so.
 * 2. **The approve button is a convenience, not the control.** The server
 *    re-checks freshness, eligibility and the quantity on every call
 *    (ADR 003). A disabled button with a specific reason is a courtesy to the
 *    operator, not a guarantee.
 * 3. **The command status and the simulator status are separate columns.**
 *    They are different facts, and collapsing them would hide exactly the
 *    state that matters — an unknown command whose shipment exists.
 */

export function DecisionPanel({
  proposals,
  shipments,
  approving,
  approve,
  reconcile,
  error,
}: {
  proposals: Proposal[]
  shipments: Shipment[]
  approving: string | null
  approve: (
    proposalId: string,
    actor: string,
    quantities: Record<string, number>,
  ) => Promise<ApproveOutcome>
  reconcile: () => Promise<void>
  error: string | null
}) {
  const [actor, setActor] = useState('operator')
  const [reviewing, setReviewing] = useState<Proposal | null>(null)
  const [outcome, setOutcome] = useState<ApproveOutcome | null>(null)

  const pending = useMemo(
    () => proposals.filter((proposal) => proposal.status === 'PENDING_REVIEW'),
    [proposals],
  )
  const settled = useMemo(
    () => proposals.filter((proposal) => proposal.status !== 'PENDING_REVIEW'),
    [proposals],
  )

  const openReview = (proposal: Proposal) => {
    setReviewing(proposal)
    setOutcome(null)
  }

  const closeReview = () => {
    setReviewing(null)
    setOutcome(null)
  }

  const submit = async (quantities: Record<string, number>) => {
    if (!reviewing) return
    const result = await approve(reviewing.id, actor, quantities)
    setOutcome(result)
    if (result.ok) {
      setReviewing(null)
    }
  }

  return (
    <>
      <section className="section" id="decisions">
        <h2>Decisions</h2>
        {error && <p className="empty error-text">{error}</p>}

        <div className="decision-toolbar">
          <label className="actor-field">
            Operator
            <input
              type="text"
              value={actor}
              onChange={(event) => setActor(event.target.value)}
              placeholder="your name"
            />
          </label>
          <p className="decision-note">
            Nothing reaches the simulator without an approval recorded here.
          </p>
        </div>

        {pending.length === 0 && settled.length === 0 && (
          <p className="empty">No proposals yet. Generate one from the intelligence panel.</p>
        )}

        {pending.length > 0 && (
          <>
            <h3>Waiting for review ({pending.length})</h3>
            <div className="table-wrap">
              <table className="decision-table">
                <thead>
                  <tr>
                    <th>Proposal</th>
                    <th>Mode</th>
                    <th>Tick</th>
                    <th>Lines</th>
                    <th>Reviewed</th>
                    <th>Status</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {pending.map((proposal) => (
                    <tr key={proposal.id}>
                      <td className="mono small">{proposal.id.slice(0, 8)}</td>
                      <td>{proposal.mode}</td>
                      <td>{proposal.proposed_at_tick}</td>
                      <td>{proposal.lines.length}</td>
                      <td>{formatLitres(proposal.reviewed_quantity)}</td>
                      <td>
                        <span className="pill pill-warn">
                          {proposalStatusLabel(proposal.status)}
                        </span>
                      </td>
                      <td>
                        <button
                          type="button"
                          className="primary"
                          onClick={() => openReview(proposal)}
                        >
                          Review
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}

        {settled.length > 0 && (
          <>
            <h3>Decided ({settled.length})</h3>
            <div className="table-wrap">
              <table className="decision-table">
                <thead>
                  <tr>
                    <th>Proposal</th>
                    <th>Mode</th>
                    <th>Tick</th>
                    <th>Reviewed</th>
                    <th>Shipped</th>
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {settled.map((proposal) => (
                    <tr key={proposal.id}>
                      <td className="mono small">{proposal.id.slice(0, 8)}</td>
                      <td>{proposal.mode}</td>
                      <td>{proposal.proposed_at_tick}</td>
                      <td>{formatLitres(proposal.reviewed_quantity)}</td>
                      <td>{formatLitres(proposal.final_quantity)}</td>
                      <td>
                        <span
                          className={`pill ${
                            proposal.status === 'APPROVED' ? 'pill-ok' : 'pill-warn'
                          }`}
                        >
                          {proposalStatusLabel(proposal.status)}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </section>

      <ShipmentLedger shipments={shipments} reconcile={reconcile} />

      {reviewing && (
        <ReviewDialog
          proposal={reviewing}
          actor={actor}
          busy={approving === reviewing.id}
          outcome={outcome}
          onCancel={closeReview}
          onSubmit={submit}
        />
      )}
    </>
  )
}

/**
 * The review dialog.
 *
 * The quantity input is capped at the reviewed quantity, and the reason a line
 * cannot be approved is stated in words. A disabled button with no explanation
 * is the thing this is designed to avoid.
 */
function ReviewDialog({
  proposal,
  actor,
  busy,
  outcome,
  onCancel,
  onSubmit,
}: {
  proposal: Proposal
  actor: string
  busy: boolean
  outcome: ApproveOutcome | null
  onCancel: () => void
  onSubmit: (quantities: Record<string, number>) => Promise<void>
}) {
  const [quantities, setQuantities] = useState<Record<string, number>>(() => {
    const initial: Record<string, number> = {}
    for (const line of proposal.lines) {
      initial[line.line_id] = line.reviewed_quantity
    }
    return initial
  })

  const total = Object.values(quantities).reduce((sum, value) => sum + value, 0)

  const change = (line: ProposalLine, raw: string) => {
    const parsed = Number(raw)
    if (!Number.isFinite(parsed) || parsed < 0) {
      setQuantities((previous) => ({ ...previous, [line.line_id]: 0 }))
      return
    }
    // The input cannot exceed the reviewed quantity. The server enforces the
    // same rule; this is the operator-facing half of it.
    const capped = Math.min(parsed, line.reviewed_quantity)
    setQuantities((previous) => ({ ...previous, [line.line_id]: capped }))
  }

  return (
    <div className="dialog-backdrop" role="presentation">
      <div className="dialog" role="dialog" aria-label="Review proposal">
        <header className="dialog-head">
          <h3>Review proposal {proposal.id.slice(0, 8)}</h3>
          <button type="button" className="ghost" onClick={onCancel} aria-label="Close">
            ×
          </button>
        </header>

        <p className="dialog-note">
          The quantities below are the <strong>upper bounds</strong>. The backend re-checks
          the world at approval time and may ship less — never more. Approved by{' '}
          <strong>{actor || 'an unnamed operator'}</strong>.
        </p>

        <div className="table-wrap">
          <table className="decision-table">
            <thead>
              <tr>
                <th>Station</th>
                <th>Fuel</th>
                <th>Route</th>
                <th>Reviewed</th>
                <th>Approve up to</th>
                <th>Why</th>
              </tr>
            </thead>
            <tbody>
              {proposal.lines.map((line) => (
                <tr key={line.line_id}>
                  <td>{line.station_id}</td>
                  <td>{line.fuel_type}</td>
                  <td className="mono small">{line.route_id}</td>
                  <td>{formatLitres(line.reviewed_quantity)}</td>
                  <td>
                    <input
                      type="number"
                      className="quantity-input"
                      value={quantities[line.line_id] ?? 0}
                      min={0}
                      max={line.reviewed_quantity}
                      step={1}
                      onChange={(event) => change(line, event.target.value)}
                      aria-label={`Quantity for ${line.station_id}`}
                    />
                  </td>
                  <td className="small reasons">
                    {line.reasons.map((reason) => (
                      <div key={reason}>{reason}</div>
                    ))}
                    {line.alternatives.length > 0 && (
                      <div className="alternative-note">
                        {line.alternatives.length} alternative
                        {line.alternatives.length === 1 ? '' : 's'} considered:
                        {line.alternatives
                          .map((alternative) => `${alternative.route_id ?? '—'} (${alternative.reason_code})`)
                          .join(', ')}
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <footer className="dialog-foot">
          <span className="total">
            Maximum to ship: <strong>{formatLitres(total)}</strong>
          </span>
          <div className="dialog-actions">
            <button type="button" className="ghost" onClick={onCancel} disabled={busy}>
              Cancel
            </button>
            <button
              type="button"
              className="primary"
              onClick={() => void onSubmit(quantities)}
              disabled={busy || total <= 0}
            >
              {busy ? 'Approving…' : 'Approve'}
            </button>
          </div>
        </footer>

        {outcome && !outcome.ok && (
          <p className="dialog-error">
            <strong>{errorTitle(outcome.errorCode)}</strong> {outcome.message}
          </p>
        )}
      </div>
    </div>
  )
}

/**
 * The shipment ledger.
 *
 * The application's command status and the simulator's shipment status are in
 * <strong>separate columns</strong>. They answer different questions and the
 * difference is the whole point of the panel.
 */
function ShipmentLedger({
  shipments,
  reconcile,
}: {
  shipments: Shipment[]
  reconcile: () => Promise<void>
}) {
  const unknownCount = shipments.filter(isUnknown).length

  return (
    <section className="section">
      <h2>Shipment ledger</h2>

      {shipments.length === 0 && (
        <p className="empty">No shipments yet. Approve a proposal to create one.</p>
      )}

      {unknownCount > 0 && (
        <div className="unknown-banner">
          <span>
            {unknownCount} shipment{unknownCount === 1 ? '' : 's'} with an unknown outcome.
            The shipment may exist — it is reconciled by its idempotency key, never by
            sending again.
          </span>
          <button type="button" onClick={() => void reconcile()}>
            Reconcile now
          </button>
        </div>
      )}

      {shipments.length > 0 && (
        <div className="table-wrap">
          <table className="decision-table">
            <thead>
              <tr>
                <th>Destination</th>
                <th>Fuel</th>
                <th>Quantity</th>
                <th>Application command</th>
                <th>Simulator shipment</th>
                <th>Allocation</th>
                <th>Attempts</th>
              </tr>
            </thead>
            <tbody>
              {shipments.map((shipment) => (
                <tr
                  key={shipment.command_id}
                  className={
                    isUnknown(shipment)
                      ? 'row-unknown'
                      : isPartial(shipment)
                        ? 'row-partial'
                        : undefined
                  }
                >
                  <td>{shipment.station_id ?? '—'}</td>
                  <td>{shipment.fuel_type ?? '—'}</td>
                  <td>{formatLitres(shipment.quantity)}</td>
                  <td>
                    <span
                      className={`pill ${
                        shipment.command_status === 'SENT'
                          ? 'pill-ok'
                          : shipment.command_status === 'UNKNOWN'
                            ? 'pill-warn'
                            : shipment.command_status === 'REJECTED' ||
                                shipment.command_status === 'FAILED'
                              ? 'pill-bad'
                              : 'pill-neutral'
                      }`}
                    >
                      {commandStatusLabel(shipment.command_status)}
                    </span>
                    {shipment.last_error && (
                      <div className="small error-text">{shipment.last_error}</div>
                    )}
                  </td>
                  <td>
                    {shipment.simulator_status ?? (
                      <span className="small muted">not reported yet</span>
                    )}
                    {isPartial(shipment) && (
                      <div className="small muted">in progress</div>
                    )}
                  </td>
                  <td className="mono small">
                    {shipment.simulator_allocation_id ?? '—'}
                  </td>
                  <td>{shipment.attempts}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

/** The title for an approval refusal. */
function errorTitle(code: ApprovalErrorCode | null): string {
  switch (code) {
    case 'STALE_OR_INVALID_SNAPSHOT':
      return 'Approval blocked.'
    case 'PROPOSAL_CHANGED':
      return 'Re-review required.'
    case 'PROPOSAL_EXPIRED':
      return 'The proposal expired.'
    case 'ALREADY_APPROVED':
      return 'Already approved.'
    case 'QUANTITY_EXCEEDS_REVIEWED':
      return 'Quantity too large.'
    case 'PROPOSAL_NOT_FOUND':
      return 'Proposal not found.'
    case 'INVALID_REQUEST':
      return 'Invalid request.'
    case 'PERSISTENCE_FAILED':
      return 'Could not record the decision.'
    default:
      return 'The approval failed.'
  }
}
