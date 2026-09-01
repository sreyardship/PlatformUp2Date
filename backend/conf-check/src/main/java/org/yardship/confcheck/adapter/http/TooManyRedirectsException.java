package org.yardship.confcheck.adapter.http;

/**
 * Thrown by {@link RedirectFollowingHttpGet} when a GET traverses more than the bounded maximum
 * number of redirect hops without reaching a non-redirect response — covers both a genuine
 * redirect loop (A -&gt; B -&gt; A -&gt; ...) and any overlong chain. Callers treat this like any
 * other source-read failure; it deliberately does not hang or recurse unbounded (ADR-0029).
 *
 * <p>Conf-check-local type, mirroring the server module's equivalent without depending on it.
 */
public class TooManyRedirectsException extends RuntimeException {

    public TooManyRedirectsException(String message) {
        super(message);
    }
}
