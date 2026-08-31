package org.yardship.adapters.out.versionsource.http;

/**
 * Thrown by {@link RedirectFollowingHttpGet} when a GET traverses more than the bounded maximum
 * number of redirect hops without reaching a non-redirect response — covers both a genuine
 * redirect loop (A → B → A → …) and any overlong chain. Callers treat this like any other
 * source-read failure; it deliberately does not hang or recurse unbounded (ADR-0029).
 */
public class TooManyRedirectsException extends RuntimeException {

    public TooManyRedirectsException(String message) {
        super(message);
    }
}
