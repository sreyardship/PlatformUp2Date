import { render, screen } from '@testing-library/react'

import VersionList from './VersionList'

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
