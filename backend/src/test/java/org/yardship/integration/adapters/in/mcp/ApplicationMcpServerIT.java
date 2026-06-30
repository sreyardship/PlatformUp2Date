package org.yardship.integration.adapters.in.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.SemverVersion;
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

    private void stubMixedApplications() {
        VersionApplication majorBehind = new VersionApplication("argo-cd",
                new SemverVersion("1.1.1"), new SemverVersion("2.2.2"));
        VersionApplication current = new VersionApplication("gitea",
                new SemverVersion("2.0.0"), new SemverVersion("2.0.0"));
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
