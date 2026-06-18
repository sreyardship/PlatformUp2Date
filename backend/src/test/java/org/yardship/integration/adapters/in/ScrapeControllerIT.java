package org.yardship.integration.adapters.in;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.valkey.ScrapeStateUnavailableException;
import org.yardship.core.domain.primitives.Side;
import org.yardship.core.domain.primitives.TargetResult;
import org.yardship.core.ports.in.ApplicationVersionPort;
import org.yardship.core.ports.in.ScrapeStatus;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

/**
 * HTTP-level tests for {@code POST /api/v1/scrape}. The inbound port is mocked so these exercise the
 * controller + JAX-RS mapping in isolation from Valkey:
 *
 * <ul>
 *   <li>SCRAPED → 200 with the {@link ScrapeStatus} body (outcome + per-app counts);</li>
 *   <li>IN_PROGRESS → still 200 (no HTTP error in this slice; the outcome is conveyed in the body);</li>
 *   <li>fail-closed: when the scrape-state source is unavailable (port throws), the endpoint returns
 *       503 via the shared {@code ScrapeStateUnavailableExceptionMapper}.</li>
 * </ul>
 */
@QuarkusTest
class ScrapeControllerIT {

    @InjectMock
    ApplicationVersionPort applicationVersionPort;

    @Test
    void postScrape_scraped_returns200WithStatusBody() {
        // attempted=3, failed=1 → succeeded=2; budget telemetry: remaining=7, resets in 1800s.
        when(applicationVersionPort.triggerScrape()).thenReturn(ScrapeStatus.scraped(3, 1, 7, 1800));

        given()
                .when()
                .post("/api/v1/scrape")
                .then()
                .statusCode(200)
                .body("outcome", equalTo("SCRAPED"))
                .body("appsAttempted", equalTo(3))
                .body("appsSucceeded", equalTo(2))
                .body("appsFailed", equalTo(1))
                .body("triggersRemaining", equalTo(7))
                .body("windowResetsInSeconds", equalTo(1800));
    }

    @Test
    void postScrape_scraped_bodyExposesPerAppTargetResults() {
        // Reuses the full-scrape TargetResult factory added in issue 02: one entry per app, side
        // BOTH, succeeded/reason naming which app failed and why — not just an aggregate count.
        when(applicationVersionPort.triggerScrape()).thenReturn(
                ScrapeStatus.scraped(
                        2,
                        1,
                        7,
                        1800,
                        List.of(
                                TargetResult.success("argo-cd", Side.BOTH),
                                TargetResult.failure("grafana", Side.BOTH, "github down"))));

        given()
                .when()
                .post("/api/v1/scrape")
                .then()
                .statusCode(200)
                .body("targetResults.size()", equalTo(2))
                .body("targetResults[0].name", equalTo("argo-cd"))
                .body("targetResults[0].side", equalTo("BOTH"))
                .body("targetResults[0].succeeded", equalTo(true))
                .body("targetResults[1].name", equalTo("grafana"))
                .body("targetResults[1].succeeded", equalTo(false))
                .body("targetResults[1].reason", equalTo("github down"));
    }

    @Test
    void postScrape_rateLimited_returns429WithRetryAfterHeaderAndBody() {
        // The budget for this window is exhausted: 429 + Retry-After: 42 + a RATE_LIMITED body.
        when(applicationVersionPort.triggerScrape()).thenReturn(ScrapeStatus.rateLimited(42));

        given()
                .when()
                .post("/api/v1/scrape")
                .then()
                .statusCode(429)
                .header("Retry-After", equalTo("42"))
                .body("outcome", equalTo("RATE_LIMITED"))
                .body("retryAfterSeconds", equalTo(42));
    }

    @Test
    void postScrape_inProgress_returns200WithInProgressOutcome() {
        when(applicationVersionPort.triggerScrape()).thenReturn(ScrapeStatus.inProgress());

        given()
                .when()
                .post("/api/v1/scrape")
                .then()
                .statusCode(200)
                .body("outcome", equalTo("IN_PROGRESS"));
    }

    @Test
    void postScrape_returns503_whenScrapeStateUnavailable() {
        // Fail closed: Valkey unreachable surfaces as the port throwing; the endpoint returns 503.
        when(applicationVersionPort.triggerScrape())
                .thenThrow(new ScrapeStateUnavailableException("valkey unreachable", new RuntimeException()));

        given()
                .when()
                .post("/api/v1/scrape")
                .then()
                .statusCode(503);
    }
}
