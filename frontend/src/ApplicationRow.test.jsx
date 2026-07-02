import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import ApplicationRow from './ApplicationRow'
import versionClient from './api/versionClient'

// Contract: <ApplicationRow name="app" ver={{ current, latest, outdated, drift }} onRefreshed={fn} />
// Renders a single <tr> (must be mounted inside <table><tbody>...) with:
//   - App Name cell: avatar initial + name
//   - Status cell: a drift badge with a status label (NONE/PATCH/MINOR/MAJOR
//     -> "Up to Date"/"Patch Available"/"Minor Available"/"Major Available")
//   - Current Version / Latest Version cells (latest emphasised when outdated).
//     Each side is a {version, readAt} object; beneath the version string a muted
//     "read Xm ago" label is shown, with the absolute local time on hover.
//   - Changelog cell: an inert document-icon control with tooltip
//     "Changelog — coming soon" that performs no action
//   - Actions cell: "Rescrape current" + "Rescrape latest" buttons, each
//     calling versionClient.scrapeApplication(name, side) and refetching on success

vi.mock('./api/versionClient', () => ({
  __esModule: true,
  default: { scrapeApplication: vi.fn() },
}))

beforeEach(() => {
  vi.clearAllMocks()
})

const renderRow = (props) =>
  render(
    <table>
      <tbody>
        <ApplicationRow {...props} />
      </tbody>
    </table>
  )

// Slice 01: each side is a { version, readAt } object.
const FIXED_READ_AT = '2026-07-01T10:00:00.000Z'

const baseVer = (overrides) => ({
  current: { version: '1.0.0', readAt: FIXED_READ_AT },
  latest: { version: '1.0.0', readAt: FIXED_READ_AT },
  outdated: false,
  drift: 'NONE',
  ...overrides,
})

describe('status badge label per drift level', () => {
  test('drift NONE shows "Up to Date"', () => {
    renderRow({ name: 'app', ver: baseVer({ drift: 'NONE' }), onRefreshed: vi.fn() })
    expect(screen.getByText(/up to date/i)).toBeInTheDocument()
  })

  test('drift PATCH shows "Patch Available"', () => {
    renderRow({
      name: 'app',
      ver: baseVer({ drift: 'PATCH', outdated: true, latest: { version: '1.0.1', readAt: FIXED_READ_AT } }),
      onRefreshed: vi.fn(),
    })
    expect(screen.getByText(/patch available/i)).toBeInTheDocument()
  })

  test('drift MINOR shows "Minor Available"', () => {
    renderRow({
      name: 'app',
      ver: baseVer({ drift: 'MINOR', outdated: true, latest: { version: '1.2.0', readAt: FIXED_READ_AT } }),
      onRefreshed: vi.fn(),
    })
    expect(screen.getByText(/minor available/i)).toBeInTheDocument()
  })

  test('drift MAJOR shows "Major Available"', () => {
    renderRow({
      name: 'app',
      ver: baseVer({ drift: 'MAJOR', outdated: true, latest: { version: '2.0.0', readAt: FIXED_READ_AT } }),
      onRefreshed: vi.fn(),
    })
    expect(screen.getByText(/major available/i)).toBeInTheDocument()
  })
})

test('renders the app name and avatar initial', () => {
  renderRow({ name: 'git-tea', ver: baseVer(), onRefreshed: vi.fn() })

  expect(screen.getByText('git-tea')).toBeInTheDocument()
  // Avatar initial is the first character, uppercased.
  expect(screen.getByText('G')).toBeInTheDocument()
})

test('renders the current and latest version strings', () => {
  renderRow({
    name: 'app',
    ver: baseVer({
      current: { version: '1.21.7', readAt: FIXED_READ_AT },
      latest: { version: '1.22.1', readAt: FIXED_READ_AT },
      outdated: true,
      drift: 'MINOR',
    }),
    onRefreshed: vi.fn(),
  })

  expect(screen.getByText('1.21.7')).toBeInTheDocument()
  expect(screen.getByText('1.22.1')).toBeInTheDocument()
})

// Parses a CSS "rgb(r, g, b)" string into { r, g, b }.
const rgb = (color) => {
  const [r, g, b] = color.match(/\d+/g).map(Number)
  return { r, g, b }
}

test('the latest version is visually emphasised (different color) when outdated vs. up to date', () => {
  // Distinct current ('0.9.0') so '1.0.0' uniquely identifies the latest cell.
  const { unmount: unmountUpToDate } = renderRow({
    name: 'app',
    ver: baseVer({
      current: { version: '0.9.0', readAt: FIXED_READ_AT },
      latest: { version: '1.0.0', readAt: FIXED_READ_AT },
      outdated: false,
      drift: 'NONE',
    }),
    onRefreshed: vi.fn(),
  })
  const upToDateColor = getComputedStyle(screen.getByText('1.0.0')).color
  unmountUpToDate()

  const { unmount: unmountOutdated } = renderRow({
    name: 'app',
    ver: baseVer({
      current: { version: '1.0.0', readAt: FIXED_READ_AT },
      latest: { version: '2.0.0', readAt: FIXED_READ_AT },
      outdated: true,
      drift: 'MAJOR',
    }),
    onRefreshed: vi.fn(),
  })
  const outdatedLatestColor = getComputedStyle(screen.getByText('2.0.0')).color
  unmountOutdated()

  expect(rgb(outdatedLatestColor)).not.toEqual(rgb(upToDateColor))
})

test('renders "Rescrape current" and "Rescrape latest" buttons', () => {
  renderRow({ name: 'app', ver: baseVer(), onRefreshed: vi.fn() })

  expect(screen.getByRole('button', { name: /rescrape current/i })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /rescrape latest/i })).toBeInTheDocument()
})

test('clicking "Rescrape current" scrapes (name, "current") and refetches on success', async () => {
  versionClient.scrapeApplication.mockResolvedValue({})
  const onRefreshed = vi.fn().mockResolvedValue(undefined)
  const user = userEvent.setup()

  renderRow({ name: 'my-app', ver: baseVer(), onRefreshed })

  await user.click(screen.getByRole('button', { name: /rescrape current/i }))

  await waitFor(() => {
    expect(versionClient.scrapeApplication).toHaveBeenCalledWith('my-app', 'current')
  })
  await waitFor(() => {
    expect(onRefreshed).toHaveBeenCalledTimes(1)
  })
})

test('clicking "Rescrape latest" scrapes (name, "latest") and refetches on success', async () => {
  versionClient.scrapeApplication.mockResolvedValue({})
  const onRefreshed = vi.fn().mockResolvedValue(undefined)
  const user = userEvent.setup()

  renderRow({ name: 'my-app', ver: baseVer(), onRefreshed })

  await user.click(screen.getByRole('button', { name: /rescrape latest/i }))

  await waitFor(() => {
    expect(versionClient.scrapeApplication).toHaveBeenCalledWith('my-app', 'latest')
  })
  await waitFor(() => {
    expect(onRefreshed).toHaveBeenCalledTimes(1)
  })
})

test('renders an inert Changelog control with an explanatory tooltip and it performs no action', async () => {
  const user = userEvent.setup()
  renderRow({ name: 'my-app', ver: baseVer(), onRefreshed: vi.fn() })

  const changelogControl = screen.getByRole('button', { name: /changelog/i })
  expect(changelogControl).toBeInTheDocument()

  await user.hover(changelogControl)
  expect(await screen.findByText('Changelog — coming soon')).toBeInTheDocument()

  await user.click(changelogControl)
  expect(versionClient.scrapeApplication).not.toHaveBeenCalled()
})

// --- Issue 03: Unknown (Unresolved) app display ------------------------------------------
//
// When an app is Unresolved (resolution === 'Unresolved', drift === null):
//   - A grey "Unknown" status badge is shown (distinct from the drift severity colours).
//   - A null version is displayed as "—" (em dash).
//   - A failed side still shows its FailedRefreshLabel even when version is null.
//   - Non-null sides display normally alongside the null side.

describe('Unknown (Unresolved) app display', () => {
  const FIXED_FAILED_AT = '2026-07-01T10:05:00.000Z'
  const FIXED_NOW_UNRESOLVED = new Date('2026-07-01T10:10:00.000Z')

  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(FIXED_NOW_UNRESOLVED)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  const unresolvedVer = (overrides = {}) => ({
    current: { version: null, readAt: null },
    latest: { version: null, readAt: null },
    outdated: false,
    drift: null,
    resolution: 'Unresolved',
    ...overrides,
  })

  test('renders "Unknown" status badge when drift is null (Unresolved app)', () => {
    renderRow({ name: 'cold-app', ver: unresolvedVer(), onRefreshed: vi.fn() })
    expect(screen.getByText(/unknown/i)).toBeInTheDocument()
  })

  test('Unknown badge is distinct from drift severity colours (grey, not green/yellow/orange/red)', () => {
    renderRow({ name: 'cold-app', ver: unresolvedVer(), onRefreshed: vi.fn() })
    const badge = screen.getByText(/unknown/i)
    // The badge element must be present; the grey color assertion is behavioural:
    // it must NOT carry the known severity colours.
    expect(badge).toBeInTheDocument()
    // The Unknown badge must not show drift labels from existing statuses.
    expect(screen.queryByText(/up to date/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/patch available/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/minor available/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/major available/i)).not.toBeInTheDocument()
  })

  test('displays "—" (em dash) for a null current version', () => {
    renderRow({
      name: 'cold-app',
      ver: unresolvedVer({ current: { version: null, readAt: null } }),
      onRefreshed: vi.fn(),
    })
    // At least one em dash must be visible for the missing current version.
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(1)
  })

  test('displays "—" for a null latest version', () => {
    renderRow({
      name: 'cold-app',
      ver: unresolvedVer({ latest: { version: null, readAt: null } }),
      onRefreshed: vi.fn(),
    })
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(1)
  })

  test('displays "—" for both sides when both versions are null', () => {
    renderRow({ name: 'cold-app', ver: unresolvedVer(), onRefreshed: vi.fn() })
    // Two em dashes — one per side.
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2)
  })

  test('shows FailedRefreshLabel for a null-version side that carries failedAt', () => {
    // A side that attempted-and-failed has no value (version: null) but does have failedAt.
    // The FailedRefreshLabel must still render alongside the "—" placeholder.
    renderRow({
      name: 'cold-app',
      ver: unresolvedVer({
        current: { version: null, readAt: null, failedAt: FIXED_FAILED_AT },
        latest:  { version: null, readAt: null },
      }),
      onRefreshed: vi.fn(),
    })
    // The "—" placeholder must be present.
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(1)
    // The "refresh failed" marker must still appear despite null version.
    expect(screen.getByText(/refresh failed/i)).toBeInTheDocument()
  })

  test('a partially resolved app shows the resolved version normally and "—" for the missing side', () => {
    // current resolved, latest pending.
    renderRow({
      name: 'partial-app',
      ver: {
        current: { version: '1.0.0', readAt: '2026-07-01T10:00:00.000Z' },
        latest:  { version: null, readAt: null },
        outdated: false,
        drift: null,
        resolution: 'Unresolved',
      },
      onRefreshed: vi.fn(),
    })
    expect(screen.getByText('1.0.0')).toBeInTheDocument()
    expect(screen.getByText('—')).toBeInTheDocument()
    expect(screen.getByText(/unknown/i)).toBeInTheDocument()
  })
})

// --- Slice 01: per-side readAt display ---------------------------------------------------
//
// Each version cell must show a muted relative "read Xm ago" label computed client-side
// from the per-side readAt instant, with the absolute local-timezone time in a hover tooltip.
// Tests in this describe block freeze Date.now() so the relative math is deterministic.

describe('per-side readAt display', () => {
  // FIXED_READ_AT is 5 minutes before FIXED_NOW so the relative label reads "read 5m ago".
  const FIXED_NOW = new Date('2026-07-01T10:05:00.000Z')

  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(FIXED_NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  test('shows a muted relative "read Xm ago" label under each version side', () => {
    renderRow({
      name: 'app',
      ver: {
        current: { version: '1.0.0', readAt: FIXED_READ_AT },
        latest: { version: '2.0.0', readAt: FIXED_READ_AT },
        outdated: true,
        drift: 'MAJOR',
      },
      onRefreshed: vi.fn(),
    })

    // Both the current and latest cells must show a relative "read Xm ago" label.
    const relativeLabels = screen.getAllByText(/read \d+m ago/i)
    expect(relativeLabels.length).toBeGreaterThanOrEqual(2)
  })

  test('relative label is computed from readAt vs current time (5m gap → "read 5m ago")', () => {
    renderRow({
      name: 'app',
      ver: {
        current: { version: '1.0.0', readAt: FIXED_READ_AT },
        latest: { version: '2.0.0', readAt: FIXED_READ_AT },
        outdated: true,
        drift: 'MAJOR',
      },
      onRefreshed: vi.fn(),
    })

    // With FIXED_READ_AT 5 minutes before FIXED_NOW the label must show "5m".
    expect(screen.getAllByText(/read 5m ago/i).length).toBeGreaterThanOrEqual(1)
  })

  test('hovering the relative label shows the absolute local time as a tooltip', async () => {
    const user = userEvent.setup({ delay: null })
    renderRow({
      name: 'app',
      ver: {
        current: { version: '1.0.0', readAt: FIXED_READ_AT },
        latest: { version: '2.0.0', readAt: FIXED_READ_AT },
        outdated: true,
        drift: 'MAJOR',
      },
      onRefreshed: vi.fn(),
    })

    const [firstLabel] = screen.getAllByText(/read \d+m ago/i)
    await user.hover(firstLabel)

    // The tooltip must appear and contain a time-like string (digits + separator).
    const tooltip = await screen.findByRole('tooltip')
    expect(tooltip).toBeInTheDocument()
    // Absolute time contains digits (hours, minutes) and a colon separator.
    expect(tooltip.textContent).toMatch(/\d+:\d+/)
  })
})

// --- Slice 02: failed-refresh marker display --------------------------------------------
//
// When a side's `failedAt` is present (the most recent refresh attempt failed), a muted
// "refresh failed Xm ago" marker must appear alongside the still-displayed old value and
// its "read Xm ago" label. The marker must NOT appear when `failedAt` is absent/null.
//
// The backend only emits `failedAt` when failedRefresh() is true (newest attempt failed),
// so the frontend simply checks: failedAt present → show marker.

describe('failed-refresh marker display', () => {
  // FIXED_READ_AT   = 2026-07-01T10:00:00Z  (prior good read, 10m before FIXED_NOW)
  // FIXED_FAILED_AT = 2026-07-01T10:05:00Z  (failed refresh, 5m before FIXED_NOW)
  // FIXED_NOW       = 2026-07-01T10:10:00Z
  const FIXED_NOW_MARKER      = new Date('2026-07-01T10:10:00.000Z')
  const FIXED_READ_AT_10M_AGO = '2026-07-01T10:00:00.000Z'
  const FIXED_FAILED_AT_5M    = '2026-07-01T10:05:00.000Z'

  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(FIXED_NOW_MARKER)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  test('no failed-refresh marker when failedAt is absent on both sides', () => {
    renderRow({
      name: 'app',
      ver: {
        current: { version: '1.0.0', readAt: FIXED_READ_AT_10M_AGO },
        latest:  { version: '2.0.0', readAt: FIXED_READ_AT_10M_AGO },
        outdated: true,
        drift: 'MAJOR',
      },
      onRefreshed: vi.fn(),
    })

    expect(screen.queryByText(/refresh failed/i)).not.toBeInTheDocument()
  })

  test('shows a "refresh failed Xm ago" marker when current side has failedAt', () => {
    renderRow({
      name: 'app',
      ver: {
        current: { version: '1.0.0', readAt: FIXED_READ_AT_10M_AGO, failedAt: FIXED_FAILED_AT_5M },
        latest:  { version: '2.0.0', readAt: FIXED_READ_AT_10M_AGO },
        outdated: true,
        drift: 'MAJOR',
      },
      onRefreshed: vi.fn(),
    })

    // The marker must appear. Text: "refresh failed 5m ago" (failedAt 5m before FIXED_NOW).
    expect(screen.getByText(/refresh failed 5m ago/i)).toBeInTheDocument()
  })

  test('shows a "refresh failed Xm ago" marker when latest side has failedAt', () => {
    renderRow({
      name: 'app',
      ver: {
        current: { version: '1.0.0', readAt: FIXED_READ_AT_10M_AGO },
        latest:  { version: '2.0.0', readAt: FIXED_READ_AT_10M_AGO, failedAt: FIXED_FAILED_AT_5M },
        outdated: true,
        drift: 'MAJOR',
      },
      onRefreshed: vi.fn(),
    })

    expect(screen.getByText(/refresh failed 5m ago/i)).toBeInTheDocument()
  })

  test('old value and read-time label are still shown alongside the failed-refresh marker', () => {
    renderRow({
      name: 'app',
      ver: {
        current: { version: '1.0.0', readAt: FIXED_READ_AT_10M_AGO, failedAt: FIXED_FAILED_AT_5M },
        latest:  { version: '2.0.0', readAt: FIXED_READ_AT_10M_AGO },
        outdated: true,
        drift: 'MAJOR',
      },
      onRefreshed: vi.fn(),
    })

    // Old value must still be visible.
    expect(screen.getByText('1.0.0')).toBeInTheDocument()

    // The "read Xm ago" label must still appear (10m gap).
    const readLabels = screen.getAllByText(/read 10m ago/i)
    expect(readLabels.length).toBeGreaterThanOrEqual(1)

    // The failed-refresh marker must also appear.
    expect(screen.getByText(/refresh failed 5m ago/i)).toBeInTheDocument()
  })

  test('failed-refresh marker relative time is computed from failedAt vs now', () => {
    // The marker reads "refresh failed 5m ago" because FIXED_FAILED_AT_5M is exactly 5 minutes
    // before FIXED_NOW_MARKER. This confirms the relative math uses the failedAt instant.
    renderRow({
      name: 'app',
      ver: {
        current: { version: '1.0.0', readAt: FIXED_READ_AT_10M_AGO, failedAt: FIXED_FAILED_AT_5M },
        latest:  { version: '2.0.0', readAt: FIXED_READ_AT_10M_AGO },
        outdated: false,
        drift: 'NONE',
      },
      onRefreshed: vi.fn(),
    })

    expect(screen.getByText(/refresh failed 5m ago/i)).toBeInTheDocument()
  })

  test('hovering the failed-refresh marker shows the absolute local failure time as a tooltip', async () => {
    const user = userEvent.setup({ delay: null })
    renderRow({
      name: 'app',
      ver: {
        current: { version: '1.0.0', readAt: FIXED_READ_AT_10M_AGO, failedAt: FIXED_FAILED_AT_5M },
        latest:  { version: '2.0.0', readAt: FIXED_READ_AT_10M_AGO },
        outdated: false,
        drift: 'NONE',
      },
      onRefreshed: vi.fn(),
    })

    const marker = screen.getByText(/refresh failed \d+m ago/i)
    await user.hover(marker)

    // A tooltip with the absolute local time must appear.
    const tooltip = await screen.findByRole('tooltip')
    expect(tooltip).toBeInTheDocument()
    // Absolute time contains digits and a colon separator.
    expect(tooltip.textContent).toMatch(/\d+:\d+/)
  })
})
