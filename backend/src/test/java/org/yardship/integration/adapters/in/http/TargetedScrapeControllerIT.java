package org.yardship.integration.adapters.in.http;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.scrapestate.ScrapeStateUnavailableException;
import org.yardship.core.domain.primitives.Side;
import org.yardship.core.domain.primitives.TargetResult;
import org.yardship.core.ports.in.ApplicationVersionPort;
import org.yardship.core.ports.in.ScrapeStatus;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * HTTP-level tests for {@code POST /api/v1/scrape/applications}. The inbound port is mocked so
 * these exercise the controller + JAX-RS mapping in isolation — mirrors {@link ScrapeControllerIT}.
 */
@QuarkusTest
class TargetedScrapeControllerIT {

    @InjectMock
    ApplicationVersionPort applicationVersionPort;

    @Test
    void postScrapeApplications_validTargets_returns200WithTargetResultsBody() {
        when(applicationVersionPort.targetedScrape(any())).thenReturn(
                ScrapeStatus.scraped(
                        List.of(
                                TargetResult.success("argo-cd", Side.CURRENT),
                                TargetResult.success("grafana", Side.LATEST)),
                        9,
                        1800));

        given()
                .contentType("application/json")
                .body("""
                        {"targets":[{"name":"argo-cd","side":"current"},{"name":"grafana","side":"latest"}]}
                        """)
                .when()
                .post("/api/v1/scrape/applications")
                .then()
                .statusCode(200)
                .body("outcome", equalTo("SCRAPED"))
                .body("targetResults.size()", equalTo(2))
                .body("targetResults[0].name", equalTo("argo-cd"))
                .body("targetResults[0].succeeded", equalTo(true))
                .body("triggersRemaining", equalTo(9))
                .body("windowResetsInSeconds", equalTo(1800));
    }

    @Test
    void postScrapeApplications_unmonitoredTarget_includesFailedResultInBody() {
        when(applicationVersionPort.targetedScrape(any())).thenReturn(
                ScrapeStatus.scraped(
                        List.of(TargetResult.failure("nonexistent-app", Side.BOTH, "not monitored")),
                        5,
                        900));

        given()
                .contentType("application/json")
                .body("""
                        {"targets":[{"name":"nonexistent-app","side":"both"}]}
                        """)
                .when()
                .post("/api/v1/scrape/applications")
                .then()
                .statusCode(200)
                .body("targetResults[0].succeeded", equalTo(false))
                .body("targetResults[0].reason", equalTo("not monitored"));
    }

    @Test
    void postScrapeApplications_inProgress_returns200WithInProgressOutcome() {
        when(applicationVersionPort.targetedScrape(any())).thenReturn(ScrapeStatus.inProgress());

        given()
                .contentType("application/json")
                .body("""
                        {"targets":[{"name":"argo-cd","side":"both"}]}
                        """)
                .when()
                .post("/api/v1/scrape/applications")
                .then()
                .statusCode(200)
                .body("outcome", equalTo("IN_PROGRESS"));
    }

    @Test
    void postScrapeApplications_rateLimited_returns429WithRetryAfterHeaderAndBody() {
        when(applicationVersionPort.targetedScrape(any())).thenReturn(ScrapeStatus.rateLimited(42));

        given()
                .contentType("application/json")
                .body("""
                        {"targets":[{"name":"argo-cd","side":"both"}]}
                        """)
                .when()
                .post("/api/v1/scrape/applications")
                .then()
                .statusCode(429)
                .header("Retry-After", equalTo("42"))
                .body("outcome", equalTo("RATE_LIMITED"))
                .body("retryAfterSeconds", equalTo(42));
    }

    @Test
    void postScrapeApplications_returns503_whenScrapeStateUnavailable() {
        when(applicationVersionPort.targetedScrape(any()))
                .thenThrow(new ScrapeStateUnavailableException("valkey unreachable", new RuntimeException()));

        given()
                .contentType("application/json")
                .body("""
                        {"targets":[{"name":"argo-cd","side":"both"}]}
                        """)
                .when()
                .post("/api/v1/scrape/applications")
                .then()
                .statusCode(503);
    }

    @Test
    void postScrapeApplications_malformedBody_returns400() {
        given()
                .contentType("application/json")
                .body("""
                        {"targets": "not-a-list"}
                        """)
                .when()
                .post("/api/v1/scrape/applications")
                .then()
                .statusCode(400);
    }

    @Test
    void postScrapeApplications_unknownSide_returns400() {
        // 'side' binds to the Side enum: an illegal value fails deserialisation → 400, rather than the
        // controller throwing IllegalArgumentException (which would surface as 500).
        given()
                .contentType("application/json")
                .body("""
                        {"targets":[{"name":"argo-cd","side":"sideways"}]}
                        """)
                .when()
                .post("/api/v1/scrape/applications")
                .then()
                .statusCode(400);
    }

    @Test
    void postScrapeApplications_mixedCaseSide_isAcceptedCaseInsensitively() {
        when(applicationVersionPort.targetedScrape(any())).thenReturn(
                ScrapeStatus.scraped(List.of(TargetResult.success("argo-cd", Side.CURRENT)), 9, 1800));

        given()
                .contentType("application/json")
                .body("""
                        {"targets":[{"name":"argo-cd","side":"CuRReNt"}]}
                        """)
                .when()
                .post("/api/v1/scrape/applications")
                .then()
                .statusCode(200)
                .body("outcome", equalTo("SCRAPED"));
    }
}
