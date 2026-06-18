package org.yardship.integration.adapters.in.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.Side;
import org.yardship.core.domain.primitives.TargetResult;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;
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
 *   - tools/list exposes both list_outdated_applications and get_application,
 *   - tools/call list_outdated_applications returns the outdated apps and omits current ones.
 *
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

    private void stubMixedApplications() {
        VersionApplication majorBehind = new VersionApplication("argo-cd",
                new Version("1.1.1"), new Version("2.2.2"));
        VersionApplication current = new VersionApplication("gitea",
                new Version("2.0.0"), new Version("2.0.0"));
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
    void toolsCall_listOutdatedApplications_returnsOutdatedAndOmitsCurrent() {
        stubMixedApplications();

        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .build()
                .connect();
        client.when()
                .toolsCall("list_outdated_applications", response -> {
                    assertFalse(response.isError(), "tool call must succeed");
                    String text = response.firstContent().asText().text();
                    assertTrue(text.contains("argo-cd"),
                            "outdated app must be present in payload: " + text);
                    assertFalse(text.contains("gitea"),
                            "current app must be omitted from payload: " + text);
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
    void toolsCall_triggerScrape_scraped_returnsOutcomeAndCounts() {
        when(applicationVersionPort.triggerScrape())
                .thenReturn(ScrapeStatus.scraped(3, 0, 9, 60));

        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .build()
                .connect();
        client.when()
                .toolsCall("trigger_scrape", response -> {
                    assertFalse(response.isError(), "successful scrape must not be an error");
                    String text = response.firstContent().asText().text();
                    assertTrue(text.contains("SCRAPED"),
                            "payload must carry the SCRAPED outcome: " + text);
                    assertTrue(text.contains("\"appsAttempted\":3"),
                            "payload must carry the attempted count: " + text);
                    assertTrue(text.contains("\"triggersRemaining\":9"),
                            "payload must carry the remaining-trigger budget: " + text);
                })
                .thenAssertResults();
    }

    @Test
    void toolsCall_triggerScrape_scraped_payloadExposesPerAppTargetResults() {
        when(applicationVersionPort.triggerScrape()).thenReturn(
                ScrapeStatus.scraped(
                        2,
                        1,
                        9,
                        60,
                        List.of(
                                TargetResult.success("argo-cd", Side.BOTH),
                                TargetResult.failure("grafana", Side.BOTH, "github down"))));

        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .build()
                .connect();
        client.when()
                .toolsCall("trigger_scrape", response -> {
                    assertFalse(response.isError(), "successful scrape must not be an error");
                    String text = response.firstContent().asText().text();
                    assertTrue(text.contains("\"name\":\"argo-cd\""),
                            "payload must name the succeeding app: " + text);
                    assertTrue(text.contains("\"name\":\"grafana\""),
                            "payload must name the failing app: " + text);
                    assertTrue(text.contains("github down"),
                            "payload must carry the failure reason: " + text);
                    assertTrue(text.contains("\"side\":\"BOTH\""),
                            "a full-scrape target result must report side BOTH: " + text);
                })
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

    @Test
    void toolsCall_scrapeApplications_mixedSides_payloadExposesOutcomeTargetResultsAndBudget() {
        when(applicationVersionPort.targetedScrape(any())).thenReturn(
                ScrapeStatus.scraped(
                        List.of(
                                TargetResult.success("argo-cd", Side.CURRENT),
                                TargetResult.success("git-tea", Side.LATEST)),
                        9,
                        60));

        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .build()
                .connect();
        client.when()
                .toolsCall(
                        "scrape_applications",
                        Map.of(
                                "targets",
                                List.of(
                                        Map.of("name", "argo-cd", "side", "current"),
                                        Map.of("name", "git-tea", "side", "latest"))),
                        response -> {
                            assertFalse(response.isError(), "tool call must succeed");
                            String text = response.firstContent().asText().text();
                            assertTrue(text.contains("SCRAPED"),
                                    "payload must carry the SCRAPED outcome: " + text);
                            assertTrue(text.contains("\"name\":\"argo-cd\""),
                                    "payload must name the first target: " + text);
                            assertTrue(text.contains("\"side\":\"CURRENT\""),
                                    "payload must report the first target's side: " + text);
                            assertTrue(text.contains("\"name\":\"git-tea\""),
                                    "payload must name the second target: " + text);
                            assertTrue(text.contains("\"side\":\"LATEST\""),
                                    "payload must report the second target's side: " + text);
                            assertTrue(text.contains("\"triggersRemaining\":9"),
                                    "payload must carry the targeted budget's remaining count: " + text);
                            assertTrue(text.contains("\"windowResetsInSeconds\":60"),
                                    "payload must carry the targeted budget's window reset: " + text);
                        })
                .thenAssertResults();
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
