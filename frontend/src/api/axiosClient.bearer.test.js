// Issue 03 (SPA becomes an OIDC client) — the axios request interceptor must attach
// `Authorization: Bearer <token>` on /api/v1 calls when web auth is enabled and a token is
// available, and attach nothing when web auth is disabled (the existing, un-authenticated
// behavior pinned elsewhere by axiosClient.test.js's response-interceptor tests).
//
// Per the design guidance for this slice, the interceptor is tested against OUR OWN seam
// (src/auth/userManager.js's isWebAuthEnabled/getAccessToken), fully mocked here — not against
// oidc-client-ts internals. Extraction of the handler follows the same pattern
// axiosClient.test.js already uses for the response interceptor: pull the registered function
// off axios's InterceptorManager via forEach, then invoke it directly.

vi.mock('../auth/userManager', () => ({
  isWebAuthEnabled: vi.fn(),
  getAccessToken: vi.fn(),
  userManager: null,
}))

import axiosClient from './axiosClient'
import { isWebAuthEnabled, getAccessToken } from '../auth/userManager'

let requestInterceptor

beforeAll(() => {
  axiosClient.interceptors.request.forEach((handler) => {
    if (handler && handler.fulfilled) {
      requestInterceptor = handler.fulfilled
    }
  })
})

beforeEach(() => {
  vi.clearAllMocks()
})

test('attaches Authorization: Bearer <token> when web auth is enabled and a token is available', async () => {
  isWebAuthEnabled.mockReturnValue(true)
  getAccessToken.mockResolvedValue('abc123')

  const config = await requestInterceptor({ url: '/version' })

  expect(config.headers.Authorization).toBe('Bearer abc123')
})

test('omits the Authorization header entirely when web auth is disabled', async () => {
  isWebAuthEnabled.mockReturnValue(false)

  const config = await requestInterceptor({ url: '/version' })

  expect(config.headers.Authorization).toBeUndefined()
  // Disabled means the interceptor must not even ask for a token.
  expect(getAccessToken).not.toHaveBeenCalled()
})

test('omits the Authorization header when enabled but no token is available yet', async () => {
  isWebAuthEnabled.mockReturnValue(true)
  getAccessToken.mockResolvedValue(null)

  const config = await requestInterceptor({ url: '/version' })

  expect(config.headers.Authorization).toBeUndefined()
})

test('still sets Content-Type: application/json regardless of auth state (regression)', async () => {
  isWebAuthEnabled.mockReturnValue(false)

  const config = await requestInterceptor({ url: '/version' })

  expect(config.headers['Content-Type']).toBe('application/json')
})

test('still sets Content-Type: application/json when auth is enabled and a bearer is attached', async () => {
  isWebAuthEnabled.mockReturnValue(true)
  getAccessToken.mockResolvedValue('abc123')

  const config = await requestInterceptor({ url: '/version' })

  expect(config.headers['Content-Type']).toBe('application/json')
  expect(config.headers.Authorization).toBe('Bearer abc123')
})
