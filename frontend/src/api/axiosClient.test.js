import axiosClient from './axiosClient'

// Extract the registered response error handler from the interceptor manager.
// axios's InterceptorManager exposes a forEach(fn) method where each handler
// has the shape { fulfilled, rejected, synchronous, runWhen }.
// We want the rejected (error) handler registered by our interceptor.
let errorInterceptor

beforeAll(() => {
  axiosClient.interceptors.response.forEach((handler) => {
    if (handler && handler.rejected) {
      errorInterceptor = handler.rejected
    }
  })
})

// ────────────────────────────────────────────────────────────────────────────
// Network error (no err.response) — should REJECT with the original error
// and must NOT call window.alert
// ────────────────────────────────────────────────────────────────────────────

describe('axiosClient response interceptor — network error (no err.response)', () => {
  let alertSpy

  beforeEach(() => {
    alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {})
  })

  afterEach(() => {
    alertSpy.mockRestore()
  })

  test('throws the original error when err.response is absent', () => {
    const networkErr = new Error('Network Error')
    // The interceptor must throw so callers get a rejection, not undefined
    expect(() => errorInterceptor(networkErr)).toThrow(networkErr)
  })

  test('does not call window.alert on network errors', () => {
    const networkErr = new Error('Network Error')
    try {
      errorInterceptor(networkErr)
    } catch {
      // expected
    }
    expect(alertSpy).not.toHaveBeenCalled()
  })

  test('throws the exact same error object (identity preserved)', () => {
    const networkErr = new Error('ECONNREFUSED')
    let thrown
    try {
      errorInterceptor(networkErr)
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBe(networkErr)
  })
})

// ────────────────────────────────────────────────────────────────────────────
// HTTP error (err.response present) — should THROW err.response
// (so callers receive { status, data, headers } directly, matching the
//  existing TopBar 429 handling: `err?.status === 429`)
// ────────────────────────────────────────────────────────────────────────────

describe('axiosClient response interceptor — HTTP error (err.response present)', () => {
  test('throws err.response for a 500 response', () => {
    const httpErr = {
      response: { status: 500, data: 'Internal Server Error', headers: {} },
    }
    expect(() => errorInterceptor(httpErr)).toThrow(httpErr.response)
  })

  test('throws err.response for a 429 response (TopBar cooldown path)', () => {
    const httpErr = {
      response: {
        status: 429,
        data: { retryAfterSeconds: 3 },
        headers: { 'retry-after': '3' },
      },
    }
    let thrown
    try {
      errorInterceptor(httpErr)
    } catch (e) {
      thrown = e
    }
    // TopBar reads err?.status — the thrown value must have .status
    expect(thrown).toBe(httpErr.response)
    expect(thrown.status).toBe(429)
  })

  test('thrown value carries .status and .data so callers can read them', () => {
    const httpErr = {
      response: { status: 503, data: { message: 'Service Unavailable' }, headers: {} },
    }
    let thrown
    try {
      errorInterceptor(httpErr)
    } catch (e) {
      thrown = e
    }
    expect(thrown.status).toBe(503)
    expect(thrown.data).toEqual({ message: 'Service Unavailable' })
  })
})
