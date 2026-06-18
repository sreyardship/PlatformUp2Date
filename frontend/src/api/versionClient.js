import axiosClient from './axiosClient'

const versionApi = {
  temp: () => axiosClient.get('/posts'),
  getVersions: () => axiosClient.get('/version'),
  triggerScrape: () => axiosClient.post('/scrape'),
  scrapeApplication: (name, side) =>
    axiosClient.post('/scrape/applications', { targets: [{ name, side }] }),
}

export default versionApi
