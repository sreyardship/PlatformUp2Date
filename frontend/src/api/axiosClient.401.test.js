// Issue 04 (SPA authorization UX — expiry, denial, logout) — the 401 branch of the axios
// response interceptor.
//
// CONTRACT PINNED FOR THIS SLICE:
//   On a 401 (missing/expired/invalid token) AND isWebAuthEnabled():
//     1. Attempt a silent renew via userManager.signinSilent().
//     2. If it RESOLVES: retry the original request exactly once via
//        axiosClient.request(err.config) and resolve the interceptor's promise with that
//        result. The caller (versionClient → App.fetchVersionData) never sees the 401 —
//        "never left staring at a broken board". This is the chosen alternative from the
//        two the issue offered (retry vs. reject-so-App-refetches): retrying inside the
//        interceptor is self-contained and needs no App-level retry wiring.
//     3. If it REJECTS: call userManager.signinRedirect() (full-page redirect to the IdP).
//        The interceptor's promise rejects too (moot once the browser navigates away, but
//        keeps the promise contract sane for any code that runs before that happens).
//   401 with web auth DISABLED, and every non-401 status, are UNCHANGED — still a synchronous
//   throw of err.response (network errors still throw the original error). This is what keeps
//   axiosClient.test.js's existing 500/429/503/network-error assertions green.
//
// IMPLEMENTATION NOTE for the implementer: the handler must stay a *non-async* function whose
// default path synchronously throws (as pinned by axiosClient.test.js's
// `expect(() => errorInterceptor(...)).toThrow(...)`). Only the 401+enabled branch may return a
// Promise (built via userManager.signinSilent().then(...)); do not make the whole handler
// `async`, or every branch starts returning a rejected Promise instead of throwing synchronously,
// which breaks the pinned sync tests.
//
// MANUAL / SYSTEM TEST NOTE: the real silent-renew iframe round-trip against an IdP and the real
// signinRedirect() navigation are not unit-testable — userManager is fully mocked here. The real
// end-to-end 401 → renew/redirect flow against a live IdP is a manual system test.

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
