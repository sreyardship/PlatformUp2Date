import axiosClient from './axiosClient'

const versionApi = {
  temp: () => axiosClient.get('/posts'),
  getVersions: () => axiosClient.get('/version'),
}

export default versionApi
