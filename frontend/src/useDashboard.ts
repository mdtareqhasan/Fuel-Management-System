import { useEffect, useRef, useState } from 'react'
import type { DashboardResponse, SystemStatus } from './types'

/**
 * Polls the backend for the dashboard view and the status panel.
 *
 * The browser never talks to the simulator. It reads the backend's cached,
 * validated view — so a hundred open dashboards produce one refresh loop
 * against the simulator, not a hundred.
 */

const POLL_INTERVAL_MS = 2000

async function fetchJson<T>(url: string, signal: AbortSignal): Promise<T> {
  const response = await fetch(url, { signal })
  if (!response.ok) {
    // A 503 means the backend has no valid snapshot yet. That is a state the UI
    // must show, not an error to swallow.
    throw new Error(`${url} returned ${response.status}`)
  }
  return (await response.json()) as T
}

export interface DashboardState {
  dashboard: DashboardResponse | null
  status: SystemStatus | null
  error: string | null
  loading: boolean
}

export function useDashboard(): DashboardState {
  const [state, setState] = useState<DashboardState>({
    dashboard: null,
    status: null,
    error: null,
    loading: true,
  })
  const controllerRef = useRef<AbortController | null>(null)

  useEffect(() => {
    let active = true

    const load = async () => {
      controllerRef.current?.abort()
      const controller = new AbortController()
      controllerRef.current = controller

      try {
        const [dashboard, status] = await Promise.all([
          fetchJson<DashboardResponse>('/api/dashboard', controller.signal),
          fetchJson<SystemStatus>('/api/system-status', controller.signal),
        ])
        if (active) {
          setState({ dashboard, status, error: null, loading: false })
        }
      } catch (error) {
        if (!active || controller.signal.aborted) return
        // Keep the last good dashboard visible. The UI shows the age and the
        // quality badge so the operator can see it is not fresh.
        setState((previous) => ({
          dashboard: previous.dashboard,
          status: previous.status,
          error: error instanceof Error ? error.message : 'failed to load',
          loading: false,
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
