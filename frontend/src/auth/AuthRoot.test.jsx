// Issue 03 (SPA becomes an OIDC client) — composition-root gating.
//
// `AuthRoot` is the seam extracted from src/index.jsx so this behavior is unit-testable without
// driving index.jsx's real ReactDOM.createRoot(...).render(...) side effect (which needs a live
// #root DOM node and has no meaningful "render output" to assert against in jsdom). The
// implementer wires src/index.jsx to render <AuthRoot /> in place of the current <App /> — see
// this repo's tester report for the exact expected shape.
//
// Contract:
//   - web auth DISABLED (isWebAuthEnabled() false): renders <App/> directly. No <AuthProvider>,
//     no react-oidc-context useAuth() call, no redirect — exactly today's behavior.
//   - web auth ENABLED + unauthenticated: does NOT render the board; triggers signinRedirect()
//     exactly once (the full-page redirect to the IdP).
//   - web auth ENABLED + still loading (mid-flow, e.g. processing the redirect callback): does
//     NOT render the board and does NOT redirect again.
//   - web auth ENABLED + authenticated: renders <App/> (the board).
//
// What this test deliberately does NOT attempt: driving a real IdP redirect round-trip in jsdom.
// react-oidc-context's useAuth/AuthProvider are mocked — we assert OUR wiring calls the right
// hooks with the right outcome, not the library's internals. The real full-page redirect is a
// system-test-only concern, covered manually (see tester report).

vi.mock('../auth/userManager', () => ({
  isWebAuthEnabled: vi.fn(),
  getAccessToken: vi.fn(),
  userManager: null,
}))

vi.mock('react-oidc-context', () => ({
  AuthProvider: ({ children }) => <div data-testid="auth-provider">{children}</div>,
  useAuth: vi.fn(),
}))

vi.mock('../App', () => ({
  __esModule: true,
  default: () => <div data-testid="board">BOARD</div>,
}))

import { render, screen } from '@testing-library/react'
import { useAuth } from 'react-oidc-context'
import { isWebAuthEnabled } from './userManager'
import AuthRoot from './AuthRoot'

beforeEach(() => {
  vi.clearAllMocks()
})

test('web auth disabled: renders the board directly, no provider, no useAuth call', () => {
  isWebAuthEnabled.mockReturnValue(false)

  render(<AuthRoot />)

  expect(screen.getByTestId('board')).toBeInTheDocument()
  expect(screen.queryByTestId('auth-provider')).not.toBeInTheDocument()
  expect(useAuth).not.toHaveBeenCalled()
})

test('web auth enabled + unauthenticated: does not render the board and triggers signinRedirect exactly once', () => {
  isWebAuthEnabled.mockReturnValue(true)
  const signinRedirect = vi.fn()
  useAuth.mockReturnValue({
    isAuthenticated: false,
    isLoading: false,
    signinRedirect,
  })

  render(<AuthRoot />)

  expect(screen.queryByTestId('board')).not.toBeInTheDocument()
  expect(signinRedirect).toHaveBeenCalledTimes(1)
})

test('web auth enabled + still loading: does not render the board and does not redirect yet', () => {
  isWebAuthEnabled.mockReturnValue(true)
  const signinRedirect = vi.fn()
  useAuth.mockReturnValue({
    isAuthenticated: false,
    isLoading: true,
    signinRedirect,
  })

  render(<AuthRoot />)

  expect(screen.queryByTestId('board')).not.toBeInTheDocument()
  expect(signinRedirect).not.toHaveBeenCalled()
})

test('web auth enabled + authenticated: renders the board and does not redirect', () => {
  isWebAuthEnabled.mockReturnValue(true)
  const signinRedirect = vi.fn()
  useAuth.mockReturnValue({
    isAuthenticated: true,
    isLoading: false,
    signinRedirect,
  })

  render(<AuthRoot />)

  expect(screen.getByTestId('board')).toBeInTheDocument()
  expect(signinRedirect).not.toHaveBeenCalled()
})

test('web auth enabled: the board renders inside the AuthProvider wrapper', () => {
  isWebAuthEnabled.mockReturnValue(true)
  useAuth.mockReturnValue({
    isAuthenticated: true,
    isLoading: false,
    signinRedirect: vi.fn(),
  })

  render(<AuthRoot />)

  const provider = screen.getByTestId('auth-provider')
  expect(provider).toBeInTheDocument()
  expect(screen.getByTestId('board')).toBeInTheDocument()
})
