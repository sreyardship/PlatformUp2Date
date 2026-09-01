// A 403 fetch error renders <NotAuthorized/> instead of <BackendUnavailable/> or the board.
// Other fetch failures still render <BackendUnavailable/>, while the loaded state is unaffected.

vi.mock('./auth/userManager', () => ({
  isWebAuthEnabled: vi.fn(() => false),
  userManager: {
    removeUser: vi.fn(),
    signoutRedirect: vi.fn(),
  },
}))

import { render, screen } from '@testing-library/react'

import Dashboard from './Dashboard'
import fakeData from './fakeData'

const notAuthorizedErr = { status: 403, data: 'Forbidden' }
const serverErr = { status: 500, data: 'Internal Server Error' }

const renderDashboard = (overrides = {}) =>
  render(
    <Dashboard
      versions={{}}
      onRefreshed={vi.fn()}
      phase="error"
      fetchError={null}
      refreshError={null}
      onDismissRefreshError={vi.fn()}
      {...overrides}
    />
  )

test('403 fetch error renders the Not authorized state, not Backend unavailable', () => {
  renderDashboard({ fetchError: notAuthorizedErr })

  expect(screen.getByText(/not authorized/i)).toBeInTheDocument()
  expect(screen.queryByText(/backend unavailable/i)).not.toBeInTheDocument()
})

test('403 fetch error does not render the empty board (no "Total Apps")', () => {
  renderDashboard({ fetchError: notAuthorizedErr })

  expect(screen.queryByText(/total apps/i)).not.toBeInTheDocument()
})

test('a non-403 error (e.g. 500) still renders Backend unavailable, not Not authorized (regression)', () => {
  renderDashboard({ fetchError: serverErr })

  expect(screen.getByText(/backend unavailable/i)).toBeInTheDocument()
  expect(screen.queryByText(/not authorized/i)).not.toBeInTheDocument()
})

test('a network error (no .status) still renders Backend unavailable (regression)', () => {
  renderDashboard({ fetchError: new Error('Network Error') })

  expect(screen.getByText(/backend unavailable/i)).toBeInTheDocument()
  expect(screen.queryByText(/not authorized/i)).not.toBeInTheDocument()
})

test('the happy path still renders the board when phase is loaded', () => {
  render(
    <Dashboard
      versions={fakeData}
      onRefreshed={vi.fn()}
      phase="loaded"
      fetchError={null}
      refreshError={null}
      onDismissRefreshError={vi.fn()}
    />
  )

  expect(screen.getByText(/total apps/i)).toBeInTheDocument()
  expect(screen.queryByText(/not authorized/i)).not.toBeInTheDocument()
})

test('Not authorized state offers no plain Retry button (retrying just hits the same 403 again)', () => {
  renderDashboard({ fetchError: notAuthorizedErr })

  expect(screen.queryByRole('button', { name: /^retry$/i })).not.toBeInTheDocument()
})
