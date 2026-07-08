package org.yardship.integration.adapters.in.http;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ChangelogTemplates;
import org.yardship.core.ports.in.ApplicationVersionPort;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

/**
 * Issue 03 (SPA becomes an OIDC client, docs/adr/0028) — the backend half of the AC "dev-origin
 * requests with an Authorization header succeed under CORS". Runs under {@link DevCorsTestProfile},
 * which pins the {@code quarkus.http.cors.*} values the implementer's real {@code %dev}
 * application.yml block must produce (see that profile's Javadoc for why config-override pinning
 * substitutes for exercising {@code %dev} activation directly).
 *
 * <p>Proves two things only real HTTP/CORS wiring can reveal (a unit test around a fake router
 * cannot): (1) a genuine CORS PREFLIGHT (OPTIONS with Origin + Access-Control-Request-Headers)
 * against a real {@code /api/v1} route is answered with the dev origin allowed and
 * {@code authorization} accepted; (2) the ACTUAL GET that follows a successful preflight also
 * carries {@code Access-Control-Allow-Origin} on its response, so the browser does not block the
 * SPA from reading the body. Production is untouched — no test here runs under a prod-flavoured
 * profile, matching the same-origin-behind-{@code /api} deployment where no CORS is enabled.
 */
@QuarkusTest
@TestProfile(DevCorsTestProfile.class)
class DevCorsPreflightIT {

    @InjectMock
    ApplicationVersionPort applicationVersionPort;

    @InjectMock
    ChangelogTemplates changelogTemplates;

    @Test
    void preflight_fromDevOrigin_withAuthorizationHeader_isAllowed() {
        given()
                .header("Origin", DevCorsTestProfile.DEV_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
                .when()
                .options("/api/v1/version")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", equalTo(DevCorsTestProfile.DEV_ORIGIN))
                .header("Access-Control-Allow-Headers", containsStringIgnoringCase("authorization"));
    }

    @Test
    void actualGet_fromDevOrigin_afterPreflight_carriesAllowOriginHeader() {
        // The preflight alone doesn't prove the browser can read the real response — the
        // follow-up GET must ALSO carry Access-Control-Allow-Origin, or the browser blocks
        // the SPA from reading the body even though the preflight succeeded.
        when(applicationVersionPort.getApplications()).thenReturn(List.of());

        given()
                .header("Origin", DevCorsTestProfile.DEV_ORIGIN)
                .header("Authorization", "Bearer some-token")
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", equalTo(DevCorsTestProfile.DEV_ORIGIN));
    }

    @Test
    void preflight_fromUnknownOrigin_isNotGrantedTheDevOrigin() {
        // Guard against an overly-broad config (e.g. origins: "*") swallowing this AC: an
        // origin OTHER than the configured dev origin must not receive it back as the
        // allowed origin.
        given()
                .header("Origin", "http://evil.example.com")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
                .when()
                .options("/api/v1/version")
                .then()
                .header("Access-Control-Allow-Origin", org.hamcrest.Matchers.not(equalTo("http://evil.example.com")));
    }
}
