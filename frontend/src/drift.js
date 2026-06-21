// Shared drift-severity logic. Slices 04/05 reuse driftCounts/severityColor
// for the table and status badges, so keep this module framework-free.

export const DRIFT_LEVELS = ['NONE', 'PATCH', 'MINOR', 'MAJOR']

// Severity colors taken from the rendered reference (style-reference/code.html).
// MINOR's orange (#fb923c) has no theme palette slot, so it lives here as a
// literal alongside the others for a single source of truth.
export const severityColor = {
  NONE: '#4ade80',
  PATCH: '#fbbf24',
  MINOR: '#fb923c',
  MAJOR: '#f87171',
}

// Status label shown on the per-row drift badge (slice 04 ApplicationRow).
export const driftStatusLabel = {
  NONE: 'Up to Date',
  PATCH: 'Patch Available',
  MINOR: 'Minor Available',
  MAJOR: 'Major Available',
}

const emptyCounts = () => ({ total: 0, upToDate: 0, patch: 0, minor: 0, major: 0 })

export function driftCounts(versions) {
  if (!versions) return emptyCounts()

  const entries = Object.values(versions)
  const counts = emptyCounts()
  counts.total = entries.length

  for (const { drift } of entries) {
    if (drift === 'NONE') counts.upToDate += 1
    else if (drift === 'PATCH') counts.patch += 1
    else if (drift === 'MINOR') counts.minor += 1
    else if (drift === 'MAJOR') counts.major += 1
  }

  return counts
}

// Numeric semver-core comparator (major.minor.patch only). Tolerates a
// leading 'v' prefix and trailing build/prerelease metadata (e.g. '+b060053'
// or '-rc1'), comparing only the numeric core. Used for version-column sort
// so '2.9.0' sorts before '2.10.0' (lexicographic comparison would not).
const semverCore = (version) =>
  String(version)
    .replace(/^v/i, '')
    .split(/[+-]/)[0]
    .split('.')
    .map((part) => parseInt(part, 10) || 0)

export function compareVersions(a, b) {
  const [aMajor, aMinor, aPatch] = semverCore(a)
  const [bMajor, bMinor, bPatch] = semverCore(b)

  if (aMajor !== bMajor) return aMajor - bMajor
  if (aMinor !== bMinor) return aMinor - bMinor
  return aPatch - bPatch
}
