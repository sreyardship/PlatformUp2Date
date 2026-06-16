import { render, screen, waitFor, fireEvent, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import RefreshButton from './RefreshButton'
import versionClient from './api/versionClient'

vi.mock('./api/versionClient', () => ({
  __esModule: true,
  default: { triggerScrape: vi.fn(), getVersions: vi.fn() },
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
})

test('200/SCRAPED: clicking triggers a scrape, refetches via onRefreshed, and surfaces counts', async () => {
  versionClient.triggerScrape.mockResolvedValue(scrapedStatus)
  const onRefreshed = vi.fn().mockResolvedValue(undefined)
  const user = userEvent.setup()

  render(<RefreshButton onRefreshed={onRefreshed} />)

  await user.click(screen.getByRole('button', { name: /refresh now/i }))

  await waitFor(() => {
    expect(versionClient.triggerScrape).toHaveBeenCalledTimes(1)
  })
  await waitFor(() => {
    expect(onRefreshed).toHaveBeenCalledTimes(1)
  })
  expect(await screen.findByText('Scraped 3/3 (9 left)')).toBeInTheDocument()
})

test('429/RATE_LIMITED: button disables and counts down from retryAfterSeconds, then re-enables', async () => {
  vi.useFakeTimers()
  try {
    versionClient.triggerScrape.mockRejectedValue(rateLimitedResponse)
    const onRefreshed = vi.fn()

    render(<RefreshButton onRefreshed={onRefreshed} />)

    const button = screen.getByRole('button', { name: /refresh now/i })
    // NOTE: userEvent v14's `await user.click(...)` deadlocks under vitest v4
    // fake timers (its internal async wrapper waits on a faked timer that the
    // deterministic `advanceTimersByTimeAsync` stepping below never advances).
    // fireEvent + an explicit microtask flush is the equivalent interaction
    // that keeps the countdown stepping deterministic.
    fireEvent.click(button)
    // Settle the rejected triggerScrape promise + the catch handler's state
    // update. Each timer advance is wrapped in act() so React 19 commits the
    // interval tick's render before we assert on the DOM (otherwise the
    // rendered label trails the committed state by one async boundary).
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })

    // After the rejection settles the button is disabled and shows the countdown.
    expect(screen.getByRole('button')).toBeDisabled()
    expect(screen.getByRole('button')).toHaveTextContent('Retry in 3s')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(screen.getByRole('button')).toHaveTextContent('Retry in 2s')
    expect(screen.getByRole('button')).toBeDisabled()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(screen.getByRole('button')).toHaveTextContent('Retry in 1s')
    expect(screen.getByRole('button')).toBeDisabled()

    // At zero the button re-enables and returns to its idle label.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(screen.getByRole('button')).toBeEnabled()
    expect(screen.getByRole('button', { name: /refresh now/i })).toBeInTheDocument()

    // The 429 path must NOT refetch.
    expect(onRefreshed).not.toHaveBeenCalled()
  } finally {
    vi.useRealTimers()
  }
})
