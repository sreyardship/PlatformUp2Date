import { render, screen, waitFor, fireEvent, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import RescrapeButton from './RescrapeButton'

// Contract: <RescrapeButton label="Rescrape current" onScrape={fn} onRefreshed={fn} />
// Mirrors the existing UpdateButton contract exactly so ApplicationRow can
// reuse the cooldown logic verbatim (ported, not reinvented).

beforeEach(() => {
  vi.clearAllMocks()
})

test('renders the given label', () => {
  render(<RescrapeButton label="Rescrape current" onScrape={vi.fn()} onRefreshed={vi.fn()} />)

  expect(screen.getByRole('button', { name: /rescrape current/i })).toBeInTheDocument()
})

test('clicking calls onScrape then onRefreshed on success', async () => {
  const onScrape = vi.fn().mockResolvedValue({})
  const onRefreshed = vi.fn().mockResolvedValue(undefined)
  const user = userEvent.setup()

  render(<RescrapeButton label="Rescrape current" onScrape={onScrape} onRefreshed={onRefreshed} />)

  await user.click(screen.getByRole('button', { name: /rescrape current/i }))

  await waitFor(() => {
    expect(onScrape).toHaveBeenCalledTimes(1)
  })
  await waitFor(() => {
    expect(onRefreshed).toHaveBeenCalledTimes(1)
  })
})

test('the button disables while its request is in flight', async () => {
  let resolveScrape
  const onScrape = vi.fn(() => new Promise((resolve) => { resolveScrape = resolve }))
  const onRefreshed = vi.fn().mockResolvedValue(undefined)
  const user = userEvent.setup()

  render(<RescrapeButton label="Rescrape current" onScrape={onScrape} onRefreshed={onRefreshed} />)

  const button = screen.getByRole('button', { name: /rescrape current/i })

  await user.click(button)

  await waitFor(() => {
    expect(button).toBeDisabled()
  })

  await act(async () => {
    resolveScrape({})
  })

  await waitFor(() => {
    expect(button).toBeEnabled()
  })
})

test('a 429 starts a "Retry in Ns" countdown, disables the button, and does not refresh', async () => {
  vi.useFakeTimers()
  try {
    const onScrape = vi.fn().mockRejectedValue({
      status: 429,
      data: { retryAfterSeconds: 3 },
    })
    const onRefreshed = vi.fn()

    render(<RescrapeButton label="Rescrape current" onScrape={onScrape} onRefreshed={onRefreshed} />)

    const button = screen.getByRole('button', { name: /rescrape current/i })

    // NOTE: userEvent v14's `await user.click(...)` deadlocks under vitest v4
    // fake timers. fireEvent + an explicit microtask flush is the equivalent
    // interaction that keeps the countdown stepping deterministic.
    fireEvent.click(button)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })

    expect(button).toBeDisabled()
    expect(button).toHaveTextContent('Retry in 3s')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(button).toHaveTextContent('Retry in 2s')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(button).toHaveTextContent('Retry in 1s')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(button).toBeEnabled()
    expect(button).toHaveTextContent(/rescrape current/i)

    expect(onRefreshed).not.toHaveBeenCalled()
  } finally {
    vi.useRealTimers()
  }
})
