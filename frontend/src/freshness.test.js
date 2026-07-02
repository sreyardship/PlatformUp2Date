import { formatRelativeDuration, formatFreshnessLine } from './freshness'

// Contract: freshness.js is a framework-free module (no React/MUI imports) that provides
// two exports for formatting read-time information in version cells.
//
// formatRelativeDuration(isoInstant)
//   Given an ISO-8601 instant and the current time (via Date.now()), returns a humanized
//   relative duration string using floor division:
//     - Under 1 hour  → "<N>m"  (minutes)
//     - Under 1 day   → "<N>h"  (hours)
//     - 1 day or more → "<N>d"  (days)
//
// formatFreshnessLine(verb, isoInstant)
//   Composes a full tooltip line: "<verb> <relative> ago — <absolute local time>"
//   where <absolute local time> is derived from new Date(isoInstant).toLocaleTimeString().
//   The em dash (—) separates the relative and absolute parts.
//   Examples:
//     formatFreshnessLine('read', '2026-07-01T07:05:00.000Z') // at 10:05 → "read 3h ago — 10:05:00 AM"
//     formatFreshnessLine('refresh failed', '2026-07-01T10:00:00.000Z') // at 10:05 → "refresh failed 5m ago — 10:00:00 AM"

// ---------------------------------------------------------------------------
// formatRelativeDuration — unit boundary tests
// ---------------------------------------------------------------------------

describe('formatRelativeDuration', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  test('45-minute gap → "45m" (under 1 hour uses minutes)', () => {
    vi.setSystemTime(new Date('2026-07-01T10:45:00.000Z'))
    expect(formatRelativeDuration('2026-07-01T10:00:00.000Z')).toBe('45m')
  })

  test('59-minute gap → "59m" (boundary just under 1 hour still uses minutes)', () => {
    vi.setSystemTime(new Date('2026-07-01T10:59:00.000Z'))
    expect(formatRelativeDuration('2026-07-01T10:00:00.000Z')).toBe('59m')
  })

  test('60-minute gap → "1h" (exactly at 1-hour boundary switches to hours)', () => {
    vi.setSystemTime(new Date('2026-07-01T11:00:00.000Z'))
    expect(formatRelativeDuration('2026-07-01T10:00:00.000Z')).toBe('1h')
  })

  test('3-hour gap → "3h" (not "180m" — hours, not minutes)', () => {
    vi.setSystemTime(new Date('2026-07-01T13:00:00.000Z'))
    expect(formatRelativeDuration('2026-07-01T10:00:00.000Z')).toBe('3h')
  })

  test('23-hour gap → "23h" (boundary just under 1 day still uses hours)', () => {
    vi.setSystemTime(new Date('2026-07-02T09:00:00.000Z'))
    expect(formatRelativeDuration('2026-07-01T10:00:00.000Z')).toBe('23h')
  })

  test('24-hour gap → "1d" (exactly at 1-day boundary switches to days)', () => {
    vi.setSystemTime(new Date('2026-07-02T10:00:00.000Z'))
    expect(formatRelativeDuration('2026-07-01T10:00:00.000Z')).toBe('1d')
  })

  test('2-day gap → "2d"', () => {
    vi.setSystemTime(new Date('2026-07-03T10:00:00.000Z'))
    expect(formatRelativeDuration('2026-07-01T10:00:00.000Z')).toBe('2d')
  })

  test('1-minute gap → "1m" (short gaps use minutes)', () => {
    vi.setSystemTime(new Date('2026-07-01T10:01:00.000Z'))
    expect(formatRelativeDuration('2026-07-01T10:00:00.000Z')).toBe('1m')
  })

  test('floor-divides minutes (90-second gap → "1m", not "1.5m")', () => {
    vi.setSystemTime(new Date('2026-07-01T10:01:30.000Z'))
    expect(formatRelativeDuration('2026-07-01T10:00:00.000Z')).toBe('1m')
  })

  test('floor-divides hours (90-minute gap → "1h", not "2h")', () => {
    vi.setSystemTime(new Date('2026-07-01T11:30:00.000Z'))
    expect(formatRelativeDuration('2026-07-01T10:00:00.000Z')).toBe('1h')
  })

  test('floor-divides days (36-hour gap → "1d", not "2d")', () => {
    vi.setSystemTime(new Date('2026-07-02T22:00:00.000Z'))
    expect(formatRelativeDuration('2026-07-01T10:00:00.000Z')).toBe('1d')
  })
})

// ---------------------------------------------------------------------------
// formatFreshnessLine — line composition tests
// ---------------------------------------------------------------------------

describe('formatFreshnessLine', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  test('composes "read <relative> ago — <absolute>" for verb "read"', () => {
    vi.setSystemTime(new Date('2026-07-01T10:05:00.000Z'))
    const line = formatFreshnessLine('read', '2026-07-01T10:00:00.000Z') // 5m gap
    expect(line).toMatch(/^read 5m ago — /)
  })

  test('uses the provided verb at the start of the line', () => {
    vi.setSystemTime(new Date('2026-07-01T10:05:00.000Z'))
    const line = formatFreshnessLine('refresh failed', '2026-07-01T10:00:00.000Z')
    expect(line).toMatch(/^refresh failed 5m ago — /)
  })

  test('separates relative and absolute parts with an em dash ( — )', () => {
    vi.setSystemTime(new Date('2026-07-01T10:05:00.000Z'))
    const line = formatFreshnessLine('read', '2026-07-01T10:00:00.000Z')
    expect(line).toContain(' — ')
  })

  test('absolute part contains digits and a colon separator (looks like a time)', () => {
    vi.setSystemTime(new Date('2026-07-01T10:05:00.000Z'))
    const line = formatFreshnessLine('read', '2026-07-01T10:00:00.000Z')
    // The absolute time comes after " — " and must match a time-like pattern.
    const [, absolutePart] = line.split(' — ')
    expect(absolutePart).toMatch(/\d+:\d+/)
  })

  test('uses humanized hours in the relative part when gap exceeds 1 hour', () => {
    vi.setSystemTime(new Date('2026-07-01T13:00:00.000Z'))
    const line = formatFreshnessLine('read', '2026-07-01T10:00:00.000Z') // 3h gap
    expect(line).toMatch(/^read 3h ago — /)
    expect(line).not.toMatch(/180m/)
  })

  test('uses humanized days in the relative part when gap exceeds 1 day', () => {
    vi.setSystemTime(new Date('2026-07-03T10:00:00.000Z'))
    const line = formatFreshnessLine('read', '2026-07-01T10:00:00.000Z') // 2d gap
    expect(line).toMatch(/^read 2d ago — /)
  })
})
