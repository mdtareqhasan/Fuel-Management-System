import { useCallback, useEffect, useRef, useState } from 'react'
import type {
  ApprovalErrorCode,
  ApprovalErrorResponse,
  ApprovalResponse,
  Proposal,
  Shipment,
} from './decisionTypes'

/**
 * Talks to the decision endpoints.
 *
 * The browser never talks to the simulator. It talks to the backend, which
 * holds the proposal, performs the revalidation and owns the write.
 *
 * The approve call is deliberately **not** wrapped in a poll-friendly loader.
 * An approval is a one-shot action with a one-shot answer, and the answer
 * matters: it says what was shipped, what was reduced and what was refused.
 */

/** How often the review list and the ledger refresh. */
const POLL_INTERVAL_MS = 4000

export interface DecisionState {
  proposals: Proposal[]
  shipments: Shipment[]
  error: string | null
}

export interface CreateProposalOutcome {
  ok: boolean
  message: string | null
}

export interface ApproveOutcome {
  ok: boolean
  result: ApprovalResponse | null
  errorCode: ApprovalErrorCode | null
  message: string | null
}

async function readJson<T>(response: Response): Promise<T> {
  const body = (await response.json()) as T
  return body
}

export function useDecisions() {
  const [state, setState] = useState<DecisionState>({
    proposals: [],
    shipments: [],
    error: null,
  })
  const [approving, setApproving] = useState<string | null>(null)
  const [creatingProposal, setCreatingProposal] = useState(false)
  const controllerRef = useRef<AbortController | null>(null)

  const load = useCallback(async () => {
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller

    try {
      const [proposalResponse, shipmentResponse] = await Promise.all([
        fetch('/api/proposals', { signal: controller.signal }),
        fetch('/api/shipments', { signal: controller.signal }),
      ])

      if (!proposalResponse.ok) {
        throw new Error(`/api/proposals returned ${proposalResponse.status}`)
      }

      const proposals = await readJson<Proposal[]>(proposalResponse)
      // The ledger may not be readable yet on a fresh database.
      const shipments = shipmentResponse.ok
        ? await readJson<Shipment[]>(shipmentResponse)
        : []

      setState({ proposals, shipments, error: null })
    } catch (error) {
      if (controller.signal.aborted) return
      setState((previous) => ({
        ...previous,
        error: error instanceof Error ? error.message : 'failed to load the review list',
      }))
    }
  }, [])

  const createProposal = useCallback(async (): Promise<CreateProposalOutcome> => {
    setCreatingProposal(true)
    try {
      const response = await fetch('/api/proposals', { method: 'POST' })
      if (!response.ok) {
        let message = `proposal could not be created (${response.status})`
        try {
          const body = await readJson<{ message?: string }>(response)
          if (body.message) message = body.message
        } catch {
          // Keep the status based message when the response is not JSON.
        }
        return { ok: false, message }
      }
      await load()
      return { ok: true, message: null }
    } catch (error) {
      return {
        ok: false,
        message: error instanceof Error ? error.message : 'proposal could not be created',
      }
    } finally {
      setCreatingProposal(false)
    }
  }, [load])

  useEffect(() => {
    void load()
    const timer = window.setInterval(() => void load(), POLL_INTERVAL_MS)
    return () => {
      controllerRef.current?.abort()
      window.clearInterval(timer)
    }
  }, [load])

  /**
   * Approves a proposal.
   *
   * The quantities are the operator's confirmed values. They may only be
   * **lower** than the reviewed quantity, and the backend enforces that — the
   * input's `max` attribute is a convenience, not the control.
   */
  const approve = useCallback(
    async (
      proposalId: string,
      actor: string,
      quantities: Record<string, number>,
    ): Promise<ApproveOutcome> => {
      setApproving(proposalId)
      try {
        const response = await fetch(`/api/proposals/${proposalId}/approve`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ actor, quantities }),
        })

        if (!response.ok) {
          let code: ApprovalErrorCode = 'INVALID_REQUEST'
          let message = `the approval was refused (${response.status})`
          try {
            const body = await readJson<ApprovalErrorResponse>(response)
            code = body.code
            message = body.message
          } catch {
            // The body was not the expected shape, so the generic message stands.
          }
          return { ok: false, result: null, errorCode: code, message }
        }

        const result = await readJson<ApprovalResponse>(response)
        await load()
        return { ok: true, result, errorCode: null, message: null }
      } catch (error) {
        return {
          ok: false,
          result: null,
          errorCode: null,
          message:
            error instanceof Error
              ? error.message
              : 'the approval could not be sent',
        }
      } finally {
        setApproving(null)
      }
    },
    [load],
  )

  /** Asks the backend to reconcile any command whose outcome is unknown. */
  const reconcile = useCallback(async () => {
    try {
      const response = await fetch('/api/shipments/reconcile', { method: 'POST' })
      if (response.ok) {
        await load()
      }
    } catch {
      // The reconciliation is a background nicety; a failure leaves the ledger
      // showing the UNKNOWN state, which is the honest answer.
    }
  }, [load])

  return { ...state, approving, creatingProposal, createProposal, approve, reconcile, reload: load }
}
