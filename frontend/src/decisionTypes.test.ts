import { describe, expect, it } from 'vitest'
import {
  commandStatusLabel,
  isPartial,
  isUnknown,
  proposalStatusLabel,
  type CommandStatus,
  type ProposalStatus,
  type Shipment,
} from './decisionTypes'

/**
 * Tests for the shipment ledger's vocabulary.
 *
 * The distinction these tests protect is the one the Day 3 brief calls out:
 * the **application's command status** and the **simulator's shipment status**
 * are different facts and must never be merged. A command in UNKNOWN while the
 * simulator reports IN_TRANSIT is a real state, and it means the shipment
 * exists but the application did not hear back.
 */

function shipment(overrides: Partial<Shipment> = {}): Shipment {
  return {
    command_id: 'cmd-1',
    proposal_id: 'prop-1',
    proposal_line_id: 'line-1',
    idempotency_key: 'proposal-1-line-1',
    command_status: 'SENT',
    simulator_allocation_id: 7,
    simulator_status: 'IN_TRANSIT',
    attempts: 1,
    last_error: null,
    quantity: 5000,
    station_id: 'station-mirpur',
    fuel_type: 'DIESEL',
    created_at: { value: '2026-11-22T10:00:00Z' },
    ...overrides,
  }
}

describe('shipment states', () => {
  it('treats a sent shipment that has not arrived as partial, not failed', () => {
    const row = shipment({ command_status: 'SENT', simulator_status: 'IN_TRANSIT' })
    expect(isPartial(row)).toBe(true)
    expect(isUnknown(row)).toBe(false)
  })

  it('treats a delivered shipment as complete', () => {
    const row = shipment({ command_status: 'SENT', simulator_status: 'DELIVERED' })
    expect(isPartial(row)).toBe(false)
  })

  it('treats an unknown command as unknown regardless of the simulator status', () => {
    const row = shipment({ command_status: 'UNKNOWN', simulator_status: 'IN_TRANSIT' })
    expect(isUnknown(row)).toBe(true)
    // The shipment exists, but the application does not know that yet.
    expect(isPartial(row)).toBe(false)
  })

  it('treats a command with no simulator status as neither partial nor unknown', () => {
    const row = shipment({ command_status: 'APPROVED', simulator_status: null })
    expect(isPartial(row)).toBe(false)
    expect(isUnknown(row)).toBe(false)
  })
})

describe('status wording', () => {
  it('says what UNKNOWN means rather than only naming the state', () => {
    const label = commandStatusLabel('UNKNOWN')
    expect(label).toContain('may exist')
  })

  it('distinguishes the reduced approval from the plain one', () => {
    expect(proposalStatusLabel('APPROVED')).not.toBe(
      proposalStatusLabel('APPROVED_WITH_REDUCTION'),
    )
    expect(proposalStatusLabel('APPROVED_WITH_REDUCTION')).toContain('reduction')
  })

  it('says a changed proposal needs a re-review', () => {
    expect(proposalStatusLabel('CHANGED')).toContain('Re-review')
  })

  it('covers every command status with a distinct-enough label', () => {
    const statuses: CommandStatus[] = [
      'APPROVED',
      'SENDING',
      'SENT',
      'UNKNOWN',
      'REJECTED',
      'FAILED',
      'CANCELLED',
    ]
    const labels = statuses.map(commandStatusLabel)
    expect(new Set(labels).size).toBe(statuses.length)
    labels.forEach((label) => expect(label.length).toBeGreaterThan(0))
  })

  it('covers every proposal status with a label', () => {
    const statuses: ProposalStatus[] = [
      'PENDING_REVIEW',
      'APPROVED',
      'APPROVED_WITH_REDUCTION',
      'PARTIALLY_CHANGED',
      'CHANGED',
      'BLOCKED',
      'EXPIRED',
      'CANCELLED',
    ]
    statuses.forEach((status) => {
      expect(proposalStatusLabel(status).length).toBeGreaterThan(0)
    })
  })
})
