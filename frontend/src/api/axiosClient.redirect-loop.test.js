// The full-page signinRedirect() loop guard of the axios response interceptor.
//
// CONTRACT PINNED HERE (complements axiosClient.401.test.js, which pins the per-request silent-
// renew bound): signinRedirect() reloads the whole app, so the per-request _retried marker cannot
// stop a loop that spans navigations — if the fresh post-login token is STILL 401-rejected (e.g.
// IdP mints it without the audience/role the API demands), the reloaded app refetches, 401s and
// would redirect forever. The guard (a sessionStorage marker, surviving the navigation):
//   1. The FIRST exhausted 401 performs the full-page signinRedirect() and arms the guard.
//   2. While armed, further exhausted 401s do NOT redirect — the interceptor still rejects, so
//      the App renders the failure instead of looping through the IdP.
//   3. Any successful response re-arms (clears) the guard, so a routine token expiry long after
//      a successful session still gets its full-page redirect.

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
let successInterceptor

beforeAll(() => {
  axiosClient.interceptors.response.forEach((handler) => {
    if (handler && handler.rejected) {
      errorInterceptor = handler.rejected
      successInterceptor = handler.fulfilled
    }
  })
})

beforeEach(() => {
  vi.clearAllMocks()
  window.sessionStorage.clear()
  isWebAuthEnabled.mockReturnValue(true)
  // Exhaust the silent-renew path immediately — these tests only exercise the redirect guard.
  userManager.signinSilent.mockRejectedValue(new Error('renew failed'))
})

const httpErr401 = () => ({
  response: { status: 401, data: 'Unauthorized', headers: {} },
  config: { url: '/version', method: 'get' },
})

const exhausted401 = async () => {
  try {
    await errorInterceptor(httpErr401())
  } catch {
    // expected — the interceptor's promise always rejects on the exhausted path
  }
}

describe('axiosClient response interceptor — full-page redirect loop guard', () => {
  test('the first exhausted 401 performs the full-page redirect', async () => {
    await exhausted401()

    expect(userManager.signinRedirect).toHaveBeenCalledTimes(1)
  })

  test('while the guard is armed (post-redirect page load), an exhausted 401 rejects WITHOUT redirecting again', async () => {
    await exhausted401() // arms the guard (simulates the pre-navigation page)

    userManager.signinRedirect.mockClear()
    await exhausted401() // the reloaded app's fetch still 401s

    expect(userManager.signinRedirect).not.toHaveBeenCalled()
  })

  test('the guarded 401 still rejects so the App can render the failure', async () => {
    await exhausted401()

    await expect(errorInterceptor(httpErr401())).rejects.toBeTruthy()
  })

  test('a successful response re-arms the guard: the next exhausted 401 redirects again', async () => {
    await exhausted401() // guard armed

    successInterceptor({ data: { ok: true } }) // auth demonstrably works again

    userManager.signinRedirect.mockClear()
    await exhausted401() // e.g. a token expiry much later

    expect(userManager.signinRedirect).toHaveBeenCalledTimes(1)
  })
})
