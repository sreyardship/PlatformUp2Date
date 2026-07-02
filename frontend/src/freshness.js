// Framework-free freshness formatting helpers.
// No React or MUI imports — this module is safe to use from any context.

/**
 * Returns a humanized relative duration string for the gap between the given
 * ISO-8601 instant and the current time (via Date.now()), using floor division:
 *   - Under 1 hour  → "<N>m"
 *   - Under 1 day   → "<N>h"
 *   - 1 day or more → "<N>d"
 */
export function formatRelativeDuration(isoInstant) {
  const diffMs = Date.now() - new Date(isoInstant).getTime()
  if (diffMs < 3_600_000) return `${Math.floor(diffMs / 60_000)}m`
  if (diffMs < 86_400_000) return `${Math.floor(diffMs / 3_600_000)}h`
  return `${Math.floor(diffMs / 86_400_000)}d`
}

/**
 * Composes a full freshness tooltip line:
 *   "<verb> <relative> ago — <absolute local time>"
 *
 * The absolute time is derived from new Date(isoInstant).toLocaleTimeString().
 * The em dash (—) separates the relative and absolute parts.
 *
 * Example: formatFreshnessLine('read', '2026-07-01T07:05:00.000Z') at 10:05
 *   → "read 3h ago — 10:05:00 AM"
 */
export function formatFreshnessLine(verb, isoInstant) {
  const relative = formatRelativeDuration(isoInstant)
  const absolute = new Date(isoInstant).toLocaleTimeString()
  return `${verb} ${relative} ago — ${absolute}`
}
