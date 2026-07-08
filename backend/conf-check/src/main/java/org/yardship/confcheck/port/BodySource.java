package org.yardship.confcheck.port;

/**
 * Obtains the response body for a fetch-backed validation, decoupling "where the body comes from"
 * (a live HTTP fetch, a local file, stdin) from the validators that consume it. Shared by every
 * subcommand that validates against a body (regex now; pointer/changelog reuse it later).
 */
public interface BodySource {

    /**
     * @return the body text.
     * @throws BodyFetchException if the body could not be obtained (network error, non-2xx response,
     *         unreadable file, ...). Adapters that fetch live over the network throw this so the
     *         caller can translate it to {@link org.yardship.confcheck.outcome.ValidationOutcome.FetchFailed};
     *         offline adapters (file/stdin) may also throw it for I/O errors.
     */
    String body();

    /** Raised by a {@link BodySource} when the body cannot be obtained. */
    class BodyFetchException extends RuntimeException {
        public BodyFetchException(String message) {
            super(message);
        }

        public BodyFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
