package org.yardship.adapters.out.versionclient;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

/**
 * Turns a non-2xx HTTP response from an upstream version endpoint into a
 * {@link VersionFetchException} whose message carries the HTTP status and a
 * truncated copy of the response body. The per-app scrape loop only has to log
 * {@code e.getMessage()} to get an informative diagnostic (e.g. a GitHub
 * rate-limit JSON or an HTML login page).
 *
 * Registered by class on the REST client builder, so the native image must keep its
 * constructor reflectively accessible — hence {@link RegisterForReflection}. Without it
 * the rest client cannot instantiate the provider in native mode and bean construction fails.
 */
@RegisterForReflection
public class VersionResponseExceptionMapper implements ResponseExceptionMapper<RuntimeException> {

    private static final int MAX_BODY = 512;

    @Override
    public RuntimeException toThrowable(Response response) {
        int status = response.getStatus();
        String body = readBody(response);
        return new VersionFetchException(
                "HTTP " + status + " response: " + truncate(body), status, body);
    }

    @Override
    public boolean handles(int status, MultivaluedMap<String, Object> headers) {
        return status < 200 || status >= 300;
    }

    private static String readBody(Response response) {
        try {
            return response.hasEntity() ? response.readEntity(String.class) : "";
        } catch (Exception e) {
            return "<unreadable body: " + e.getMessage() + ">";
        }
    }

    private static String truncate(String body) {
        if (body == null) {
            return "null";
        }
        if (body.length() <= MAX_BODY) {
            return body;
        }
        return body.substring(0, MAX_BODY) + "…[truncated]";
    }
}
