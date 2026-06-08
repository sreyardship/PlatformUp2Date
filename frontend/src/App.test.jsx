import { render, screen } from '@testing-library/react'

import App from './App'
import versionClient from './api/versionClient'

vi.mock('./api/versionClient', () => ({
  __esModule: true,
  default: { getVersions: vi.fn() },
}))

test('renders the title and an application row with its current and latest versions', async () => {
  versionClient.getVersions.mockResolvedValue({
    'argo-cd': { current: '2.10.7', latest: '2.11.7' },
  })

  render(<App />)

  expect(await screen.findByText('Versions')).toBeInTheDocument()
  expect(await screen.findByText('argo-cd:')).toBeInTheDocument()
  expect(screen.getByText('2.10.7')).toBeInTheDocument()
  expect(screen.getByText('2.11.7')).toBeInTheDocument()
})
