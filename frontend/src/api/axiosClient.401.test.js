// Contract for the response interceptor's 401 handling:
//   1. When web auth is enabled, attempt silent renewal.
//   2. On success, retry the original request exactly once through axiosClient.
//   3. On failure, start a full-page sign-in redirect and reject the interceptor promise.
// With web auth disabled, and for non-401 responses, errors continue to throw synchronously.
// Keep the handler non-async so those default branches preserve that synchronous contract; only
// the 401-with-auth branch returns a Promise.
//
// userManager is mocked here because iframe renewal and browser navigation require a system test.

vi.mock('../auth/userManager', () => ({
  isWebAuthEnabled: vi.fn(),
  getAccessToken: vi.fn(),
  userManager: {
    signinSilent: vi.fn(),
    signinRedirect: vi.fn(),
    removeUser: vi.fn(),
    signoutRedirect: vi.fn(),
  },
}))

import axiosClient from './axiosClient'
import { isWebAuthEnabled, userManager } from '../auth/userManager'

let errorInterceptor

beforeAll(() => {
  axiosClient.interceptors.response.forEach((handler) => {
    if (handler && handler.rejected) {
      errorInterceptor = handler.rejected
    }
  })
})

beforeEach(() => {
  vi.clearAllMocks()
  // Each test starts with the full-page-redirect loop guard re-armed (its sessionStorage marker
  // would otherwise leak between tests). The guard's own contract is pinned in
  // axiosClient.redirect-loop.test.js.
  window.sessionStorage.clear()
})

const httpErr401 = () => ({
  response: { status: 401, data: 'Unauthorized', headers: {} },
  config: { url: '/version', method: 'get' },
})

describe('axiosClient response interceptor — 401 with web auth DISABLED (unchanged behavior)', () => {
  test('throws err.response synchronously and never attempts a silent renew', () => {
    isWebAuthEnabled.mockReturnValue(false)
    const err = httpErr401()

    expect(() => errorInterceptor(err)).toThrow(err.response)
    expect(userManager.signinSilent).not.toHaveBeenCalled()
    expect(userManager.signinRedirect).not.toHaveBeenCalled()
  })
})

describe('axiosClient response interceptor — 401 with web auth ENABLED', () => {
  test('attempts a silent renew before doing anything else', () => {
    isWebAuthEnabled.mockReturnValue(true)
    userManager.signinSilent.mockResolvedValue({ access_token: 'renewed' })
    vi.spyOn(axiosClient, 'request').mockResolvedValue({ data: 'ok' })

    errorInterceptor(httpErr401())

    expect(userManager.signinSilent).toHaveBeenCalledTimes(1)
  })

  test('on successful renew, retries the original request via axiosClient.request(err.config) and resolves with its result', async () => {
    isWebAuthEnabled.mockReturnValue(true)
    userManager.signinSilent.mockResolvedValue({ access_token: 'renewed' })
    const requestSpy = vi.spyOn(axiosClient, 'request').mockResolvedValue({ appA: {} })

    const err = httpErr401()
    const result = await errorInterceptor(err)

    expect(requestSpy).toHaveBeenCalledWith(err.config)
    expect(result).toEqual({ appA: {} })
    expect(userManager.signinRedirect).not.toHaveBeenCalled()
  })

  test('bounds the retry to a single renew: a second consecutive 401 on the retried request redirects instead of renewing again (anti-loop)', async () => {
    isWebAuthEnabled.mockReturnValue(true)
    userManager.signinSilent.mockResolvedValue({ access_token: 'renewed' })
    // Reject the retried request with a SECOND 401, echoing back whatever config the
    // interceptor actually passed to axiosClient.request. We don't hardcode/assume any
    // private "already retried" marker name — we just relay the real call-site config.
    const requestSpy = vi.spyOn(axiosClient, 'request').mockImplementation((config) =>
      Promise.reject({ response: { status: 401, data: 'Unauthorized', headers: {} }, config })
    )

    const err = httpErr401()
    await expect(errorInterceptor(err)).rejects.toBeTruthy()

    expect(requestSpy).toHaveBeenCalledTimes(1)
    // The anti-loop contract: exactly one renew attempt total, no matter how many 401s follow.
    expect(userManager.signinSilent).toHaveBeenCalledTimes(1)
    // The second consecutive 401 forces a full-page re-auth instead of another silent renew.
    expect(userManager.signinRedirect).toHaveBeenCalled()
  })

  test('on failed renew, redirects to the IdP login page via signinRedirect', async () => {
    isWebAuthEnabled.mockReturnValue(true)
    userManager.signinSilent.mockRejectedValue(new Error('renew failed'))

    const err = httpErr401()
    await expect(errorInterceptor(err)).rejects.toBeTruthy()

    expect(userManager.signinRedirect).toHaveBeenCalledTimes(1)
  })

  test('on failed renew, does not retry the original request', async () => {
    isWebAuthEnabled.mockReturnValue(true)
    userManager.signinSilent.mockRejectedValue(new Error('renew failed'))
    const requestSpy = vi.spyOn(axiosClient, 'request').mockResolvedValue({})

    const err = httpErr401()
    try {
      await errorInterceptor(err)
    } catch {
      // expected — the promise contract stays sane even though the redirect navigates away
    }

    expect(requestSpy).not.toHaveBeenCalled()
  })
})

describe('axiosClient response interceptor — non-401 statuses unchanged when web auth is enabled (regression)', () => {
  test('500 still throws err.response synchronously without attempting a silent renew', () => {
    isWebAuthEnabled.mockReturnValue(true)
    const err = { response: { status: 500, data: 'Internal Server Error', headers: {} }, config: {} }

    expect(() => errorInterceptor(err)).toThrow(err.response)
    expect(userManager.signinSilent).not.toHaveBeenCalled()
  })

  test('403 still throws err.response synchronously (Not-authorized is handled by failureKind/Dashboard, not the interceptor)', () => {
    isWebAuthEnabled.mockReturnValue(true)
    const err = { response: { status: 403, data: 'Forbidden', headers: {} }, config: {} }

    expect(() => errorInterceptor(err)).toThrow(err.response)
    expect(userManager.signinSilent).not.toHaveBeenCalled()
  })

  test('429 still throws err.response with .status/.data intact (TopBar cooldown path)', () => {
    isWebAuthEnabled.mockReturnValue(true)
    const err = {
      response: { status: 429, data: { retryAfterSeconds: 3 }, headers: {} },
      config: {},
    }

    let thrown
    try {
      errorInterceptor(err)
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBe(err.response)
    expect(thrown.status).toBe(429)
  })

  test('network error (no err.response) still throws the original error', () => {
    isWebAuthEnabled.mockReturnValue(true)
    const networkErr = new Error('Network Error')

    expect(() => errorInterceptor(networkErr)).toThrow(networkErr)
    expect(userManager.signinSilent).not.toHaveBeenCalled()
  })
})
