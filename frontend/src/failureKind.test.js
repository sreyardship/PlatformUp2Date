import { failureKind } from './failureKind'

// failureKind(error) interprets a rejected fetch error into one of two kinds:
//   'unreachable' — no HTTP response at all (network error, no .status on the error)
//   'api-error'   — the server replied with an HTTP error status
//
// Errors from the axiosClient interceptor are shaped as:
//   network errors  → original axios Error object (no .status property)
//   HTTP errors     → err.response, which has a .status number

describe('failureKind — unreachable (network error)', () => {
  test('returns kind "unreachable" when the error has no status', () => {
    const networkErr = new Error('Network Error')
    const result = failureKind(networkErr)
    expect(result.kind).toBe('unreachable')
  })

  test('returns the canonical unreachable message', () => {
    const networkErr = new Error('Network Error')
    const result = failureKind(networkErr)
    expect(result.message).toBe("Couldn't reach the PlatformUp2Date API")
  })

  test('treats a plain object with no status as unreachable', () => {
    const result = failureKind({})
    expect(result.kind).toBe('unreachable')
  })

  test('treats null error as unreachable', () => {
    const result = failureKind(null)
    expect(result.kind).toBe('unreachable')
  })
})

describe('failureKind — api-error (HTTP error response)', () => {
  test('returns kind "api-error" when the error carries an HTTP status', () => {
    const httpErr = { status: 500 }
    const result = failureKind(httpErr)
    expect(result.kind).toBe('api-error')
  })

  test('includes the HTTP status number in the message for a 500', () => {
    const httpErr = { status: 500 }
    const result = failureKind(httpErr)
    expect(result.message).toMatch(/500/)
  })

  test('includes the HTTP status number in the message for a 503', () => {
    const httpErr = { status: 503 }
    const result = failureKind(httpErr)
    expect(result.message).toMatch(/503/)
  })

  test('includes the HTTP status number in the message for a 404', () => {
    const httpErr = { status: 404 }
    const result = failureKind(httpErr)
    expect(result.message).toMatch(/404/)
  })

  test('status 0 is treated as unreachable (no real HTTP response)', () => {
    // Axios may set status:0 in some environments for aborted requests;
    // treat falsy status as unreachable
    const abortedErr = { status: 0 }
    const result = failureKind(abortedErr)
    expect(result.kind).toBe('unreachable')
  })
})
