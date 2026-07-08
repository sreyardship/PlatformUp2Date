import axios from 'axios'
import { isWebAuthEnabled, getAccessToken } from '../auth/userManager'

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
    throw err.response
  }
)

export default axiosClient
