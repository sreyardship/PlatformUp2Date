package org.yardship.confcheck.adapter;

import org.yardship.confcheck.adapter.http.InsecureRedirectException;
import org.yardship.confcheck.adapter.http.RedirectFollowingHttpGet;
import org.yardship.confcheck.adapter.http.TooManyRedirectsException;
import org.yardship.confcheck.port.BodySource;
import org.yardship.confcheck.port.ResponseSource;

import java.net.URI;
import java.net.http.HttpResponse;

/**
 * Driven {@link ResponseSource} adapter that fetches {@code url} live, mirroring
 * {@link LiveHttpBodySource}'s composition but for the {@code header} surface: redirects
 * (301/302/303/307/308) are followed to their final response before it is returned, per ADR-0029,
 * via this module's own {@link RedirectFollowingHttpGet} — the same transport
 * {@link LiveHttpBodySource} uses, so redirect handling stays identical between the two.
 *
 * <p>The one deliberate divergence from {@link LiveHttpBodySource}: a non-2xx final response is
 * RETURNED as a normal {@link ResponseSource.Response}, never translated into
 * {@link BodySource.BodyFetchException}. That exception is reserved here for genuine transport
 * failures — connection errors, an insecure (HTTPS-to-HTTP) redirect, or a redirect loop/overlong
 * chain — exactly mirroring which failures {@link LiveHttpBodySource} itself treats as transport
 * failures, just without the extra non-2xx gate. See
 * {@code docs/adr/0030-http-header-current-source.md}: a secured Jenkins answering 403 while still
 * volunteering its version in {@code X-Jenkins} is the motivating case this exists for.
 */
public final class LiveHttpResponseSource implements ResponseSource {

    private final URI uri;
    private final RedirectFollowingHttpGet http;

    public LiveHttpResponseSource(String url) {
        this.uri = URI.create(url);
        this.http = new RedirectFollowingHttpGet();
    }

    @Override
    public Response fetch() {
        HttpResponse<String> response = get();
        return new Response(response.statusCode(), response.headers().map());
    }

    private HttpResponse<String> get() {
        try {
            return http.get(uri);
        } catch (InsecureRedirectException | TooManyRedirectsException e) {
            throw new BodySource.BodyFetchException("Failed to fetch '" + uri + "': " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new BodySource.BodyFetchException("Failed to fetch '" + uri + "': " + e.getMessage(), e);
        }
    }
}
