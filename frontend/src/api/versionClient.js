import axiosClient from './axiosClient'

const versionApi = {
  temp: () => axiosClient.get('/posts'),
  getVersions: () => axiosClient.get('/version'),
  triggerScrape: () => axiosClient.post('/scrape'),
}

export default versionApi
