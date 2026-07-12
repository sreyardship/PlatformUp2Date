import axios from 'axios'
import { isWebAuthEnabled, getAccessToken, userManager } from '../auth/userManager'

const apiBaseUrl = (window._env_ && window._env_.API_BASE_URL) || ''
const baseUrl = `${apiBaseUrl}/api/v1`

const axiosClient = axios.create({
  baseURL: baseUrl,
})

// Full-page signinRedirect() loop guard. The interceptor's _retried flag bounds SILENT renews
// per request, but a signinRedirect() reloads the whole app: if the fresh post-login token is
// STILL rejected with 401 (e.g. the IdP mints it without the audience/role the API demands),
// the reloaded app refetches, 401s, and redirects again — an infinite login loop. The guard has
// to survive that navigation, so it lives in sessionStorage (a boolean marker only — tokens
// deliberately stay in-memory, see userManager.js): one redirect is allowed, then 401s surface
// as errors until some request succeeds again (which re-arms the guard, so a routine token
// expiry hours later still gets its redirect).
const REDIRECT_GUARD_KEY = 'pu2d.auth.signin-redirect-attempted'

const signinRedirectOnce = () => {
  try {
    if (window.sessionStorage.getItem(REDIRECT_GUARD_KEY)) {
      return
    }
    window.sessionStorage.setItem(REDIRECT_GUARD_KEY, 'true')
  } catch {
    // sessionStorage unavailable — fall through and redirect; worst case is today's behavior.
  }
  userManager.signinRedirect()
}

const rearmSigninRedirectGuard = () => {
  try {
    window.sessionStorage.removeItem(REDIRECT_GUARD_KEY)
  } catch {
    // ignore — the guard is best-effort
  }
}

axiosClient.interceptors.request.use(async (config) => {
  const headers = {
    'Content-Type': 'application/json',
  }

  if (isWebAuthEnabled()) {
    const accessToken = await getAccessToken()
    if (accessToken) {
      headers.Authorization = `Bearer ${accessToken}`
    }
  }

  return {
    ...config,
    headers,
  }
})

axiosClient.interceptors.response.use(
  (response) => {
    rearmSigninRedirectGuard()
    if (response && response.data) return response.data
    return response
  },
  (err) => {
    if (!err.response) {
      throw err
    }
    if (err.response.status === 401 && isWebAuthEnabled()) {
      if (err.config && err.config._retried) {
        signinRedirectOnce()
        throw err.response
      }
      return userManager.signinSilent().then(
        () => {
          if (err.config) {
            err.config._retried = true
          }
          return axiosClient.request(err.config).catch((retryErr) => {
            if (
              retryErr &&
              retryErr.response &&
              retryErr.response.status === 401
            ) {
              signinRedirectOnce()
            }
            throw (retryErr && retryErr.response) || retryErr
          })
        },
        () => {
          signinRedirectOnce()
          throw err.response
        }
      )
    }
    throw err.response
  }
)

export default axiosClient
