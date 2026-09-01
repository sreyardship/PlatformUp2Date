package org.yardship.adapters.out.versionsource.latest.githubrelease;

import io.vertx.core.MultiMap;
import io.vertx.core.http.RequestOptions;
import org.jboss.resteasy.reactive.client.handlers.AdvancedRedirectHandler;

import jakarta.ws.rs.ext.ContextResolver;
import java.net.URI;

/**
 * Follows GitHub release redirects without forwarding a bearer token to another origin.
 */
final class GithubRedirectHandler implements AdvancedRedirectHandler, ContextResolver<AdvancedRedirectHandler> {

    @Override
    public AdvancedRedirectHandler getContext(Class<?> ignored) {
        return this;
    }

    @Override
    public RequestOptions handle(Context context) {
        if (!isRedirect(context.jaxRsResponse().getStatus())) {
            return null;
        }

        String location = context.jaxRsResponse().getHeaderString("Location");
        if (location == null) {
            return null;
        }

        URI origin = URI.create(context.request().absoluteURI());
        URI target = origin.resolve(location);
        if (isHttpsToHttpDowngrade(origin, target)) {
            return null;
        }
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().addAll(context.request().headers());
        if (!hasSameOrigin(origin, target)) {
            context.request().headers().remove("Authorization");
            headers.remove("Authorization");
        }

        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(target.toString())
                .setHeaders(headers);
        if (context.jaxRsResponse().getStatus() == 307) {
            options.setMethod(context.request().getMethod());
        }
        return options;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean isHttpsToHttpDowngrade(URI origin, URI target) {
        return origin.getScheme().equalsIgnoreCase("https")
                && target.getScheme().equalsIgnoreCase("http");
    }

    private static boolean hasSameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }
}
