import { render, screen, waitFor, fireEvent, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import Version from './Version'
import versionClient from './api/versionClient'

vi.mock('./api/versionClient', () => ({
  __esModule: true,
  default: { scrapeApplication: vi.fn() },
}))

beforeEach(() => {
  vi.clearAllMocks()
})

// Parses a CSS "rgb(r, g, b)" string into { r, g, b }.
const rgb = (color) => {
  const [r, g, b] = color.match(/\d+/g).map(Number)
  return { r, g, b }
}

const currentColor = (current, latest) => {
  const { unmount } = render(
    <Version name='app' ver={{ current, latest }} onRefreshed={vi.fn()} />
  )
  // When up-to-date, current and latest render the same string; the colored
  // "current" Typography is the first match in DOM order.
  const color = getComputedStyle(screen.getAllByText(current)[0]).color
  unmount()
  return color
}

test('shows the current version in green when it matches the latest', () => {
  const { r, g, b } = rgb(currentColor('1.0.0', '1.0.0'))
  expect(g).toBeGreaterThan(r)
  expect(g).toBeGreaterThan(b)
})

test('shows the current version in red when it is behind the latest', () => {
  const { r, g, b } = rgb(currentColor('1.0.0', '2.0.0'))
  expect(r).toBeGreaterThan(g)
  expect(r).toBeGreaterThan(b)
})

test('renders an "Update current" and an "Update latest" button', () => {
  render(<Version name='app' ver={{ current: '1.0.0', latest: '1.0.0' }} onRefreshed={vi.fn()} />)

  expect(screen.getByRole('button', { name: /update current/i })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /update latest/i })).toBeInTheDocument()
})

test('clicking "Update current" scrapes the (name, "current") target and refreshes on success', async () => {
  versionClient.scrapeApplication.mockResolvedValue({})
  const onRefreshed = vi.fn().mockResolvedValue(undefined)
  const user = userEvent.setup()

  render(<Version name='my-app' ver={{ current: '1.0.0', latest: '2.0.0' }} onRefreshed={onRefreshed} />)

  await user.click(screen.getByRole('button', { name: /update current/i }))

  await waitFor(() => {
    expect(versionClient.scrapeApplication).toHaveBeenCalledWith('my-app', 'current')
  })
  await waitFor(() => {
    expect(onRefreshed).toHaveBeenCalledTimes(1)
  })
})

test('clicking "Update latest" scrapes the (name, "latest") target and refreshes on success', async () => {
  versionClient.scrapeApplication.mockResolvedValue({})
  const onRefreshed = vi.fn().mockResolvedValue(undefined)
  const user = userEvent.setup()

  render(<Version name='my-app' ver={{ current: '1.0.0', latest: '2.0.0' }} onRefreshed={onRefreshed} />)

  await user.click(screen.getByRole('button', { name: /update latest/i }))

  await waitFor(() => {
    expect(versionClient.scrapeApplication).toHaveBeenCalledWith('my-app', 'latest')
  })
  await waitFor(() => {
    expect(onRefreshed).toHaveBeenCalledTimes(1)
  })
})

test('the clicked button disables while its request is in flight', async () => {
  let resolveScrape
  versionClient.scrapeApplication.mockImplementation(
    () => new Promise((resolve) => { resolveScrape = resolve })
  )
  const onRefreshed = vi.fn().mockResolvedValue(undefined)
  const user = userEvent.setup()

  render(<Version name='my-app' ver={{ current: '1.0.0', latest: '2.0.0' }} onRefreshed={onRefreshed} />)

  const currentButton = screen.getByRole('button', { name: /update current/i })
  const latestButton = screen.getByRole('button', { name: /update latest/i })

  await user.click(currentButton)

  await waitFor(() => {
    expect(currentButton).toBeDisabled()
  })
  // The other button's request is independent and stays enabled.
  expect(latestButton).toBeEnabled()

  await act(async () => {
    resolveScrape({})
  })

  await waitFor(() => {
    expect(currentButton).toBeEnabled()
  })
})

test('a 429 on "Update current" starts that button\'s cooldown and does not refresh', async () => {
  vi.useFakeTimers()
  try {
    versionClient.scrapeApplication.mockRejectedValue({
      status: 429,
      data: { retryAfterSeconds: 3 },
    })
    const onRefreshed = vi.fn()

    render(<Version name='my-app' ver={{ current: '1.0.0', latest: '2.0.0' }} onRefreshed={onRefreshed} />)

    const currentButton = screen.getByRole('button', { name: /update current/i })
    const latestButton = screen.getByRole('button', { name: /update latest/i })

    // NOTE: userEvent v14's `await user.click(...)` deadlocks under vitest v4
    // fake timers. fireEvent + an explicit microtask flush is the equivalent
    // interaction that keeps the countdown stepping deterministic.
    fireEvent.click(currentButton)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })

    expect(currentButton).toBeDisabled()
    expect(currentButton).toHaveTextContent('Retry in 3s')
    // The other button's cooldown is independent and stays unaffected.
    expect(latestButton).toBeEnabled()
    expect(latestButton).toHaveTextContent(/update latest/i)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(currentButton).toHaveTextContent('Retry in 2s')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(currentButton).toHaveTextContent('Retry in 1s')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(currentButton).toBeEnabled()
    expect(currentButton).toHaveTextContent(/update current/i)

    expect(onRefreshed).not.toHaveBeenCalled()
  } finally {
    vi.useRealTimers()
  }
})
