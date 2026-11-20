/**
 * Formatting helpers.
 *
 * The one rule here: a value the system does not know renders as "Unknown",
 * never as 0. A zero is a claim about the world; "Unknown" is a claim about our
 * knowledge. Conflating them is how an operator ends up dispatching fuel that
 * was never needed.
 */

/** Renders a possibly-unknown number with a unit. */
export function formatLitres(value: number | null | undefined, unit = 'L'): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return 'Unknown'
  }
  return `${formatNumber(value)} ${unit}`
}

/** Renders a number without a unit, or "Unknown". */
export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return 'Unknown'
  }
  if (Number.isInteger(value)) {
    return value.toLocaleString('en-US')
  }
  return value.toLocaleString('en-US', { maximumFractionDigits: 2 })
}

/** Renders a wall-clock age in seconds as a short human string. */
export function formatAge(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds) || seconds < 0) {
    return 'never'
  }
  if (seconds < 1) return 'just now'
  if (seconds < 60) return `${Math.floor(seconds)}s ago`
  const minutes = Math.floor(seconds / 60)
  return `${minutes}m ago`
}

/**
 * Renders a percentage, or "Unknown".
 * Used for service level, which is the simulator's own metric and may be absent.
 */
export function formatPercent(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return 'Unknown'
  }
  return `${value.toLocaleString('en-US', { maximumFractionDigits: 1 })}%`
}

/**
 * Classifies a fill level as a CSS modifier.
 *
 * These thresholds are a first pass and are explicitly not tuned; they are
 * reported as-is in the day 5 measurement.
 */
export function levelClass(fraction: number | null | undefined): string {
  if (fraction === null || fraction === undefined || !Number.isFinite(fraction)) {
    return 'level-unknown'
  }
  if (fraction <= 0.1) return 'level-critical'
  if (fraction <= 0.25) return 'level-low'
  if (fraction <= 0.6) return 'level-ok'
  return 'level-good'
}

/** Computes a fill fraction, or null when either side is unknown. */
export function fillFraction(
  inventory: number | null | undefined,
  capacity: number | null | undefined,
): number | null {
  if (
    inventory === null ||
    inventory === undefined ||
    capacity === null ||
    capacity === undefined ||
    !Number.isFinite(inventory) ||
    !Number.isFinite(capacity) ||
    capacity <= 0
  ) {
    return null
  }
  return inventory / capacity
}

/** Picks a readable status colour class for an entity status. */
export function statusClass(status: string | null | undefined): string {
  if (!status) return 'status-unknown'
  const upper = status.toUpperCase()
  if (upper === 'OPEN' || upper === 'AVAILABLE' || upper === 'ARRIVED' || upper === 'DELIVERED') {
    return 'status-good'
  }
  if (upper === 'OUTAGE' || upper === 'CLOSED' || upper === 'DISRUPTED' || upper === 'FAILED') {
    return 'status-bad'
  }
  return 'status-warn'
}
