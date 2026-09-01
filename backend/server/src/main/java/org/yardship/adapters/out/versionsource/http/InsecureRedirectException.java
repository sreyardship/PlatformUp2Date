package org.yardship.adapters.out.versionsource.http;

import java.net.URI;

/**
 * Thrown by {@link RedirectFollowingHttpGet} when a redirect {@code Location} would downgrade the
 * connection from HTTPS to plain HTTP. Refused before the HTTP target is ever contacted (ADR-0029)
 * — a source-controlled redirect must not be able to force a request onto an unencrypted channel.
 */
public class InsecureRedirectException extends RuntimeException {

    public InsecureRedirectException(URI from, URI to) {
        super("Refusing to follow HTTPS-to-HTTP downgrade redirect from '" + from + "' to '" + to + "'");
    }
}
