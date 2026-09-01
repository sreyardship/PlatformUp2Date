// A 403 means the caller is authenticated but lacks the web role. This state stays distinct from
// backend unavailability and offers logout, rather than retry, so the user can switch accounts.

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

  // Keep the message specific to account authorization.
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
