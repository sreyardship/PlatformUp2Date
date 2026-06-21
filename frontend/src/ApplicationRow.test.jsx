import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import ApplicationRow from './ApplicationRow'
import versionClient from './api/versionClient'

// Contract: <ApplicationRow name="app" ver={{ current, latest, outdated, drift }} onRefreshed={fn} />
// Renders a single <tr> (must be mounted inside <table><tbody>...) with:
//   - App Name cell: avatar initial + name
//   - Status cell: a drift badge with a status label (NONE/PATCH/MINOR/MAJOR
//     -> "Up to Date"/"Patch Available"/"Minor Available"/"Major Available")
//   - Current Version / Latest Version cells (latest emphasised when outdated)
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

const baseVer = (overrides) => ({
  current: '1.0.0',
  latest: '1.0.0',
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
    renderRow({ name: 'app', ver: baseVer({ drift: 'PATCH', outdated: true, latest: '1.0.1' }), onRefreshed: vi.fn() })
    expect(screen.getByText(/patch available/i)).toBeInTheDocument()
  })

  test('drift MINOR shows "Minor Available"', () => {
    renderRow({ name: 'app', ver: baseVer({ drift: 'MINOR', outdated: true, latest: '1.2.0' }), onRefreshed: vi.fn() })
    expect(screen.getByText(/minor available/i)).toBeInTheDocument()
  })

  test('drift MAJOR shows "Major Available"', () => {
    renderRow({ name: 'app', ver: baseVer({ drift: 'MAJOR', outdated: true, latest: '2.0.0' }), onRefreshed: vi.fn() })
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
    ver: baseVer({ current: '1.21.7', latest: '1.22.1', outdated: true, drift: 'MINOR' }),
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
    ver: baseVer({ current: '0.9.0', latest: '1.0.0', outdated: false, drift: 'NONE' }),
    onRefreshed: vi.fn(),
  })
  const upToDateColor = getComputedStyle(screen.getByText('1.0.0')).color
  unmountUpToDate()

  const { unmount: unmountOutdated } = renderRow({
    name: 'app',
    ver: baseVer({ current: '1.0.0', latest: '2.0.0', outdated: true, drift: 'MAJOR' }),
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
