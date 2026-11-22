/**
 * Wire types for the decision surface — the proposals, the approval and the
 * shipment ledger.
 *
 * The separation that matters here is between the **application's own command
 * status** and the **simulator's shipment status**. They are different facts:
 * the command says what this application did, the simulator status says what
 * the world did with it. A command stuck in UNKNOWN while the simulator says
 * IN_TRANSIT is a real and important state, and collapsing the two into one
 * column would hide it.
 */

export type ProposalStatus =
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'APPROVED_WITH_REDUCTION'
  | 'PARTIALLY_CHANGED'
  | 'CHANGED'
  | 'BLOCKED'
  | 'EXPIRED'
  | 'CANCELLED'

export type ProposalLineStatus =
  | 'PENDING'
  | 'APPROVED_AS_REVIEWED'
  | 'APPROVED_REDUCED'
  | 'CHANGED'
  | 'BLOCKED'

export type CommandStatus =
  | 'APPROVED'
  | 'SENDING'
  | 'SENT'
  | 'UNKNOWN'
  | 'REJECTED'
  | 'FAILED'
  | 'CANCELLED'

/** The application-side codes an approval refusal can carry. */
export type ApprovalErrorCode =
  | 'STALE_OR_INVALID_SNAPSHOT'
  | 'PROPOSAL_CHANGED'
  | 'PROPOSAL_EXPIRED'
  | 'ALREADY_APPROVED'
  | 'INVALID_REQUEST'
  | 'QUANTITY_EXCEEDS_REVIEWED'
  | 'PROPOSAL_NOT_FOUND'
  | 'PERSISTENCE_FAILED'

export interface ProposalAlternative {
  route_id: string | null
  source_depot_id: string | null
  quantity_liters: number | null
  expected_arrival_tick: number | null
  cover_after_hours: number | null
  reason_code: string
  reason: string
}

export interface ProposalLine {
  line_id: string
  station_id: string
  fuel_type: string
  source_depot_id: string
  route_id: string
  reviewed_quantity: number
  final_quantity: number | null
  status: ProposalLineStatus
  reasons: string[]
  constraints: string[]
  alternatives: ProposalAlternative[]
}

export interface Proposal {
  id: string
  status: ProposalStatus
  mode: string
  proposed_at_tick: number
  reviewed_quantity: number
  final_quantity: number
  created_at: { value: string }
  lines: ProposalLine[]
}

export interface ApprovalLineOutcome {
  line_id: string
  status: ProposalLineStatus
  reviewed_quantity: number
  final_quantity: number
  reason: string
  command_id: string | null
}

export interface ApprovalResponse {
  proposal_id: string
  status: ProposalStatus
  lines: ApprovalLineOutcome[]
  shipped_quantity: number
  reviewed_quantity: number
  correlation_id: string
}

export interface ApprovalErrorResponse {
  code: ApprovalErrorCode
  message: string
}

export interface Shipment {
  command_id: string
  proposal_id: string
  proposal_line_id: string
  idempotency_key: string
  /** What this application did. */
  command_status: CommandStatus
  /** The simulator's allocation id, once known. */
  simulator_allocation_id: number | null
  /** What the simulator did, once known. */
  simulator_status: string | null
  attempts: number
  last_error: string | null
  quantity: number | null
  station_id: string | null
  fuel_type: string | null
  created_at: { value: string }
}

/**
 * Whether the shipment ledger shows a partial completion.
 *
 * A shipment is partially complete when the command was confirmed but the
 * simulator has not yet reported an arrival. That is the normal state between
 * departure and arrival, and it must be visible as such — not as a failure.
 */
export function isPartial(shipment: Shipment): boolean {
  return (
    shipment.command_status === 'SENT' &&
    shipment.simulator_status !== null &&
    shipment.simulator_status !== 'DELIVERED' &&
    shipment.simulator_status !== 'ARRIVED' &&
    shipment.simulator_status !== 'COMPLETED'
  )
}

/** Whether the outcome is not known. */
export function isUnknown(shipment: Shipment): boolean {
  return shipment.command_status === 'UNKNOWN'
}

/**
 * The operator-facing wording for a command status.
 *
 * "UNKNOWN" on its own would be alarming and would not say what to do. The
 * point of an unknown outcome is that the shipment may exist, so the wording
 * says that.
 */
export function commandStatusLabel(status: CommandStatus): string {
  switch (status) {
    case 'APPROVED':
      return 'Approved, waiting to send'
    case 'SENDING':
      return 'Sending'
    case 'SENT':
      return 'Sent to the simulator'
    case 'UNKNOWN':
      return 'Outcome unknown — the shipment may exist'
    case 'REJECTED':
      return 'Refused by the simulator'
    case 'FAILED':
      return 'Failed'
    case 'CANCELLED':
      return 'Cancelled'
  }
}

/** The operator-facing wording for a proposal status. */
export function proposalStatusLabel(status: ProposalStatus): string {
  switch (status) {
    case 'PENDING_REVIEW':
      return 'Waiting for review'
    case 'APPROVED':
      return 'Approved as reviewed'
    case 'APPROVED_WITH_REDUCTION':
      return 'Approved with a reduction'
    case 'PARTIALLY_CHANGED':
      return 'Partially approved — some lines changed'
    case 'CHANGED':
      return 'Re-review required'
    case 'BLOCKED':
      return 'Approval blocked'
    case 'EXPIRED':
      return 'Expired'
    case 'CANCELLED':
      return 'Cancelled'
  }
}
