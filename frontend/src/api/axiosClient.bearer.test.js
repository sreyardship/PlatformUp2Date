// The request interceptor attaches `Authorization: Bearer <token>` when web auth is enabled and
// a token is available, and attaches nothing when web auth is disabled. The test mocks the
// isWebAuthEnabled/getAccessToken application seam rather than oidc-client-ts internals. Handler
// extraction follows the same pattern as axiosClient.test.js for the response interceptor: pull the registered function
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
