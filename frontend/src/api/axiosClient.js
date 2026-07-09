import axios from 'axios'
import { isWebAuthEnabled, getAccessToken, userManager } from '../auth/userManager'

const apiBaseUrl = (window._env_ && window._env_.API_BASE_URL) || ''
const baseUrl = `${apiBaseUrl}/api/v1`

const axiosClient = axios.create({
  baseURL: baseUrl,
})

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
    if (response && response.data) return response.data
    return response
  },
  (err) => {
    if (!err.response) {
      throw err
    }
    if (err.response.status === 401 && isWebAuthEnabled()) {
      if (err.config && err.config._retried) {
        userManager.signinRedirect()
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
              userManager.signinRedirect()
            }
            throw (retryErr && retryErr.response) || retryErr
          })
        },
        () => {
          userManager.signinRedirect()
          throw err.response
        }
      )
    }
    throw err.response
  }
)

export default axiosClient
