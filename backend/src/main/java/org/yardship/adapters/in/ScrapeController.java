package org.yardship.adapters.in;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.yardship.core.domain.primitives.ScrapeTarget;
import org.yardship.core.ports.in.Outcome;
import org.yardship.core.ports.in.ScrapeStatus;
import org.yardship.core.ports.in.ApplicationVersionPort;

import java.util.List;

/**
 * Hand-written JAX-RS adapter for the manual on-demand scrape, mirroring {@link VersionController}'s
 * style (it does NOT touch the OpenAPI spec). {@code POST /api/v1/scrape} forces a scrape:
 *
 * <ul>
 *   <li>SCRAPED / IN_PROGRESS → 200 with the {@link ScrapeStatus} body;</li>
 *   <li>RATE_LIMITED → 429 with a {@code Retry-After} header (seconds) and the body, so a client knows
 *       when a budget slot frees;</li>
 *   <li>fail-closed: when Valkey is unreachable the port throws
 *       {@link org.yardship.adapters.out.valkey.ScrapeStateUnavailableException}, mapped to 503 by
 *       {@link ScrapeStateUnavailableExceptionMapper} — do not catch it here.</li>
 * </ul>
 */
@Path("/api/v1")
public class ScrapeController {

    private final ApplicationVersionPort applicationVersionPort;

    public ScrapeController(ApplicationVersionPort applicationVersionPort) {
        this.applicationVersionPort = applicationVersionPort;
    }

    @POST
    @Path("scrape")
    public Response scrape() {
        ScrapeStatus status = applicationVersionPort.triggerScrape();
        if (status.outcome() == Outcome.RATE_LIMITED) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .header("Retry-After", status.retryAfterSeconds())
                    .entity(status)
                    .build();
        }
        return Response.ok(status).build();
    }

    /**
     * Refreshes only the requested {@code (app, side)} targets — see
     * {@link ApplicationVersionPort#targetedScrape(List)}. Same outcome→HTTP mapping as
     * {@link #scrape()}: SCRAPED/IN_PROGRESS → 200, RATE_LIMITED → 429 + {@code Retry-After}, and
     * (via {@link ScrapeStateUnavailableExceptionMapper}) Valkey-unreachable → 503.
     */
    @POST
    @Path("scrape/applications")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response scrapeApplications(ScrapeTargetsRequest request) {
        List<ScrapeTarget> targets = request.targets().stream()
                .map(t -> new ScrapeTarget(t.name(), t.side()))
                .toList();
        ScrapeStatus status = applicationVersionPort.targetedScrape(targets);
        if (status.outcome() == Outcome.RATE_LIMITED) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .header("Retry-After", status.retryAfterSeconds())
                    .entity(status)
                    .build();
        }
        return Response.ok(status).build();
    }
}
