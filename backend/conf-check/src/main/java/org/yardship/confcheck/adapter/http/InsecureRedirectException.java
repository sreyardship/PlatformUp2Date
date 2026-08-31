package org.yardship.confcheck.adapter.http;

import java.net.URI;

/**
 * Thrown by {@link RedirectFollowingHttpGet} when a redirect {@code Location} would downgrade the
 * connection from HTTPS to plain HTTP. Refused before the HTTP target is ever contacted (ADR-0029)
 * — a source-controlled redirect must not be able to force a request onto an unencrypted channel.
 *
 * <p>This is a conf-check-local type: {@code :backend:conf-check} must not depend on
 * {@code :backend:server}, so this deliberately does not reuse the server module's equivalent
 * exception even though the shape/behavior mirrors it.
 */
public class InsecureRedirectException extends RuntimeException {

    public InsecureRedirectException(URI from, URI to) {
        super("Refusing to follow HTTPS-to-HTTP downgrade redirect from '" + from + "' to '" + to + "'");
    }
}
