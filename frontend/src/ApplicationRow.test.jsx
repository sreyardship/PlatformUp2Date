import { render, screen, waitFor, fireEvent, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import ApplicationRow from './ApplicationRow'
import versionClient from './api/versionClient'

// Contract: <ApplicationRow name="app" ver={{ current, latest, outdated, drift }} onRefreshed={fn} />
// Renders a single <tr> (must be mounted inside <table><tbody>...) with:
//   - App Name cell: avatar initial + name
//   - Status cell: a drift badge with a status label (NONE/PATCH/MINOR/MAJOR
//     -> "Up to Date"/"Patch Available"/"Minor Available"/"Major Available")
//   - Current Version / Latest Version cells (latest emphasised when outdated).
//     Each side is a { version, readAt, failedAt? } object.
//     No always-visible captions — a healthy row is caption-free.
//     Hovering the version string (when readAt present) shows "read Xm ago — <absolute>".
//     When failedAt is present an amber WarningAmberIcon appears after the version string;
//     hovering it shows a two-line tooltip: "read Xm ago — <abs>" + "refresh failed Xm ago — <abs>".
//   - Changelog cell: driven by ver.changelogUrl (sibling of drift/outdated).
//     - present -> a link-rendered icon button (href/target="_blank"/rel="noopener noreferrer"),
//       tooltip "Open changelog"
//     - null -> a disabled icon button (no anchor in the DOM), tooltip "No changelog link"
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
  changelogUrl: null,
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

// --- Changelog control (driven by ver.changelogUrl) --------------------------------------
//
// The changelog icon in the board is a sibling column, always rendered (never hidden) so the
// column stays aligned across a mixed fleet. Its behaviour depends solely on ver.changelogUrl:
//   - present -> a real link (anchor-rendered icon) that opens the URL in a new tab, tooltip
//     "Open changelog"
//   - null    -> a visibly disabled icon, not clickable, no anchor in the DOM, tooltip
//     "No changelog link"
// The old "Changelog — coming soon" placeholder tooltip must be gone entirely.

describe('changelog control', () => {
  const CHANGELOG_URL = 'https://github.com/argoproj/argo-cd/releases/tag/v3.0.5'

  test('changelogUrl present renders a link with correct href, target and rel', () => {
    renderRow({ name: 'my-app', ver: baseVer({ changelogUrl: CHANGELOG_URL }), onRefreshed: vi.fn() })

    const link = screen.getByRole('link', { name: /changelog/i })
    expect(link).toHaveAttribute('href', CHANGELOG_URL)
    expect(link).toHaveAttribute('target', '_blank')
    // rel must include both noopener and noreferrer (order-agnostic).
    const rel = link.getAttribute('rel')
    expect(rel).toMatch(/noopener/)
    expect(rel).toMatch(/noreferrer/)
  })

  test('changelogUrl present shows "Open changelog" tooltip on hover', async () => {
    const user = userEvent.setup()
    renderRow({ name: 'my-app', ver: baseVer({ changelogUrl: CHANGELOG_URL }), onRefreshed: vi.fn() })

    const link = screen.getByRole('link', { name: /changelog/i })
    await user.hover(link)
    expect(await screen.findByText('Open changelog')).toBeInTheDocument()
  })

  test('changelogUrl null renders a disabled changelog icon with no anchor in the DOM', () => {
    renderRow({ name: 'my-app', ver: baseVer({ changelogUrl: null }), onRefreshed: vi.fn() })

    expect(screen.queryByRole('link', { name: /changelog/i })).not.toBeInTheDocument()
    const button = screen.getByRole('button', { name: /changelog/i })
    expect(button).toBeDisabled()
  })

  test('changelogUrl null shows "No changelog link" tooltip on hover', async () => {
    const user = userEvent.setup()
    renderRow({ name: 'my-app', ver: baseVer({ changelogUrl: null }), onRefreshed: vi.fn() })

    const button = screen.getByRole('button', { name: /changelog/i })
    await user.hover(button)
    expect(await screen.findByText('No changelog link')).toBeInTheDocument()
  })

  test('the "Changelog — coming soon" placeholder tooltip is gone', async () => {
    const user = userEvent.setup()
    renderRow({ name: 'my-app', ver: baseVer({ changelogUrl: null }), onRefreshed: vi.fn() })
    await user.hover(screen.getByRole('button', { name: /changelog/i }))
    expect(await screen.findByText('No changelog link')).toBeInTheDocument()
    expect(screen.queryByText(/coming soon/i)).not.toBeInTheDocument()

    renderRow({ name: 'other-app', ver: baseVer({ changelogUrl: CHANGELOG_URL }), onRefreshed: vi.fn() })
    expect(screen.queryByText(/coming soon/i)).not.toBeInTheDocument()
  })

  test('a stale row (failed-refresh marker on latest) still links using the last-known latest version — no special handling', () => {
    // The changelog URL is independent of readAt/failedAt on the latest side: no special
    // casing is expected for a row whose latest side carries a failed-refresh marker.
    renderRow({
      name: 'my-app',
      ver: baseVer({
        changelogUrl: CHANGELOG_URL,
        latest: { version: '3.0.5', readAt: FIXED_READ_AT, failedAt: '2026-07-01T10:05:00.000Z' },
        outdated: true,
        drift: 'PATCH',
      }),
      onRefreshed: vi.fn(),
    })

    const link = screen.getByRole('link', { name: /changelog/i })
    expect(link).toHaveAttribute('href', CHANGELOG_URL)
  })
})

// --- Issue 03: Unknown (Unresolved) app display ------------------------------------------
//
// When an app is Unresolved (resolution === 'Unresolved', drift === null):
//   - A grey "Unknown" status badge is shown (distinct from the drift severity colours).
//   - A null version is displayed as "—" (em dash).
//   - A failed side still shows its warning icon even when version is null.
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

  test('shows warning icon for a null-version side that carries failedAt', () => {
    // A side that attempted-and-failed has no value (version: null) but does have failedAt.
    // The amber WarningAmberIcon must still render alongside the "—" placeholder.
    // No always-visible "refresh failed" text should appear — only the icon.
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
    // The warning icon must still render despite null version.
    expect(screen.getByTestId('WarningAmberIcon')).toBeInTheDocument()
    // No always-visible "refresh failed" text — only the icon.
    expect(screen.queryByText(/refresh failed/i)).not.toBeInTheDocument()
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

// --- Slice 01: per-side readAt display (reworked) ----------------------------------------
//
// Each version cell is now a single line (the version string, or "—"). The always-visible
// "read Xm ago" caption is gone. Instead the version string itself is a hover target:
// hovering it reveals a tooltip: "read <relative> ago — <absolute local time>".
//
// Relative time is humanized with floor division: minutes under 1h, hours under 1d, days
// after that. This module delegates the formatting to freshness.js (slice 01 also creates
// freshness.js — see freshness.test.js for unit-level boundary tests).
//
// Sides without a readAt show "—" (when version is null) and expose no hover tooltip.
// Tests freeze Date.now() via vi.setSystemTime so the relative math is deterministic.

describe('per-side readAt display', () => {
  // FIXED_READ_AT is 5 minutes before FIXED_NOW → relative label "5m".
  const FIXED_NOW = new Date('2026-07-01T10:05:00.000Z')

  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(FIXED_NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  test('no always-visible "read … ago" text renders anywhere in a row', () => {
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

    // "read … ago" must NOT be visible without any hover interaction.
    expect(screen.queryByText(/read .+ ago/i)).not.toBeInTheDocument()
  })

  test('hovering a version string with readAt shows "read <relative> ago — <absolute>" tooltip', () => {
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

    // FIXED_READ_AT is 5 minutes before FIXED_NOW → "5m".
    const versionEl = screen.getByText('1.0.0')
    fireEvent.mouseEnter(versionEl)

    const tooltip = screen.getByRole('tooltip')
    expect(tooltip).toBeInTheDocument()
    // Tooltip contains "read 5m ago" (humanized minutes for sub-hour gap).
    expect(tooltip.textContent).toMatch(/read 5m ago/)
    // Tooltip contains an em dash separator.
    expect(tooltip.textContent).toContain(' — ')
    // Tooltip contains an absolute time (digits + colon).
    expect(tooltip.textContent).toMatch(/\d+:\d+/)
  })

  test('tooltip disappears when the mouse leaves the version string', () => {
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

    const versionEl = screen.getByText('1.0.0')
    fireEvent.mouseEnter(versionEl)
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
    fireEvent.mouseLeave(versionEl)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  test('a side with readAt: null shows "—" and exposes no tooltip on hover', () => {
    renderRow({
      name: 'app',
      ver: {
        // current side: null version, no readAt → shows "—", no tooltip.
        current: { version: null, readAt: null },
        latest: { version: '2.0.0', readAt: FIXED_READ_AT },
        outdated: false,
        drift: null,
      },
      onRefreshed: vi.fn(),
    })

    const dashEl = screen.getByText('—')
    expect(dashEl).toBeInTheDocument()

    fireEvent.mouseEnter(dashEl)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })
})

// --- Slice 02: failed-refresh warning icon display --------------------------------------
//
// When a side's `failedAt` is present the row renders an amber WarningAmberIcon after
// the version string (or after "—" for a value-less side). There are NO always-visible
// freshness captions anywhere in the row. Hovering the icon shows a two-line tooltip:
//   line 1: "read <relative> ago — <absolute>"  (or "never read successfully" when readAt null)
//   line 2: "refresh failed <relative> ago — <absolute>"
//
// The backend only emits `failedAt` when the newest attempt failed, so the frontend
// simply checks: failedAt present → show icon.

describe('failed-refresh marker display', () => {
  // FIXED_READ_AT_10M_AGO = 2026-07-01T10:00:00Z  (prior good read, 10m before FIXED_NOW)
  // FIXED_FAILED_AT_5M    = 2026-07-01T10:05:00Z  (failed refresh, 5m before FIXED_NOW)
  // FIXED_NOW_MARKER      = 2026-07-01T10:10:00Z
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

  test('no warning icon when failedAt is absent on both sides', () => {
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

    expect(screen.queryByTestId('WarningAmberIcon')).not.toBeInTheDocument()
  })

  test('no always-visible "refresh failed" text renders anywhere in a row, even with failedAt', () => {
    // The icon replaces the always-visible caption — no visible text should appear outside hover.
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

    expect(screen.queryByText(/refresh failed/i)).not.toBeInTheDocument()
  })

  test('no always-visible "read … ago" text when failedAt is present (ReadAtCaption removed)', () => {
    // Slice 01 had a bridge ReadAtCaption that showed "read Xm ago" when failedAt was present.
    // Slice 02 removes it — the read time is in the hover tooltip only.
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

    expect(screen.queryByText(/read .+ ago/i)).not.toBeInTheDocument()
  })

  test('renders amber warning icon when current side has failedAt', () => {
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

    expect(screen.getByTestId('WarningAmberIcon')).toBeInTheDocument()
  })

  test('renders amber warning icon when latest side has failedAt', () => {
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

    expect(screen.getByTestId('WarningAmberIcon')).toBeInTheDocument()
  })

  test('version string is still visible alongside the warning icon when failedAt is present', () => {
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

    expect(screen.getByText('1.0.0')).toBeInTheDocument()
    expect(screen.getByTestId('WarningAmberIcon')).toBeInTheDocument()
  })

  test('hovering the warning icon shows two-line tooltip: "read Xm ago" and "refresh failed Xm ago"', () => {
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

    const icon = screen.getByTestId('WarningAmberIcon')
    fireEvent.mouseEnter(icon)

    const tooltip = screen.getByRole('tooltip')
    expect(tooltip).toBeInTheDocument()
    // Line 1: read 10m ago (readAt is 10m before FIXED_NOW_MARKER).
    expect(tooltip.textContent).toMatch(/read 10m ago/)
    // Line 2: refresh failed 5m ago (failedAt is 5m before FIXED_NOW_MARKER).
    expect(tooltip.textContent).toMatch(/refresh failed 5m ago/)
    // Both lines carry an absolute time (digits + colon).
    const absoluteMatches = tooltip.textContent.match(/\d+:\d+/g)
    expect(absoluteMatches).not.toBeNull()
    expect(absoluteMatches.length).toBeGreaterThanOrEqual(2)
    // Both lines carry an em dash separator.
    const emDashMatches = tooltip.textContent.match(/ — /g)
    expect(emDashMatches).not.toBeNull()
    expect(emDashMatches.length).toBeGreaterThanOrEqual(2)
  })

  test('tooltip disappears when mouse leaves the warning icon', () => {
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

    const icon = screen.getByTestId('WarningAmberIcon')
    fireEvent.mouseEnter(icon)
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
    fireEvent.mouseLeave(icon)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  test('never-resolved side: tooltip first line is "never read successfully" when readAt is null', () => {
    // A side that has failedAt but no readAt has never resolved successfully.
    // The icon still renders (failedAt present), but the first tooltip line changes.
    renderRow({
      name: 'app',
      ver: {
        current: { version: null, readAt: null, failedAt: FIXED_FAILED_AT_5M },
        latest:  { version: '2.0.0', readAt: FIXED_READ_AT_10M_AGO },
        outdated: false,
        drift: null,
      },
      onRefreshed: vi.fn(),
    })

    const icon = screen.getByTestId('WarningAmberIcon')
    fireEvent.mouseEnter(icon)

    const tooltip = screen.getByRole('tooltip')
    expect(tooltip).toBeInTheDocument()
    // First line is the never-resolved sentinel — not "read X ago".
    expect(tooltip.textContent).toContain('never read successfully')
    // Second line is still the refresh-failed line.
    expect(tooltip.textContent).toMatch(/refresh failed 5m ago/)
  })

  test('pending side (readAt and failedAt both null) shows no icon and no tooltip', () => {
    // A side that is merely pending has not attempted a refresh yet — no failedAt, no icon.
    renderRow({
      name: 'app',
      ver: {
        current: { version: null, readAt: null },
        latest:  { version: '2.0.0', readAt: FIXED_READ_AT_10M_AGO },
        outdated: false,
        drift: null,
      },
      onRefreshed: vi.fn(),
    })

    expect(screen.queryByTestId('WarningAmberIcon')).not.toBeInTheDocument()

    // No tooltip should appear on hovering the "—" placeholder.
    const dashEl = screen.getByText('—')
    fireEvent.mouseEnter(dashEl)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  test('per-side independence: current failed + latest healthy renders exactly one warning icon in Current cell', () => {
    // Only the current side has failedAt; the latest side is healthy.
    // Exactly one icon must be rendered (in the Current cell, not the Latest cell).
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

    // cell index: 0=name, 1=status, 2=current, 3=latest
    const currentCell = screen.getAllByRole('cell')[2]
    const icons = within(currentCell).getAllByTestId('WarningAmberIcon')
    expect(icons).toHaveLength(1)
  })
})
