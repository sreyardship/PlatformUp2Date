import { render, screen } from '@testing-library/react'

import App from './App'
import versionClient from './api/versionClient'

vi.mock('./api/versionClient', () => ({
  __esModule: true,
  default: { getVersions: vi.fn(), triggerScrape: vi.fn() },
}))

test('renders the dashboard shell with the PlatformUp2Date top bar', async () => {
  versionClient.getVersions.mockResolvedValue({
    'argo-cd': { current: '2.10.7', latest: '2.11.7' },
  })

  render(<App />)

  expect(await screen.findByText(/platformup2date/i)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /refresh all/i })).toBeInTheDocument()
})
