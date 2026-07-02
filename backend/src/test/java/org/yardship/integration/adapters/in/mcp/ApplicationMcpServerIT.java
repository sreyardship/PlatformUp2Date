package org.yardship.integration.adapters.in.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionApplication;

import java.util.Optional;

import java.time.Instant;
import org.yardship.core.ports.in.ApplicationVersionPort;
import org.yardship.core.ports.in.ScrapeStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * System test exercising the MCP Streamable HTTP endpoint end-to-end through a real MCP client
 * ({@link McpAssured}). The endpoint is served STATELESS at /api/mcp (a single endpoint, no
 * /sse) via {@code quarkus.mcp.server.http.root-path} (see docs/adr/0004); McpAssured does not
 * read that config, so the path is set explicitly below with {@code setMcpPath}. The extension
 * auto-discovers the {@code @Tool}-annotated beans; this test asserts the wiring works:
 *   - tools/list exposes all four tools with the expected registration and descriptions,
 *   - rate-limited outcomes are conveyed via the payload field, NOT a JSON-RPC protocol error.
 *
 * Business logic (filtering, pass-through, side-parsing) is owned by ApplicationMcpToolsTests.
 * The inbound port is mocked so the result is deterministic and no real HTTP scrape runs.
 *
 * McpAssured 1.13.0 API (verified against the jar):
 *   McpAssured.newStreamableClient() -> McpStreamableTestClient.Builder; setMcpPath(String)
 *   client.when().toolsList(Consumer<ToolsPage>)...
 *   client.when().toolsCall(name, Map args, Consumer<ToolResponse>)...
 *   ...thenAssertResults();
 * ToolsPage.findByName(String) -> ToolInfo (null if absent).
 * ToolResponse.isError() / .firstContent().asText().text() yields the JSON payload text;
 * a POJO tool return is serialized into that text payload (and structuredContent()).
 */
@QuarkusTest
public class ApplicationMcpServerIT {

    @InjectMock
    ApplicationVersionPort applicationVersionPort;

    private static final Instant NOW = Instant.parse("2026-07-01T10:00:00Z");

    private void stubMixedApplications() {
        VersionApplication majorBehind = new VersionApplication("argo-cd",
                SideObservation.resolved(new SemverVersion("1.1.1"), NOW),
                SideObservation.resolved(new SemverVersion("2.2.2"), NOW));
        VersionApplication current = new VersionApplication("gitea",
                SideObservation.resolved(new SemverVersion("2.0.0"), NOW),
                SideObservation.resolved(new SemverVersion("2.0.0"), NOW));
        when(applicationVersionPort.getApplications())
                .thenReturn(List.of(majorBehind, current));
    }

    @Test
    void toolsList_exposesBothApplicationTools() {
        stubMixedApplications();

        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .build()
                .connect();
        client.when()
                .toolsList(page -> {
                    assertNotNull(page.findByName("list_outdated_applications"),
                            "list_outdated_applications tool must be registered");
                    assertNotNull(page.findByName("get_application"),
                            "get_application tool must be registered");
                })
                .thenAssertResults();
    }

    @Test
    void toolsList_exposesTriggerScrapeTool() {
        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .build()
                .connect();
        client.when()
                .toolsList(page -> assertNotNull(page.findByName("trigger_scrape"),
                        "trigger_scrape tool must be registered"))
                .thenAssertResults();
    }

    @Test
    void toolsCall_triggerScrape_rateLimited_isNotAnErrorAndCarriesRetryAfter() {
        when(applicationVersionPort.triggerScrape())
                .thenReturn(ScrapeStatus.rateLimited(42));

        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .build()
                .connect();
        client.when()
                .toolsCall("trigger_scrape", response -> {
                    assertFalse(response.isError(),
                            "rate-limited result must be conveyed via the outcome field, "
                                    + "not as a protocol error");
                    String text = response.firstContent().asText().text();
                    assertTrue(text.contains("RATE_LIMITED"),
                            "payload must carry the RATE_LIMITED outcome: " + text);
                    assertTrue(text.contains("\"retryAfterSeconds\":42"),
                            "payload must carry retryAfterSeconds=42: " + text);
                })
                .thenAssertResults();
    }

    @Test
    void toolsList_exposesScrapeApplicationsTool() {
        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .build()
                .connect();
        client.when()
                .toolsList(page -> assertNotNull(page.findByName("scrape_applications"),
                        "scrape_applications tool must be registered"))
                .thenAssertResults();
    }

    @Test
    void toolsList_scrapeApplicationsDescription_documentsTelemetryOnlyFollowUpAndRateLimit() {
        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .build()
                .connect();
        client.when()
                .toolsList(page -> {
                    String description = page.findByName("scrape_applications").description().toLowerCase();
                    assertTrue(
                            description.contains("get_application")
                                    || description.contains("list_outdated_applications"),
                            "description must point the agent to a follow-up read: " + description);
                    assertTrue(description.contains("telemetry"),
                            "description must say the tool is telemetry-only: " + description);
                    assertTrue(description.contains("rate") || description.contains("budget"),
                            "description must call out the targeted rate limit: " + description);
                    assertTrue(description.contains("separate") || description.contains("distinct"),
                            "description must say the targeted budget is separate from trigger_scrape's: "
                                    + description);
                })
                .thenAssertResults();
    }

    // === Issue 05: list_applications_with_failed_scrapes + get_application freshness fields ======

    /** A side with a prior success that was then followed by a newer failure. */
    private SideObservation failedRefreshSide(String version) {
        Instant successAt = NOW.minusSeconds(60);
        Instant failureAt = NOW;
        return new SideObservation(
                Optional.of(new SemverVersion(version)),
                Optional.of(successAt),
                Optional.of(failureAt));
    }

    /** Never-succeeded-failed: no value, no prior success, has a failure stamp. */
    private SideObservation neverSucceededFailedSide() {
        return new SideObservation(Optional.empty(), Optional.empty(), Optional.of(NOW));
    }

    @Test
    void toolsList_exposesListApplicationsWithFailedScrapesTool() {
        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .build()
                .connect();
        client.when()
                .toolsList(page -> assertNotNull(page.findByName("list_applications_with_failed_scrapes"),
                        "list_applications_with_failed_scrapes tool must be registered"))
                .thenAssertResults();
    }

    @Test
    void toolsList_listApplicationsWithFailedScrapesDescription_attributesFailureToScrapeNotApp() {
        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .build()
                .connect();
        client.when()
                .toolsList(page -> {
                    String description = page.findByName("list_applications_with_failed_scrapes")
                            .description().toLowerCase();
                    assertTrue(description.contains("scrape") || description.contains("read"),
                            "description must attribute failure to the scrape, not the app: " + description);
                    assertFalse(description.contains("unhealthy") || description.contains("down"),
                            "description must not call the application unhealthy or down: " + description);
                })
                .thenAssertResults();
    }

    @Test
    void toolsCall_listApplicationsWithFailedScrapes_returnsFailedApp() {
        // Only the failed-refresh app must appear — the fresh (current) app must be excluded.
        VersionApplication failedApp = new VersionApplication("vault",
                failedRefreshSide("1.0.0"),
                SideObservation.resolved(new SemverVersion("1.1.0"), NOW));
        when(applicationVersionPort.getApplications())
                .thenReturn(List.of(failedApp, stubMixedApplications_justCurrentApp()));

        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .build()
                .connect();
        client.when()
                .toolsCall("list_applications_with_failed_scrapes", response -> {
                    assertFalse(response.isError(),
                            "list_applications_with_failed_scrapes must not error for a normal call");
                    String text = response.firstContent().asText().text();
                    assertTrue(text.contains("vault"),
                            "failed-refresh app 'vault' must appear in the result: " + text);
                    assertFalse(text.contains("gitea"),
                            "fresh app 'gitea' must not appear in the failed-scrapes list: " + text);
                })
                .thenAssertResults();
    }

    @Test
    void toolsCall_getApplication_resolvedApp_exposesFreshnessFields() {
        stubMixedApplications();

        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .build()
                .connect();
        client.when()
                .toolsCall("get_application", Map.of("name", "argo-cd"), response -> {
                    assertFalse(response.isError(), "get_application must not error for a known app");
                    String text = response.firstContent().asText().text();
                    // resolution field must be present
                    assertTrue(text.contains("\"resolution\""),
                            "get_application response must include the 'resolution' field: " + text);
                    assertTrue(text.contains("Resolved"),
                            "get_application response must carry resolution=Resolved for a resolved app: " + text);
                    // per-side read instants must be present
                    assertTrue(text.contains("currentReadAt") || text.contains("\"currentReadAt\""),
                            "get_application response must include currentReadAt: " + text);
                    assertTrue(text.contains("latestReadAt") || text.contains("\"latestReadAt\""),
                            "get_application response must include latestReadAt: " + text);
                })
                .thenAssertResults();
    }

    @Test
    void toolsCall_getApplication_failedRefreshApp_exposesFailedAt() {
        VersionApplication failedApp = new VersionApplication("vault",
                failedRefreshSide("1.0.0"),
                SideObservation.resolved(new SemverVersion("1.1.0"), NOW));
        when(applicationVersionPort.getApplications()).thenReturn(List.of(failedApp));

        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .build()
                .connect();
        client.when()
                .toolsCall("get_application", Map.of("name", "vault"), response -> {
                    assertFalse(response.isError());
                    String text = response.firstContent().asText().text();
                    assertTrue(text.contains("currentFailedAt") || text.contains("\"currentFailedAt\""),
                            "get_application response must include currentFailedAt for a failed-refresh side: "
                                    + text);
                    // must NOT carry a reason field
                    assertFalse(text.toLowerCase().contains("\"reason\""),
                            "get_application response must not carry a reason field — reason stays in logs: " + text);
                })
                .thenAssertResults();
    }

    /** Returns the 'gitea' (fresh/current) app from the standard stub — used for isolation. */
    private VersionApplication stubMixedApplications_justCurrentApp() {
        return new VersionApplication("gitea",
                SideObservation.resolved(new SemverVersion("2.0.0"), NOW),
                SideObservation.resolved(new SemverVersion("2.0.0"), NOW));
    }

    @Test
    void toolsCall_scrapeApplications_rateLimited_isNotAnErrorAndCarriesRetryAfter() {
        when(applicationVersionPort.targetedScrape(any())).thenReturn(ScrapeStatus.rateLimited(30));

        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .build()
                .connect();
        client.when()
                .toolsCall(
                        "scrape_applications",
                        Map.of("targets", List.of(Map.of("name", "argo-cd", "side", "current"))),
                        response -> {
                            assertFalse(response.isError(),
                                    "rate-limited result must be conveyed via the outcome field, "
                                            + "not as a protocol error");
                            String text = response.firstContent().asText().text();
                            assertTrue(text.contains("RATE_LIMITED"),
                                    "payload must carry the RATE_LIMITED outcome: " + text);
                            assertTrue(text.contains("\"retryAfterSeconds\":30"),
                                    "payload must carry retryAfterSeconds=30: " + text);
                        })
                .thenAssertResults();
    }
}
