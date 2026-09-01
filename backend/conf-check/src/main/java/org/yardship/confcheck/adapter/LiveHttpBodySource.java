package org.yardship.confcheck.adapter;

import org.yardship.confcheck.adapter.http.InsecureRedirectException;
import org.yardship.confcheck.adapter.http.RedirectFollowingHttpGet;
import org.yardship.confcheck.adapter.http.TooManyRedirectsException;
import org.yardship.confcheck.port.BodySource;

import java.net.URI;
import java.net.http.HttpResponse;

/**
 * Driven {@link BodySource} adapter that fetches {@code url} live, mirroring the fetch half of the
 * production {@code HttpRegexLatestSource} adapter (backend). Redirects (301/302/303/307/308) are
 * followed to their final response before the body is returned, per ADR-0029, via this module's own
 * {@link RedirectFollowingHttpGet} (a small conf-check-local equivalent of the server module's
 * transport of the same name — deliberately not shared, since {@code :backend:conf-check} must not
 * depend on {@code :backend:server}). A non-2xx response on the final response, an insecure
 * (HTTPS-to-HTTP) redirect, a redirect loop/overlong chain, or an I/O error is translated to
 * {@link BodySource.BodyFetchException}, which the {@code regex} command maps to
 * {@link org.yardship.confcheck.outcome.ValidationOutcome.FetchFailed}.
 */
public final class LiveHttpBodySource implements BodySource {

    private final URI uri;
    private final RedirectFollowingHttpGet http;

    public LiveHttpBodySource(String url) {
        this.uri = URI.create(url);
        this.http = new RedirectFollowingHttpGet();
    }

    @Override
    public String body() {
        HttpResponse<String> response = get();
        if (response.statusCode() / 100 != 2) {
            throw new BodyFetchException(
                    "Non-success HTTP status " + response.statusCode() + " fetching '" + uri + "'");
        }
        return response.body();
    }

    private HttpResponse<String> get() {
        try {
            return http.get(uri);
        } catch (InsecureRedirectException | TooManyRedirectsException e) {
            throw new BodyFetchException("Failed to fetch '" + uri + "': " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new BodyFetchException("Failed to fetch '" + uri + "': " + e.getMessage(), e);
        }
    }
}
