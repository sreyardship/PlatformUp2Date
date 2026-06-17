package org.yardship.integration.adapters.in.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.in.ApplicationVersionPort;
import org.yardship.core.ports.in.ScrapeStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
