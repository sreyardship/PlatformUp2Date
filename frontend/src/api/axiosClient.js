import axios from 'axios'

const apiBaseUrl = (window._env_ && window._env_.API_BASE_URL) || ''
const baseUrl = `${apiBaseUrl}/api/v1`

const axiosClient = axios.create({
  baseURL: baseUrl,
})

axiosClient.interceptors.request.use(async (config) => {
  return {
    ...config,
    headers: {
      'Content-Type': 'application/json',
    },
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
