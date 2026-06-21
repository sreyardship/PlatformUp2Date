import { render, screen, within } from '@testing-library/react'

import SummaryCards from './SummaryCards'

// Contract: <SummaryCards versions={versionsObject} />
// versionsObject is the version payload shape { "<name>": { current, latest, outdated, drift } }.
// Renders three bento cards:
//   - "Total Apps"        -> count of all apps
//   - "Up to Date"        -> count where drift === 'NONE'
//   - "Updates Available" -> PATCH / MINOR / MAJOR breakdown, each labeled and counted

const mixedVersions = {
  'app-none': { current: '1.0.0', latest: '1.0.0', outdated: false, drift: 'NONE' },
  'app-patch-1': { current: '1.0.0', latest: '1.0.1', outdated: true, drift: 'PATCH' },
  'app-patch-2': { current: '2.0.0', latest: '2.0.3', outdated: true, drift: 'PATCH' },
  'app-minor-1': { current: '1.0.0', latest: '1.2.0', outdated: true, drift: 'MINOR' },
  'app-major-1': { current: '1.0.0', latest: '2.0.0', outdated: true, drift: 'MAJOR' },
  'app-major-2': { current: '1.0.0', latest: '3.0.0', outdated: true, drift: 'MAJOR' },
  'app-major-3': { current: '1.0.0', latest: '4.0.0', outdated: true, drift: 'MAJOR' },
}

// Finds a card by its uppercase label heading, then scopes further queries to
// that card's container so colliding numbers across cards can't cross-match.
function getCardByLabel(label) {
  const heading = screen.getByText(label)
  // The card is the nearest ancestor that contains both the heading and its value.
  return heading.closest('[data-testid], .MuiPaper-root') ?? heading.parentElement
}

test('renders Total Apps, Up to Date, and Updates Available card labels', () => {
  render(<SummaryCards versions={mixedVersions} />)

  expect(screen.getByText(/total apps/i)).toBeInTheDocument()
  expect(screen.getByText(/up to date/i)).toBeInTheDocument()
  expect(screen.getByText(/updates available/i)).toBeInTheDocument()
})

test('Total Apps shows the total count of apps in the payload', () => {
  render(<SummaryCards versions={mixedVersions} />)

  const card = getCardByLabel(/total apps/i)
  expect(within(card).getByText('7')).toBeInTheDocument()
})

test('Up to Date shows only the count of apps with drift NONE', () => {
  render(<SummaryCards versions={mixedVersions} />)

  const card = getCardByLabel(/up to date/i)
  expect(within(card).getByText('1')).toBeInTheDocument()
})

test('Updates Available shows the PATCH/MINOR/MAJOR breakdown with labels', () => {
  render(<SummaryCards versions={mixedVersions} />)

  const card = getCardByLabel(/updates available/i)

  expect(within(card).getByText(/patch/i)).toBeInTheDocument()
  expect(within(card).getByText(/minor/i)).toBeInTheDocument()
  expect(within(card).getByText(/major/i)).toBeInTheDocument()

  expect(within(card).getByText('2')).toBeInTheDocument() // patch count
  expect(within(card).getByText('1')).toBeInTheDocument() // minor count
  expect(within(card).getByText('3')).toBeInTheDocument() // major count
})

test('renders all-zero counts for an empty payload', () => {
  render(<SummaryCards versions={{}} />)

  const totalCard = getCardByLabel(/total apps/i)
  const upToDateCard = getCardByLabel(/up to date/i)

  expect(within(totalCard).getByText('0')).toBeInTheDocument()
  expect(within(upToDateCard).getByText('0')).toBeInTheDocument()
})

test('counts recompute when the versions prop changes on refresh', () => {
  const { rerender } = render(<SummaryCards versions={{
    a: { current: '1.0.0', latest: '1.0.0', outdated: false, drift: 'NONE' },
  }} />)

  expect(within(getCardByLabel(/total apps/i)).getByText('1')).toBeInTheDocument()
  expect(within(getCardByLabel(/up to date/i)).getByText('1')).toBeInTheDocument()

  rerender(<SummaryCards versions={mixedVersions} />)

  expect(within(getCardByLabel(/total apps/i)).getByText('7')).toBeInTheDocument()
  expect(within(getCardByLabel(/up to date/i)).getByText('1')).toBeInTheDocument()
  expect(within(getCardByLabel(/updates available/i)).getByText('2')).toBeInTheDocument()
})
