/**
 * Interprets a rejected fetch error into a failure kind.
 *
 * @param {*} error - The caught error. Shape depends on the axiosClient interceptor:
 *   - network error  → original Error object (no .status)
 *   - HTTP error     → err.response ({ .status, .data, ... })
 *
 * @returns {{ kind: 'unreachable' | 'api-error', message: string }}
 */
export function failureKind(error) {
  if (error && error.status) {
    return {
      kind: 'api-error',
      message: `API error: received HTTP ${error.status}`,
    }
  }
  return {
    kind: 'unreachable',
    message: "Couldn't reach the PlatformUp2Date API",
  }
}
