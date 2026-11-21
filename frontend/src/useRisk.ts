import { useEffect, useRef, useState } from 'react'
import type { RiskResponse } from './types'

/**
 * Polls the backend for the risk view.
 *
 * The same shape as the dashboard poll, and for the same reason: the browser
 * reads the backend's cached assessment and never talks to the simulator.
 *
 * The risk view is polled on its own timer because it is more expensive to
 * compute than the dashboard. When the assessment fails, the last good one is
 * kept visible and the error is surfaced, so a transient failure does not blank
 * the operator's screen.
 */

const POLL_INTERVAL_MS = 3000

async function fetchRisk(signal: AbortSignal): Promise<RiskResponse> {
  const response = await fetch('/api/risk', { signal })
  if (!response.ok) {
    throw new Error(`/api/risk returned ${response.status}`)
  }
  return (await response.json()) as RiskResponse
}

export interface RiskState {
  risk: RiskResponse | null
  error: string | null
}

export function useRisk(): RiskState {
  const [state, setState] = useState<RiskState>({ risk: null, error: null })
  const controllerRef = useRef<AbortController | null>(null)

  useEffect(() => {
    let active = true

    const load = async () => {
      controllerRef.current?.abort()
      const controller = new AbortController()
      controllerRef.current = controller

      try {
        const risk = await fetchRisk(controller.signal)
        if (active) {
          setState({ risk, error: null })
        }
      } catch (error) {
        if (!active || controller.signal.aborted) return
        setState((previous) => ({
          risk: previous.risk,
          error: error instanceof Error ? error.message : 'failed to load risk view',
        }))
      }
    }

    void load()
    const timer = window.setInterval(() => void load(), POLL_INTERVAL_MS)

    return () => {
      active = false
      controllerRef.current?.abort()
      window.clearInterval(timer)
    }
  }, [])

  return state
}
