import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import ApplicationTable from './ApplicationTable'
import versionClient from './api/versionClient'

// Contract: <ApplicationTable versions={versionsObject} onRefreshed={fn} />
// versionsObject is the payload shape
// { "<name>": { current: { version, readAt }, latest: { version, readAt }, outdated, drift } }.
//
// Filter:
//   - A textbox (locator: screen.getByPlaceholderText('Filter applications…'))
//     lives in the "Application Status" card header.
//   - Typing live-filters rows by app name, case-insensitive substring match.
//   - Clearing the input restores all rows.
//
// Sort:
//   - Clicking a sortable column header (App Name / Status / Current Version /
//     Latest Version) sorts the rows by that column. Clicking the SAME header
//     again reverses the direction. Headers are queried by role 'button' (MUI
//     TableSortLabel renders an interactive element with the header text as
//     its accessible name), e.g. screen.getByRole('button', { name: /app name/i }).
//   - App Name sorts alphabetically.
//   - Status sorts by drift SEVERITY order (NONE < PATCH < MINOR < MAJOR),
//     not alphabetically.
//   - Current Version / Latest Version sort SEMVER-aware (via compareVersions
//     from drift.js), not lexicographically.
//
// Default order:
//   - On initial render (no sort header clicked yet), rows are sorted
//     most-outdated-first: MAJOR, then MINOR, then PATCH, then NONE.
//
// Footer:
//   - Text matching /\d+ applications?/ reflecting the FILTERED row count
//     (e.g. "4 applications" / "1 application").
//   - No pagination controls: no button named /next|previous/i.

vi.mock('./api/versionClient', () => ({
  __esModule: true,
  default: { scrapeApplication: vi.fn() },
}))

beforeEach(() => {
  vi.clearAllMocks()
})

const READ_AT = '2026-07-01T10:00:00Z'
const side = (version) => ({ version, readAt: READ_AT })
const pendingSide = () => ({ version: null, readAt: null })

const versions = {
  'git-tea': { current: side('1.21.7'), latest: side('1.22.1'), outdated: true, drift: 'MINOR' },
  'argo-cd': { current: side('2.10.7'), latest: side('2.11.7'), outdated: true, drift: 'PATCH' },
  sharry: { current: side('1.14.0'), latest: side('1.14.0'), outdated: false, drift: 'NONE' },
}

function rowNames() {
  const rows = screen.getAllByRole('row').filter((row) => row.querySelector('td'))
  return rows.map((row) => row.querySelector('td').textContent)
}

test('renders one row per application in the versions object', () => {
  render(<ApplicationTable versions={versions} onRefreshed={vi.fn()} />)

  expect(screen.getByText('git-tea')).toBeInTheDocument()
  expect(screen.getByText('argo-cd')).toBeInTheDocument()
  expect(screen.getByText('sharry')).toBeInTheDocument()
})

test('default render order is most-outdated-first by drift severity (MAJOR > MINOR > PATCH > NONE)', () => {
  const mixed = {
    'zeta-none': { current: side('1.0.0'), latest: side('1.0.0'), outdated: false, drift: 'NONE' },
    'beta-patch': { current: side('1.0.0'), latest: side('1.0.1'), outdated: true, drift: 'PATCH' },
    'omega-minor': { current: side('1.0.0'), latest: side('1.2.0'), outdated: true, drift: 'MINOR' },
    'alpha-major': { current: side('1.0.0'), latest: side('2.0.0'), outdated: true, drift: 'MAJOR' },
  }

  render(<ApplicationTable versions={mixed} onRefreshed={vi.fn()} />)

  const names = rowNames()
  expect(names[0]).toContain('alpha-major')
  expect(names[1]).toContain('omega-minor')
  expect(names[2]).toContain('beta-patch')
  expect(names[3]).toContain('zeta-none')
})

test('threads onRefreshed down to a row so its rescrape buttons trigger a refetch', async () => {
  versionClient.scrapeApplication.mockResolvedValue({})
  const onRefreshed = vi.fn().mockResolvedValue(undefined)
  const user = userEvent.setup()

  render(<ApplicationTable versions={{ 'git-tea': versions['git-tea'] }} onRefreshed={onRefreshed} />)

  await user.click(screen.getByRole('button', { name: /rescrape current/i }))

  await waitFor(() => {
    expect(onRefreshed).toHaveBeenCalledTimes(1)
  })
})

describe('filter', () => {
  test('typing a substring narrows visible rows by app name, case-insensitive', async () => {
    const user = userEvent.setup()
    render(<ApplicationTable versions={versions} onRefreshed={vi.fn()} />)

    const filterInput = screen.getByPlaceholderText('Filter applications…')
    await user.type(filterInput, 'GIT')

    expect(screen.getByText('git-tea')).toBeInTheDocument()
    expect(screen.queryByText('argo-cd')).not.toBeInTheDocument()
    expect(screen.queryByText('sharry')).not.toBeInTheDocument()
  })

  test('clearing the filter input restores all rows', async () => {
    const user = userEvent.setup()
    render(<ApplicationTable versions={versions} onRefreshed={vi.fn()} />)

    const filterInput = screen.getByPlaceholderText('Filter applications…')
    await user.type(filterInput, 'git')
    expect(screen.queryByText('argo-cd')).not.toBeInTheDocument()

    await user.clear(filterInput)

    expect(screen.getByText('git-tea')).toBeInTheDocument()
    expect(screen.getByText('argo-cd')).toBeInTheDocument()
    expect(screen.getByText('sharry')).toBeInTheDocument()
  })
})

describe('sort', () => {
  test('clicking the App Name header sorts alphabetically, clicking again reverses', async () => {
    const user = userEvent.setup()
    render(<ApplicationTable versions={versions} onRefreshed={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: /app name/i }))

    let names = rowNames()
    expect(names[0]).toContain('argo-cd')
    expect(names[1]).toContain('git-tea')
    expect(names[2]).toContain('sharry')

    await user.click(screen.getByRole('button', { name: /app name/i }))

    names = rowNames()
    expect(names[0]).toContain('sharry')
    expect(names[1]).toContain('git-tea')
    expect(names[2]).toContain('argo-cd')
  })

  test('clicking the Status header sorts by SEVERITY order, not alphabetically', async () => {
    // Alphabetical name order differs from severity order here:
    // 'app-a' (MAJOR) comes alphabetically first but should sort LAST in
    // ascending severity; 'app-z' (NONE) comes alphabetically last but
    // should sort FIRST in ascending severity.
    const mixed = {
      'app-a': { current: side('1.0.0'), latest: side('2.0.0'), outdated: true, drift: 'MAJOR' },
      'app-m': { current: side('1.0.0'), latest: side('1.1.0'), outdated: true, drift: 'MINOR' },
      'app-p': { current: side('1.0.0'), latest: side('1.0.1'), outdated: true, drift: 'PATCH' },
      'app-z': { current: side('1.0.0'), latest: side('1.0.0'), outdated: false, drift: 'NONE' },
    }
    const user = userEvent.setup()
    render(<ApplicationTable versions={mixed} onRefreshed={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: /status/i }))

    let names = rowNames()
    expect(names[0]).toContain('app-z') // NONE
    expect(names[1]).toContain('app-p') // PATCH
    expect(names[2]).toContain('app-m') // MINOR
    expect(names[3]).toContain('app-a') // MAJOR

    await user.click(screen.getByRole('button', { name: /status/i }))

    names = rowNames()
    expect(names[0]).toContain('app-a') // MAJOR
    expect(names[1]).toContain('app-m') // MINOR
    expect(names[2]).toContain('app-p') // PATCH
    expect(names[3]).toContain('app-z') // NONE
  })

  test('clicking the Current Version header sorts SEMVER-aware, not lexicographically', async () => {
    // Lexicographic order would put '2.10.0' before '2.9.0' (since '1' < '9'
    // as characters); semver-aware order must put 2.9.0 before 2.10.0.
    const mixed = {
      'app-high': { current: side('2.10.0'), latest: side('2.10.0'), outdated: false, drift: 'NONE' },
      'app-low': { current: side('2.9.0'), latest: side('2.9.0'), outdated: false, drift: 'NONE' },
    }
    const user = userEvent.setup()
    render(<ApplicationTable versions={mixed} onRefreshed={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: /current version/i }))

    let names = rowNames()
    expect(names[0]).toContain('app-low') // 2.9.0
    expect(names[1]).toContain('app-high') // 2.10.0

    await user.click(screen.getByRole('button', { name: /current version/i }))

    names = rowNames()
    expect(names[0]).toContain('app-high') // 2.10.0
    expect(names[1]).toContain('app-low') // 2.9.0
  })
})

// --- Issue 03: Unknown (Unresolved) app sort behaviour ------------------------------------
//
// Default status sort (desc): Unknown ranks ABOVE MAJOR (at the very top of the list).
// Version-column sort: null/missing versions sink to the BOTTOM in BOTH directions
// (ascending and descending) — missing is not "oldest".

describe('Unknown (Unresolved) sort behaviour', () => {
  test('default sort: Unknown app appears above MAJOR (at the very top)', () => {
    const mixed = {
      'alpha-major': { current: side('1.0.0'), latest: side('2.0.0'), outdated: true, drift: 'MAJOR', resolution: 'Resolved' },
      'beta-unknown': { current: pendingSide(), latest: pendingSide(), outdated: false, drift: null, resolution: 'Unresolved' },
      'gamma-none':  { current: side('1.0.0'), latest: side('1.0.0'), outdated: false, drift: 'NONE', resolution: 'Resolved' },
    }

    render(<ApplicationTable versions={mixed} onRefreshed={vi.fn()} />)

    const names = rowNames()
    // Unknown must come before MAJOR in the default desc sort.
    const unknownIdx = names.findIndex((n) => n.includes('beta-unknown'))
    const majorIdx   = names.findIndex((n) => n.includes('alpha-major'))
    expect(unknownIdx).toBeLessThan(majorIdx)
    expect(names[0]).toContain('beta-unknown')
  })

  test('default sort: all Unknown apps appear before all Resolved apps', () => {
    const mixed = {
      'a-major': { current: side('1.0.0'), latest: side('2.0.0'), outdated: true, drift: 'MAJOR', resolution: 'Resolved' },
      'b-unknown-1': { current: pendingSide(), latest: pendingSide(), drift: null, resolution: 'Unresolved' },
      'c-minor': { current: side('1.0.0'), latest: side('1.2.0'), outdated: true, drift: 'MINOR', resolution: 'Resolved' },
      'd-unknown-2': { current: pendingSide(), latest: pendingSide(), drift: null, resolution: 'Unresolved' },
    }

    render(<ApplicationTable versions={mixed} onRefreshed={vi.fn()} />)

    const names = rowNames()
    // Both unknown apps must come before both resolved apps.
    const unknownIndices = [
      names.findIndex((n) => n.includes('b-unknown-1')),
      names.findIndex((n) => n.includes('d-unknown-2')),
    ]
    const resolvedIndices = [
      names.findIndex((n) => n.includes('a-major')),
      names.findIndex((n) => n.includes('c-minor')),
    ]
    expect(Math.max(...unknownIndices)).toBeLessThan(Math.min(...resolvedIndices))
  })

  test('status sort ascending: Unknown apps appear at the BOTTOM (below NONE)', async () => {
    const mixed = {
      'a-none': { current: side('1.0.0'), latest: side('1.0.0'), outdated: false, drift: 'NONE', resolution: 'Resolved' },
      'b-major': { current: side('1.0.0'), latest: side('2.0.0'), outdated: true, drift: 'MAJOR', resolution: 'Resolved' },
      'c-unknown': { current: pendingSide(), latest: pendingSide(), drift: null, resolution: 'Unresolved' },
    }
    const user = userEvent.setup()
    render(<ApplicationTable versions={mixed} onRefreshed={vi.fn()} />)

    // Click once to activate ascending sort (first click on status toggles away from the default desc).
    await user.click(screen.getByRole('button', { name: /status/i }))

    const names = rowNames()
    const unknownIdx = names.findIndex((n) => n.includes('c-unknown'))
    expect(unknownIdx).toBe(names.length - 1)
  })

  test('version column sort ascending: null versions appear at the BOTTOM', async () => {
    // When sorting by current version ascending, apps with null version must go to the bottom.
    const mixed = {
      'a-high': { current: side('2.10.0'), latest: side('2.10.0'), drift: 'NONE', resolution: 'Resolved' },
      'b-low':  { current: side('1.0.0'),  latest: side('1.0.0'),  drift: 'NONE', resolution: 'Resolved' },
      'c-null': { current: pendingSide(),   latest: pendingSide(),  drift: null,   resolution: 'Unresolved' },
    }
    const user = userEvent.setup()
    render(<ApplicationTable versions={mixed} onRefreshed={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: /current version/i }))

    const names = rowNames()
    // Ascending: 1.0.0, 2.10.0, then null at bottom.
    expect(names[0]).toContain('b-low')
    expect(names[1]).toContain('a-high')
    expect(names[2]).toContain('c-null')
  })

  test('version column sort descending: null versions still appear at the BOTTOM', async () => {
    // Descending: 2.10.0, 1.0.0, then null at bottom — not at the "top" as if it were "oldest".
    const mixed = {
      'a-high': { current: side('2.10.0'), latest: side('2.10.0'), drift: 'NONE', resolution: 'Resolved' },
      'b-low':  { current: side('1.0.0'),  latest: side('1.0.0'),  drift: 'NONE', resolution: 'Resolved' },
      'c-null': { current: pendingSide(),   latest: pendingSide(),  drift: null,   resolution: 'Unresolved' },
    }
    const user = userEvent.setup()
    render(<ApplicationTable versions={mixed} onRefreshed={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: /current version/i }))
    await user.click(screen.getByRole('button', { name: /current version/i }))

    const names = rowNames()
    // Descending: 2.10.0, 1.0.0, null still at bottom.
    expect(names[0]).toContain('a-high')
    expect(names[1]).toContain('b-low')
    expect(names[2]).toContain('c-null')
  })

  test('latest version sort ascending: null versions appear at the BOTTOM', async () => {
    const mixed = {
      'a-high': { current: side('1.0.0'), latest: side('3.0.0'), drift: 'MAJOR', resolution: 'Resolved' },
      'b-low':  { current: side('1.0.0'), latest: side('2.0.0'), drift: 'MAJOR', resolution: 'Resolved' },
      'c-null': { current: pendingSide(),  latest: pendingSide(), drift: null,    resolution: 'Unresolved' },
    }
    const user = userEvent.setup()
    render(<ApplicationTable versions={mixed} onRefreshed={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: /latest version/i }))

    const names = rowNames()
    expect(names[names.length - 1]).toContain('c-null')
  })
})

describe('footer', () => {
  test('shows the total application count initially', () => {
    render(<ApplicationTable versions={versions} onRefreshed={vi.fn()} />)

    expect(screen.getByText(/^3 applications$/)).toBeInTheDocument()
  })

  test('reflects the FILTERED count after typing in the filter input', async () => {
    const user = userEvent.setup()
    render(<ApplicationTable versions={versions} onRefreshed={vi.fn()} />)

    await user.type(screen.getByPlaceholderText('Filter applications…'), 'git')

    expect(screen.getByText(/^1 application$/)).toBeInTheDocument()
  })

  test('has no pagination controls', () => {
    render(<ApplicationTable versions={versions} onRefreshed={vi.fn()} />)

    expect(screen.queryByRole('button', { name: /next/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /previous/i })).not.toBeInTheDocument()
  })
})
