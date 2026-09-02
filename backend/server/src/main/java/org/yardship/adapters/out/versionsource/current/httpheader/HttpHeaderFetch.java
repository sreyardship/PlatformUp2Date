package org.yardship.adapters.out.versionsource.current.httpheader;

/**
 * The narrow seam {@link HttpHeaderCurrentSource} depends on to obtain a response — mirroring how
 * the {@code http} current source depends on {@code HttpCurrentVersionClient} rather than on a
 * concrete transport. In production this is fulfilled by a GET through
 * {@link org.yardship.adapters.out.versionsource.http.RedirectFollowingHttpGet}; in unit tests it
 * is a fake supplying a fixed {@link HttpHeaderResponse}, so status code and headers can be
 * dictated per test without a stub server.
 */
@FunctionalInterface
public interface HttpHeaderFetch {

    HttpHeaderResponse fetch();
}
