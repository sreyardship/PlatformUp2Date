import versionClient from './versionClient'
import axiosClient from './axiosClient'

vi.mock('./axiosClient', () => ({
  __esModule: true,
  default: { get: vi.fn(), post: vi.fn() },
}))

test('triggerScrape issues POST /scrape', () => {
  versionClient.triggerScrape()

  expect(axiosClient.post).toHaveBeenCalledWith('/scrape')
})

test('scrapeApplication issues POST /scrape/applications with a single (name, side) target', () => {
  versionClient.scrapeApplication('my-app', 'current')

  expect(axiosClient.post).toHaveBeenCalledWith('/scrape/applications', {
    targets: [{ name: 'my-app', side: 'current' }],
  })
})

test('scrapeApplication targets the latest side when requested', () => {
  versionClient.scrapeApplication('my-app', 'latest')

  expect(axiosClient.post).toHaveBeenCalledWith('/scrape/applications', {
    targets: [{ name: 'my-app', side: 'latest' }],
  })
})
