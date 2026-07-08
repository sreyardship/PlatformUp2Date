// Issue 04 (SPA authorization UX) — the Not-authorized surface.
//
// Renders when the backend answers a data request with 403: the caller's token validated but
// lacks the web Surface's role (pu2d-web) — authenticated but not entitled. Sibling to
// BackendUnavailable.jsx, but a DISTINCT state (see CONTEXT.md's "Not authorized" definition):
// never lumped in with "backend didn't answer", never an empty fleet, and — because retrying the
// exact same request just gets another 403 — no plain no-op "Retry" button. The pinned action
// here is "Log out" (RP-initiated logout, so the user can switch to an entitled account), backed
// by the same userManager seam TopBar's logout control uses.
//
// This component does not exist yet — module-resolution failure here is the expected RED state
// for this slice; the implementer creates ./NotAuthorized.jsx.

vi.mock('./auth/userManager', () => ({
  isWebAuthEnabled: vi.fn(() => true),
  userManager: {
    removeUser: vi.fn(),
    signoutRedirect: vi.fn(),
  },
}))

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import NotAuthorized from './NotAuthorized'
import { userManager } from './auth/userManager'

beforeEach(() => {
  vi.clearAllMocks()
})

test('renders a "Not authorized" title', () => {
  render(<NotAuthorized />)

  expect(screen.getByText(/not authorized/i)).toBeInTheDocument()
})

test('body copy explains the account is authenticated but not entitled — pinned wording', () => {
  render(<NotAuthorized />)

  // Matches the exact issue framing: "your account isn't authorized for this app"
  expect(screen.getByText(/your account isn't authorized for this app/i)).toBeInTheDocument()
})

test('is distinct from Backend unavailable — does not use its copy or heading', () => {
  render(<NotAuthorized />)

  expect(screen.queryByText(/backend unavailable/i)).not.toBeInTheDocument()
  expect(screen.queryByText(/couldn't reach the platformup2date api/i)).not.toBeInTheDocument()
})

test('avoids the disallowed HTTP-jargon words per CONTEXT.md (forbidden, unauthenticated, no access)', () => {
  render(<NotAuthorized />)

  const bodyText = document.body.textContent.toLowerCase()
  expect(bodyText).not.toMatch(/forbidden/)
  expect(bodyText).not.toMatch(/unauthenticated/)
  expect(bodyText).not.toMatch(/no access/)
})

test('does NOT offer a plain "Retry" button (retrying hits the same 403 again)', () => {
  render(<NotAuthorized />)

  expect(screen.queryByRole('button', { name: /^retry$/i })).not.toBeInTheDocument()
})

test('offers a "Log out" action instead', () => {
  render(<NotAuthorized />)

  expect(screen.getByRole('button', { name: /log out/i })).toBeInTheDocument()
})

test('clicking "Log out" clears the in-memory user and redirects to the IdP end-session endpoint', async () => {
  const user = userEvent.setup()
  render(<NotAuthorized />)

  await user.click(screen.getByRole('button', { name: /log out/i }))

  expect(userManager.removeUser).toHaveBeenCalledTimes(1)
  expect(userManager.signoutRedirect).toHaveBeenCalledTimes(1)
})
