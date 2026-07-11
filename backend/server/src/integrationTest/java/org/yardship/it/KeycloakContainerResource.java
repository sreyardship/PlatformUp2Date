package org.yardship.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Boots a real Keycloak container (Testcontainers {@link GenericContainer}, not Dev Services) for
 * the native auth-on MCP smoke test (issue 04, docs/adr/0026).
 *
 * <p>Keycloak Dev Services cannot be reused here the way {@code SurfaceAuthTestProfile} (JVM
 * {@code @QuarkusTest} suite) reuses it: that profile derives {@code OIDC_ISSUER} from the
 * Dev-Services-assigned {@code quarkus.oidc.auth-server-url} via a SmallRye property expression
 * resolved lazily inside the launched JVM. A {@code @QuarkusIntegrationTest} artifact (a
 * standalone process — the native binary in CI) is launched with a fixed environment computed
 * *before* the process starts; there is no in-process config-resolution step where a
 * `${quarkus.oidc.auth-server-url}` expression could still be lazily substituted, and Dev
 * Services' own trigger additionally depends on build-time tenant-enabled wiring that doesn't
 * apply to an already-built artifact. So this resource manager starts its own Keycloak container
 * directly and hands the launched process a concrete, already-resolved issuer URL instead.
 *
 * <p>Image pinned to the same tag ({@code quay.io/keycloak/keycloak:26.5.7}) that Quarkus'
 * {@code quarkus-devservices-keycloak} module defaults to, so the auth-on native smoke stays on
 * the same Keycloak version as the JVM auth-on suite's Dev-Services container. The realm import
 * ({@code mcp-oidc-test-realm.json}, copied into {@code src/integrationTest/resources} from the
 * slice-01 fixture in {@code src/test/resources}) defines the same two clients: {@code mcp-client}
 * (audience {@code mcp-api}) and {@code other-client} (audience {@code other-api}), user
 * {@code alice}/{@code alice} (carrying the {@code pu2d-mcp} realm role), and user
 * {@code bob}/{@code bob} (without it, for the 403 case).
 */
public class KeycloakContainerResource implements QuarkusTestResourceLifecycleManager {

    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.5.7";
    private static final int KEYCLOAK_PORT = 8080;
    private static final String REALM_NAME = "mcp-oidc-test";

    public static final String AUDIENCE = "mcp-api";
    public static final String MCP_ROLE = "pu2d-mcp";

    /**
     * The issuer URL handed to the launched artifact (via {@code OIDC_ISSUER}), also exposed
     * to the test JVM itself: the resource manager and the {@code @QuarkusIntegrationTest} class
     * run in the same (test) JVM, only the artifact under test is a separate process, so the test
     * class needs this value directly (e.g. to fetch a token via password grant) rather than via
     * any config mechanism scoped to the launched process.
     */
    private static volatile String issuerUrl;

    private GenericContainer<?> keycloak;
    private WireMockServer wireMockServer;

    public static String issuer() {
        return issuerUrl;
    }

    @Override
    @SuppressWarnings("resource")
    public Map<String, String> start() {
        keycloak = new GenericContainer<>(DockerImageName.parse(KEYCLOAK_IMAGE))
                .withExposedPorts(KEYCLOAK_PORT)
                .withEnv("KEYCLOAK_ADMIN", "admin")
                .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("mcp-oidc-test-realm.json"),
                        "/opt/keycloak/data/import/mcp-oidc-test-realm.json")
                .withCommand("start-dev", "--import-realm")
                .waitingFor(Wait.forHttp("/realms/" + REALM_NAME)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        keycloak.start();

        // The issuer host has to be one string that works from three vantage points at once: the
        // launched artifact (which fetches OIDC discovery/JWKS), this test JVM (which fetches a
        // token by password grant), and Keycloak itself (dev mode derives the token's `iss` claim
        // from the request host, so it must match what the artifact validates against). As a host
        // process everything is localhost; as the shipped container image (the native PR job) the
        // artifact can't reach localhost, so CI sets PU2D_IT_CALLBACK_HOST=host.docker.internal,
        // adds the matching --add-host to the container, and maps host.docker.internal to loopback
        // on the runner so this test JVM resolves the very same issuer string. Keycloak's published
        // port is reachable through the host gateway, so the mapped port stays correct either way.
        // Defaults to the Testcontainers host so host-process runs are unchanged.
        String callbackHost = System.getenv().getOrDefault("PU2D_IT_CALLBACK_HOST", keycloak.getHost());

        issuerUrl = "http://" + callbackHost + ":" + keycloak.getMappedPort(KEYCLOAK_PORT)
                + "/realms/" + REALM_NAME;

        // platform-config is unset in the default/prod profile the launched artifact boots under
        // (it is only pre-populated for %dev, and prod expects an operator-mounted ConfigMap) —
        // required (scrape-interval has no fallback), so this resource supplies a minimal one-app
        // WireMock-backed config, mirroring WireMockVersionResource. This IT is not exercising
        // scrape/business logic (that's ApplicationVersionResourceIT's job) — it only needs the
        // app to boot so /api/mcp can be reached and tools/list can be called authenticated.
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":\"1.0.0\"}")));
        int httpPort = wireMockServer.port();

        return Map.of(
                "OIDC_ISSUER", issuerUrl,
                "OIDC_AUDIENCE", AUDIENCE,
                "MCP_OIDC_ROLE", MCP_ROLE,
                "platform-config.scrape-interval", "1h",
                "platform-config.apps[0].name", "auth-smoke-app",
                "platform-config.apps[0].current.type", "http",
                "platform-config.apps[0].current.url", "http://" + callbackHost + ":" + httpPort + "/current",
                "platform-config.apps[0].latest.type", "http-regex",
                "platform-config.apps[0].latest.url", "http://" + callbackHost + ":" + httpPort + "/current",
                "platform-config.apps[0].latest.regex", "(\\d+\\.\\d+\\.\\d+)");
    }

    @Override
    public void stop() {
        if (keycloak != null) {
            keycloak.stop();
        }
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }
}
