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
