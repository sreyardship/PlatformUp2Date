// Issue 04 (SPA authorization UX) — Dashboard's Not-authorized branch.
//
// A 403 fetch error (failureKind kind === 'not-authorized') must render <NotAuthorized/> INSTEAD
// of <BackendUnavailable/> and INSTEAD of the board — never an empty fleet. Any other fetch error
// keeps rendering <BackendUnavailable/> exactly as before (regression coverage). The happy path
// (phase === 'loaded') is unaffected.
//
// NotAuthorized.jsx does not exist yet (see NotAuthorized.test.jsx) and Dashboard.jsx does not
// yet branch on it — these assertions are the expected RED for this slice.

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
