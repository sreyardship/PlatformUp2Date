import axios from 'axios'

const baseUrl = `${process.env.REACT_APP_BASE_URL}/api/v1`

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
      return alert(err)
    }
    throw err.response
  }
)

export default axiosClient
