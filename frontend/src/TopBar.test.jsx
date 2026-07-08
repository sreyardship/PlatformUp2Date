import { render, screen, waitFor, fireEvent, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import TopBar from './TopBar'
import versionClient from './api/versionClient'
import { isWebAuthEnabled, userManager } from './auth/userManager'

vi.mock('./api/versionClient', () => ({
  __esModule: true,
  default: { triggerScrape: vi.fn(), getVersions: vi.fn() },
}))

// Issue 04 (SPA authorization UX) — the logout control.
// Shown ONLY when web auth is enabled (isWebAuthEnabled()); absent otherwise (pinned both ways).
// onClick performs RP-initiated logout: clears the in-memory user (removeUser()) AND redirects to
// the IdP end-session endpoint (signoutRedirect()). The real IdP end-session round-trip is not
// unit-testable — userManager is fully mocked here; that round-trip is a manual system test.
vi.mock('./auth/userManager', () => ({
  isWebAuthEnabled: vi.fn(),
  userManager: {
    removeUser: vi.fn(),
    signoutRedirect: vi.fn(),
  },
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

const rateLimitedResponse = {
  status: 429,
  data: {
    outcome: 'RATE_LIMITED',
    appsAttempted: 0,
    appsSucceeded: 0,
    appsFailed: 0,
    triggersRemaining: 0,
    windowResetsInSeconds: 3,
    retryAfterSeconds: 3,
  },
  headers: { 'retry-after': '3' },
}

beforeEach(() => {
  vi.clearAllMocks()
  // Default to disabled so the pre-existing tests below (which never touch auth) keep behaving
  // exactly as before this slice.
  isWebAuthEnabled.mockReturnValue(false)
})

test('renders the PlatformUp2Date wordmark, logo, and a Refresh All button', () => {
  render(<TopBar onRefreshed={vi.fn()} />)

  expect(screen.getByText(/platformup2date/i)).toBeInTheDocument()
  expect(screen.getByRole('img')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /refresh all/i })).toBeInTheDocument()
})

test('200/SCRAPED: clicking Refresh All triggers a scrape, refetches via onRefreshed, and surfaces an outcome Snackbar', async () => {
  versionClient.triggerScrape.mockResolvedValue(scrapedStatus)
  const onRefreshed = vi.fn().mockResolvedValue(undefined)
  const user = userEvent.setup()

  render(<TopBar onRefreshed={onRefreshed} />)

  await user.click(screen.getByRole('button', { name: /refresh all/i }))

  await waitFor(() => {
    expect(versionClient.triggerScrape).toHaveBeenCalledTimes(1)
  })
  await waitFor(() => {
    expect(onRefreshed).toHaveBeenCalledTimes(1)
  })

  expect(await screen.findByText(/scraped 3\/3/i)).toBeInTheDocument()
  expect(await screen.findByText(/9 left/i)).toBeInTheDocument()
})

test('429/RATE_LIMITED: button disables and counts down from retryAfterSeconds, then re-enables, without refetching', async () => {
  vi.useFakeTimers()
  try {
    versionClient.triggerScrape.mockRejectedValue(rateLimitedResponse)
    const onRefreshed = vi.fn()

    render(<TopBar onRefreshed={onRefreshed} />)

    const button = screen.getByRole('button', { name: /refresh all/i })
    // NOTE: userEvent v14's `await user.click(...)` deadlocks under vitest v4
    // fake timers. fireEvent + an explicit microtask flush is the equivalent
    // interaction that keeps the countdown stepping deterministic. See
    // RefreshButton.test.jsx for the original pattern this ports from.
    fireEvent.click(button)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })

    // After the rejection settles the button is disabled and shows the countdown.
    expect(screen.getByRole('button', { name: /refresh all|retry in/i })).toBeDisabled()
    expect(screen.getByText(/retry in 3s/i)).toBeInTheDocument()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(screen.getByText(/retry in 2s/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /retry in/i })).toBeDisabled()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(screen.getByText(/retry in 1s/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /retry in/i })).toBeDisabled()

    // At zero the button re-enables and returns to its idle label.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(screen.getByRole('button', { name: /refresh all/i })).toBeEnabled()

    // The 429 path must NOT refetch.
    expect(onRefreshed).not.toHaveBeenCalled()
  } finally {
    vi.useRealTimers()
  }
})

// ────────────────────────────────────────────────────────────────────────────
// Logout control — slice 04
// ────────────────────────────────────────────────────────────────────────────

test('no logout control is rendered when web auth is disabled', () => {
  isWebAuthEnabled.mockReturnValue(false)

  render(<TopBar onRefreshed={vi.fn()} />)

  expect(screen.queryByRole('button', { name: /log out/i })).not.toBeInTheDocument()
})

test('a logout control is rendered when web auth is enabled', () => {
  isWebAuthEnabled.mockReturnValue(true)

  render(<TopBar onRefreshed={vi.fn()} />)

  expect(screen.getByRole('button', { name: /log out/i })).toBeInTheDocument()
})

test('clicking Log out clears the in-memory user and redirects to the IdP end-session endpoint (RP-initiated logout)', async () => {
  isWebAuthEnabled.mockReturnValue(true)
  const user = userEvent.setup()

  render(<TopBar onRefreshed={vi.fn()} />)

  await user.click(screen.getByRole('button', { name: /log out/i }))

  expect(userManager.removeUser).toHaveBeenCalledTimes(1)
  expect(userManager.signoutRedirect).toHaveBeenCalledTimes(1)
})
