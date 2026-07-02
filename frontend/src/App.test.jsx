import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import App from './App'
import versionClient from './api/versionClient'
import fakeData from './fakeData'

vi.mock('./api/versionClient', () => ({
  __esModule: true,
  default: { getVersions: vi.fn(), triggerScrape: vi.fn() },
}))

const scrapedStatus = {
  outcome: 'SCRAPED',
  appsAttempted: 3,
  appsSucceeded: 3,
  appsFailed: 0,
  triggersRemaining: 9,
  windowResetsInSeconds: 3600,
  retryAfterSeconds: 0,
}

beforeEach(() => {
  vi.clearAllMocks()
})

// ────────────────────────────────────────────────────────────────────────────
// Existing smoke test
// ────────────────────────────────────────────────────────────────────────────

test('renders the dashboard shell with the PlatformUp2Date top bar', async () => {
  versionClient.getVersions.mockResolvedValue(fakeData)

  render(<App />)

  expect(await screen.findByText(/platformup2date/i)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /refresh all/i })).toBeInTheDocument()
})

// ────────────────────────────────────────────────────────────────────────────
// First-load failure — network error (no status on the rejected error)
// ────────────────────────────────────────────────────────────────────────────

test('first-load network failure: shows Backend unavailable card with unreachable subtext', async () => {
  const networkErr = new Error('Network Error') // no .status
  versionClient.getVersions.mockRejectedValue(networkErr)

  render(<App />)

  expect(await screen.findByText(/backend unavailable/i)).toBeInTheDocument()
  expect(
    await screen.findByText(/couldn't reach the platformup2date api/i)
  ).toBeInTheDocument()
})

test('first-load network failure: does not show "Total Apps" (no fake-zero board)', async () => {
  const networkErr = new Error('Network Error')
  versionClient.getVersions.mockRejectedValue(networkErr)

  render(<App />)

  await screen.findByText(/backend unavailable/i)
  expect(screen.queryByText(/total apps/i)).not.toBeInTheDocument()
})

test('first-load network failure: TopBar remains visible (PlatformUp2Date wordmark present)', async () => {
  const networkErr = new Error('Network Error')
  versionClient.getVersions.mockRejectedValue(networkErr)

  render(<App />)

  // TopBar is always rendered; error card replaces only the body
  expect(await screen.findByText(/platformup2date/i)).toBeInTheDocument()
  expect(await screen.findByText(/backend unavailable/i)).toBeInTheDocument()
})

test('first-load network failure: a Retry button is visible on the error card', async () => {
  const networkErr = new Error('Network Error')
  versionClient.getVersions.mockRejectedValue(networkErr)

  render(<App />)

  await screen.findByText(/backend unavailable/i)
  expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
})

// ────────────────────────────────────────────────────────────────────────────
// First-load failure — HTTP error (error object carries a .status)
// (axiosClient throws err.response which has .status; App receives this)
// ────────────────────────────────────────────────────────────────────────────

test('first-load HTTP 500 failure: shows Backend unavailable card with status in subtext', async () => {
  const httpErr = { status: 500, data: 'Internal Server Error' }
  versionClient.getVersions.mockRejectedValue(httpErr)

  render(<App />)

  expect(await screen.findByText(/backend unavailable/i)).toBeInTheDocument()
  // Subtext must include the HTTP status number
  expect(await screen.findByText(/500/)).toBeInTheDocument()
})

test('first-load HTTP 500 failure: does not show "Total Apps"', async () => {
  const httpErr = { status: 500, data: 'Internal Server Error' }
  versionClient.getVersions.mockRejectedValue(httpErr)

  render(<App />)

  await screen.findByText(/backend unavailable/i)
  expect(screen.queryByText(/total apps/i)).not.toBeInTheDocument()
})

// ────────────────────────────────────────────────────────────────────────────
// Retry button — re-calls getVersions; on success replaces card with board
// ────────────────────────────────────────────────────────────────────────────

test('clicking Retry re-calls getVersions and, on success, replaces the error card with the board', async () => {
  const user = userEvent.setup()
  const networkErr = new Error('Network Error')

  // First call fails, second call (after Retry) succeeds
  versionClient.getVersions
    .mockRejectedValueOnce(networkErr)
    .mockResolvedValueOnce(fakeData)

  render(<App />)

  // Error card appears
  const retryButton = await screen.findByRole('button', { name: /retry/i })
  expect(screen.getByText(/backend unavailable/i)).toBeInTheDocument()

  // Click Retry
  await user.click(retryButton)

  // Board replaces the error card
  expect(await screen.findByText(/total apps/i)).toBeInTheDocument()
  expect(screen.queryByText(/backend unavailable/i)).not.toBeInTheDocument()
})

test('clicking Retry calls getVersions exactly twice (initial + retry)', async () => {
  const user = userEvent.setup()
  const networkErr = new Error('Network Error')

  versionClient.getVersions
    .mockRejectedValueOnce(networkErr)
    .mockResolvedValueOnce(fakeData)

  render(<App />)

  await user.click(await screen.findByRole('button', { name: /retry/i }))
  await screen.findByText(/total apps/i)

  expect(versionClient.getVersions).toHaveBeenCalledTimes(2)
})

// ────────────────────────────────────────────────────────────────────────────
// First-load spinner — slice 02
// While the very first GET /version is still in flight the body must show a
// centered CircularProgress instead of the fake-zero board.
// ────────────────────────────────────────────────────────────────────────────

test('first-load pending: body shows a spinner (progressbar role)', () => {
  // Promise that never resolves — simulates an in-flight fetch that has not
  // yet returned so the component stays in the 'loading' phase.
  versionClient.getVersions.mockReturnValue(new Promise(() => {}))

  render(<App />)

  // The spinner must be present immediately (synchronously after render)
  expect(screen.getByRole('progressbar')).toBeInTheDocument()
})

test('first-load pending: "Total Apps" is not rendered while fetch is in flight', () => {
  versionClient.getVersions.mockReturnValue(new Promise(() => {}))

  render(<App />)

  expect(screen.queryByText(/total apps/i)).not.toBeInTheDocument()
})

test('first-load pending: "0 applications" is not rendered while fetch is in flight', () => {
  versionClient.getVersions.mockReturnValue(new Promise(() => {}))

  render(<App />)

  expect(screen.queryByText(/0 applications/i)).not.toBeInTheDocument()
})

test('first-load pending: TopBar remains visible while fetch is in flight', async () => {
  versionClient.getVersions.mockReturnValue(new Promise(() => {}))

  render(<App />)

  expect(await screen.findByText(/platformup2date/i)).toBeInTheDocument()
})

test('first-load success: spinner is gone and board is visible after fetch resolves', async () => {
  // Deferred promise: lets us assert the spinner state before resolving
  let resolve
  versionClient.getVersions.mockReturnValue(new Promise((res) => { resolve = res }))

  render(<App />)

  // Spinner present before resolution
  expect(screen.getByRole('progressbar')).toBeInTheDocument()

  // Resolve the fetch
  resolve(fakeData)

  // Board appears; spinner disappears
  expect(await screen.findByText(/total apps/i)).toBeInTheDocument()
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
})

test('post-load refresh: full-body spinner does not reappear during a refresh', async () => {
  const user = userEvent.setup()

  // First load succeeds; second call (triggered by Refresh All) also succeeds
  versionClient.getVersions
    .mockResolvedValueOnce(fakeData)
    .mockReturnValueOnce(new Promise(() => {})) // hangs so we can assert mid-flight

  versionClient.triggerScrape.mockResolvedValue(scrapedStatus)

  render(<App />)

  // Wait for the initial board to load
  await screen.findByText(/total apps/i)

  // Trigger a refresh via the TopBar button
  await user.click(screen.getByRole('button', { name: /refresh all/i }))

  // The board must stay on screen; no full-body spinner should appear
  expect(screen.getByText(/total apps/i)).toBeInTheDocument()
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
})

// ────────────────────────────────────────────────────────────────────────────
// Refresh failure after successful first load
// — must NOT blank the board or show the error card
// ────────────────────────────────────────────────────────────────────────────

test('refresh failure after first successful load: board stays visible, no error card', async () => {
  const user = userEvent.setup()
  const networkErr = new Error('Network Error')

  // First getVersions call succeeds (board loads)
  // Second call (triggered by Refresh All → onRefreshed) fails
  versionClient.getVersions
    .mockResolvedValueOnce(fakeData)
    .mockRejectedValueOnce(networkErr)

  // triggerScrape needs to succeed so TopBar calls onRefreshed
  versionClient.triggerScrape.mockResolvedValue(scrapedStatus)

  render(<App />)

  // Wait for initial board to load
  await screen.findByText(/total apps/i)

  // Trigger refresh via TopBar "Refresh All" button
  await user.click(screen.getByRole('button', { name: /refresh all/i }))

  // Wait for the refresh to settle (the second getVersions call rejects)
  await waitFor(() => {
    expect(versionClient.getVersions).toHaveBeenCalledTimes(2)
  })

  // Board must still be visible; error card must NOT appear
  expect(screen.getByText(/total apps/i)).toBeInTheDocument()
  expect(screen.queryByText(/backend unavailable/i)).not.toBeInTheDocument()
})

// ────────────────────────────────────────────────────────────────────────────
// Refresh-failure banner — slice 03
// After a successful first load, a failed refresh must show a persistent error
// banner above the summary cards. The board must stay intact. The banner is
// dismissible and cleared by a subsequent successful fetch.
// ────────────────────────────────────────────────────────────────────────────

test('refresh-failure (network error): error banner shows unreachable wording and last-data note', async () => {
  const user = userEvent.setup()
  const networkErr = new Error('Network Error')

  versionClient.getVersions
    .mockResolvedValueOnce(fakeData)
    .mockRejectedValueOnce(networkErr)

  versionClient.triggerScrape.mockResolvedValue(scrapedStatus)

  render(<App />)

  await screen.findByText(/total apps/i)
  await user.click(screen.getByRole('button', { name: /refresh all/i }))

  // Wait for the second getVersions call to settle
  await waitFor(() => expect(versionClient.getVersions).toHaveBeenCalledTimes(2))

  // Banner must be present with correct wording
  // failureKind returns "Couldn't reach the PlatformUp2Date API" for network errors
  expect(await screen.findByText(/showing last loaded data/i)).toBeInTheDocument()
  expect(screen.getByText(/couldn't reach the platformup2date api/i)).toBeInTheDocument()

  // Board must still be visible (SummaryCards rendered)
  expect(screen.getByText(/total apps/i)).toBeInTheDocument()

  // Full-body error card (BackendUnavailable with Retry) must NOT appear
  expect(screen.queryByRole('button', { name: /retry/i })).not.toBeInTheDocument()
})

test('refresh-failure (HTTP 503): error banner names the HTTP status', async () => {
  const user = userEvent.setup()
  const httpErr = { status: 503, data: 'Service Unavailable' }

  versionClient.getVersions
    .mockResolvedValueOnce(fakeData)
    .mockRejectedValueOnce(httpErr)

  versionClient.triggerScrape.mockResolvedValue(scrapedStatus)

  render(<App />)

  await screen.findByText(/total apps/i)
  await user.click(screen.getByRole('button', { name: /refresh all/i }))

  await waitFor(() => expect(versionClient.getVersions).toHaveBeenCalledTimes(2))

  // Banner must name the HTTP status
  // failureKind returns "API error: received HTTP 503" for api-error kind
  expect(await screen.findByText(/showing last loaded data/i)).toBeInTheDocument()
  expect(screen.getByText(/503/)).toBeInTheDocument()

  // Board still intact
  expect(screen.getByText(/total apps/i)).toBeInTheDocument()
})

test('refresh-failure banner is dismissible; board data is retained after dismiss', async () => {
  const user = userEvent.setup()
  const networkErr = new Error('Network Error')

  versionClient.getVersions
    .mockResolvedValueOnce(fakeData)
    .mockRejectedValueOnce(networkErr)

  versionClient.triggerScrape.mockResolvedValue(scrapedStatus)

  render(<App />)

  await screen.findByText(/total apps/i)
  await user.click(screen.getByRole('button', { name: /refresh all/i }))

  await waitFor(() => expect(versionClient.getVersions).toHaveBeenCalledTimes(2))

  // Banner is visible
  const bannerText = await screen.findByText(/showing last loaded data/i)
  expect(bannerText).toBeInTheDocument()

  // Locate the Close button scoped to the error banner element (not the TopBar snackbar)
  const bannerEl = bannerText.closest('[role="alert"]')
  expect(bannerEl).not.toBeNull()
  const closeBtn = within(bannerEl).getByRole('button', { name: /close/i })
  await user.click(closeBtn)

  // Banner is gone after dismissal
  expect(screen.queryByText(/showing last loaded data/i)).not.toBeInTheDocument()

  // Board is still intact after dismissal
  expect(screen.getByText(/total apps/i)).toBeInTheDocument()
})

test('subsequent successful refresh after a failed refresh clears the error banner', async () => {
  const user = userEvent.setup()
  const networkErr = new Error('Network Error')

  versionClient.getVersions
    .mockResolvedValueOnce(fakeData)   // initial load
    .mockRejectedValueOnce(networkErr) // first refresh fails → banner
    .mockResolvedValueOnce(fakeData)   // second refresh succeeds → banner clears

  versionClient.triggerScrape.mockResolvedValue(scrapedStatus)

  render(<App />)

  // Initial load
  await screen.findByText(/total apps/i)

  // First refresh → fails → banner appears
  await user.click(screen.getByRole('button', { name: /refresh all/i }))
  await waitFor(() => expect(versionClient.getVersions).toHaveBeenCalledTimes(2))
  expect(await screen.findByText(/showing last loaded data/i)).toBeInTheDocument()

  // Second refresh → succeeds → banner gone without any explicit dismiss
  await user.click(screen.getByRole('button', { name: /refresh all/i }))
  await waitFor(() => expect(versionClient.getVersions).toHaveBeenCalledTimes(3))
  expect(screen.queryByText(/showing last loaded data/i)).not.toBeInTheDocument()

  // Board still intact after recovery
  expect(screen.getByText(/total apps/i)).toBeInTheDocument()
})
