import '@testing-library/jest-dom'

// @testing-library/dom's jestFakeTimersAreEnabled() gates its fake-timer detection on
// `typeof jest !== 'undefined'`. In a vitest environment only `vi` is global, so the
// check always returns false. The consequence is that @testing-library/react's
// asyncWrapper drains microtasks via `setTimeout(..., 0)` without ever advancing the
// fake clock, causing every `await userEvent.*()` call to hang when vi.useFakeTimers()
// is active.
//
// vitest uses @sinonjs/fake-timers internally, which sets a `clock` own-property on
// the fake `setTimeout` — exactly the property that jestFakeTimersAreEnabled() tests
// after the `typeof jest` guard. Aliasing `vi` as `jest` makes the guard pass, so the
// detection and the subsequent `jest.advanceTimersByTime(0)` call both work correctly.
globalThis.jest = globalThis.vi
