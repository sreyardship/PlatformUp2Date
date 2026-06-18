import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import VersionList from './VersionList'
import versionClient from './api/versionClient'

vi.mock('./api/versionClient', () => ({
  __esModule: true,
  default: { scrapeApplication: vi.fn() },
}))

beforeEach(() => {
  vi.clearAllMocks()
})

test('renders one row per application in the versions object', () => {
  const versions = {
    'git-tea': { current: '1.21.7', latest: '1.22.1' },
    'argo-cd': { current: '2.10.7', latest: '2.11.7' },
    sharry: { current: '1.14.0', latest: '1.14.0' },
  }

  render(<VersionList versions={versions} />)

  expect(screen.getByText('git-tea:')).toBeInTheDocument()
  expect(screen.getByText('argo-cd:')).toBeInTheDocument()
  expect(screen.getByText('sharry:')).toBeInTheDocument()
})

test('threads onRefreshed down to each Version so its buttons can trigger a refetch', async () => {
  versionClient.scrapeApplication.mockResolvedValue({})
  const onRefreshed = vi.fn().mockResolvedValue(undefined)
  const versions = {
    'git-tea': { current: '1.21.7', latest: '1.22.1' },
  }
  const user = userEvent.setup()

  render(<VersionList versions={versions} onRefreshed={onRefreshed} />)

  await user.click(screen.getByRole('button', { name: /update current/i }))

  expect(onRefreshed).toHaveBeenCalledTimes(1)
})
