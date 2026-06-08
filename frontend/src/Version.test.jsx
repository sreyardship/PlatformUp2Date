import { render, screen } from '@testing-library/react'

import Version from './Version'

// Parses a CSS "rgb(r, g, b)" string into { r, g, b }.
const rgb = (color) => {
  const [r, g, b] = color.match(/\d+/g).map(Number)
  return { r, g, b }
}

const currentColor = (current, latest) => {
  const { unmount } = render(
    <Version name='app' ver={{ current, latest }} />
  )
  // When up-to-date, current and latest render the same string; the colored
  // "current" Typography is the first match in DOM order.
  const color = getComputedStyle(screen.getAllByText(current)[0]).color
  unmount()
  return color
}

test('shows the current version in green when it matches the latest', () => {
  const { r, g, b } = rgb(currentColor('1.0.0', '1.0.0'))
  expect(g).toBeGreaterThan(r)
  expect(g).toBeGreaterThan(b)
})

test('shows the current version in red when it is behind the latest', () => {
  const { r, g, b } = rgb(currentColor('1.0.0', '2.0.0'))
  expect(r).toBeGreaterThan(g)
  expect(r).toBeGreaterThan(b)
})
