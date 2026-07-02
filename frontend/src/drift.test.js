import { compareVersions, driftCounts } from './drift'

// Contract: driftCounts(versions) takes the version payload shape
//   { "<name>": { current, latest, outdated, drift, resolution } }
// (drift is one of 'NONE' | 'PATCH' | 'MINOR' | 'MAJOR' | null for Unresolved) and returns
//   { total, upToDate, patch, minor, major, unknown }
// where:
//   - total    = number of apps in the payload
//   - upToDate = number of apps with drift === 'NONE'
//   - patch    = number of apps with drift === 'PATCH'
//   - minor    = number of apps with drift === 'MINOR'
//   - major    = number of apps with drift === 'MAJOR'
//   - unknown  = number of apps with drift === null (Unresolved, issue 03)
// An empty payload ({} or undefined/null) yields all-zero counts.
// Invariant: upToDate + patch + minor + major + unknown === total

test('counts a mixed payload by drift level', () => {
  const versions = {
    'app-none': { current: '1.0.0', latest: '1.0.0', outdated: false, drift: 'NONE' },
    'app-patch-1': { current: '1.0.0', latest: '1.0.1', outdated: true, drift: 'PATCH' },
    'app-patch-2': { current: '2.0.0', latest: '2.0.3', outdated: true, drift: 'PATCH' },
    'app-minor-1': { current: '1.0.0', latest: '1.2.0', outdated: true, drift: 'MINOR' },
    'app-major-1': { current: '1.0.0', latest: '2.0.0', outdated: true, drift: 'MAJOR' },
    'app-major-2': { current: '1.0.0', latest: '3.0.0', outdated: true, drift: 'MAJOR' },
    'app-major-3': { current: '1.0.0', latest: '4.0.0', outdated: true, drift: 'MAJOR' },
  }

  expect(driftCounts(versions)).toEqual({
    total: 7,
    upToDate: 1,
    patch: 2,
    minor: 1,
    major: 3,
    unknown: 0,
  })
})

test('returns all-zero counts for an empty payload', () => {
  expect(driftCounts({})).toEqual({
    total: 0,
    upToDate: 0,
    patch: 0,
    minor: 0,
    major: 0,
    unknown: 0,
  })
})

test('returns all-zero counts when versions is undefined', () => {
  expect(driftCounts(undefined)).toEqual({
    total: 0,
    upToDate: 0,
    patch: 0,
    minor: 0,
    major: 0,
    unknown: 0,
  })
})

test('returns all-zero counts when versions is null', () => {
  expect(driftCounts(null)).toEqual({
    total: 0,
    upToDate: 0,
    patch: 0,
    minor: 0,
    major: 0,
    unknown: 0,
  })
})

test('counts an all-up-to-date payload', () => {
  const versions = {
    a: { current: '1.0.0', latest: '1.0.0', outdated: false, drift: 'NONE' },
    b: { current: '2.0.0', latest: '2.0.0', outdated: false, drift: 'NONE' },
  }

  expect(driftCounts(versions)).toEqual({
    total: 2,
    upToDate: 2,
    patch: 0,
    minor: 0,
    major: 0,
    unknown: 0,
  })
})

// --- Issue 03: Unknown (Unresolved) apps in driftCounts ------------------------------------
//
// Apps with drift === null (Unresolved) must be counted in `unknown`, not silently dropped.
// The totals invariant must hold: upToDate + patch + minor + major + unknown === total.

test('counts an Unresolved app (drift: null) in the unknown bucket', () => {
  const versions = {
    'resolved-app': { current: { version: '1.0.0' }, latest: { version: '1.0.0' }, drift: 'NONE', resolution: 'Resolved' },
    'unresolved-app': { current: { version: null }, latest: { version: '1.0.0' }, drift: null, resolution: 'Unresolved' },
  }

  const counts = driftCounts(versions)
  expect(counts.unknown).toBe(1)
  expect(counts.upToDate).toBe(1)
  expect(counts.total).toBe(2)
})

test('driftCounts totals reconcile: upToDate+patch+minor+major+unknown === total', () => {
  const versions = {
    'app-none': { drift: 'NONE' },
    'app-patch': { drift: 'PATCH' },
    'app-minor': { drift: 'MINOR' },
    'app-major': { drift: 'MAJOR' },
    'app-unknown-1': { drift: null, resolution: 'Unresolved' },
    'app-unknown-2': { drift: null, resolution: 'Unresolved' },
  }

  const counts = driftCounts(versions)
  expect(counts.total).toBe(6)
  expect(counts.unknown).toBe(2)
  const sumBuckets = counts.upToDate + counts.patch + counts.minor + counts.major + counts.unknown
  expect(sumBuckets).toBe(counts.total)
})

test('driftCounts unknown is zero when no Unresolved apps present', () => {
  const versions = {
    a: { drift: 'NONE' },
    b: { drift: 'MAJOR' },
  }

  const counts = driftCounts(versions)
  expect(counts.unknown).toBe(0)
  expect(counts.upToDate + counts.patch + counts.minor + counts.major + counts.unknown).toBe(counts.total)
})

test('all-unknown payload: total equals unknown, all other buckets zero', () => {
  const versions = {
    a: { drift: null, resolution: 'Unresolved' },
    b: { drift: null, resolution: 'Unresolved' },
    c: { drift: null, resolution: 'Unresolved' },
  }

  const counts = driftCounts(versions)
  expect(counts.total).toBe(3)
  expect(counts.unknown).toBe(3)
  expect(counts.upToDate).toBe(0)
  expect(counts.patch).toBe(0)
  expect(counts.minor).toBe(0)
  expect(counts.major).toBe(0)
})

// Contract: compareVersions(a, b) compares two version strings SEMVER-aware
// (numeric core comparison), not lexicographically, and returns:
//   - a negative number when a < b
//   - zero             when a and b are equal (by numeric core)
//   - a positive number when a > b
// It tolerates a leading 'v' prefix (e.g. 'v2.9.0') and build/prerelease
// metadata appended after the numeric core (e.g. '2.10.7+b060053'), comparing
// only the numeric major.minor.patch core in those cases.

test('compareVersions sorts numerically, not lexicographically (v2.9.0 < v2.10.0)', () => {
  expect(compareVersions('v2.9.0', 'v2.10.0')).toBeLessThan(0)
  expect(compareVersions('v2.10.0', 'v2.9.0')).toBeGreaterThan(0)
})

test('compareVersions sorts 1.0.0 before 1.0.10 (patch numeric, not lexicographic)', () => {
  expect(compareVersions('1.0.0', '1.0.10')).toBeLessThan(0)
  expect(compareVersions('1.0.10', '1.0.0')).toBeGreaterThan(0)
})

test('compareVersions returns 0 for equal versions', () => {
  expect(compareVersions('1.2.3', '1.2.3')).toBe(0)
})

test('compareVersions treats a leading "v" prefix as equivalent to no prefix', () => {
  expect(compareVersions('v1.2.3', '1.2.3')).toBe(0)
})

test('compareVersions ignores build metadata after the numeric core', () => {
  expect(compareVersions('2.10.7+b060053', '2.10.7')).toBe(0)
  expect(compareVersions('2.10.7+b060053', '2.10.8')).toBeLessThan(0)
})

test('compareVersions compares major version first', () => {
  expect(compareVersions('2.0.0', '1.9.9')).toBeGreaterThan(0)
})

test('compareVersions compares minor version when major is equal', () => {
  expect(compareVersions('1.2.0', '1.9.0')).toBeLessThan(0)
})
