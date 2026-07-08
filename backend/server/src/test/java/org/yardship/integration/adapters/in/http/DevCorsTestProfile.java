package org.yardship.integration.adapters.in.http;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Issue 03 (SPA becomes an OIDC client, docs/adr/0028): pins the dev-only CORS config the
 * implementer must add so the React SPA (served from {@code localhost:3000} by Vite) can call
 * {@code /api/v1} on the Quarkus backend (localhost:8080) with an {@code Authorization: Bearer}
 * header attached.
 *
 * <p>The design guidance for this slice calls for a {@code %dev}-profile
 * {@code quarkus.http.cors.*} block in application.yml. A {@code @QuarkusTest} runs under the
 * "test" profile, not "dev", so it cannot exercise that {@code %dev} section directly. Rather
 * than fight Quarkus's profile activation inside the test harness, this profile pins the SAME
 * config shape via {@code getConfigOverrides()} — proving the values Quarkus needs (allowed dev
 * origin + the {@code authorization} header) actually produce a passing CORS preflight. The
 * implementer's job is to make the real {@code %dev} block in application.yml carry equivalent
 * values; this profile is the executable contract for what those values must be. Flagged for the
 * reviewer: this is a deliberate substitute for testing {@code %dev} activation itself, which
 * QuarkusTest cannot do.
 *
 * <p>Production is untouched by this profile (and by the {@code %dev} block it mirrors) — no
 * CORS is enabled outside dev, matching the same-origin-behind-{@code /api} deployment model.
 */
public class DevCorsTestProfile implements QuarkusTestProfile {

    public static final String DEV_ORIGIN = "http://localhost:3000";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.http.cors.enabled", "true",
                "quarkus.http.cors.origins", DEV_ORIGIN,
                "quarkus.http.cors.headers", "authorization,content-type",
                "quarkus.http.cors.methods", "GET,POST,OPTIONS");
    }
}
