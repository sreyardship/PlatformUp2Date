package org.yardship.core.ports.out;

/**
 * Thrown by a {@link ScrapeStateStore} when the Scrape state could not be read or written —
 * the backing store is unreachable, or the snapshot could not be serialised or deserialised.
 * Substrate detail (Valkey, the operation that failed) stays in the message and the cause.
 *
 * <p>It is the store's statement, not the use case's. {@code ApplicationVersionService} catches it
 * and raises {@code core.ports.in.ScrapeStateUnavailableException}, preserving the cause, so a
 * Surface is never handed a type from {@code core.ports.out}. See that type's Javadoc for why the
 * pair is kept apart.
 */
public class ScrapeStateAccessException extends RuntimeException {

    public ScrapeStateAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
