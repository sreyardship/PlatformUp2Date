package org.yardship.integration.adapters.in.http;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Pins the dev-only CORS config (docs/adr/0028) that lets the React SPA on
 * {@code localhost:3000} call {@code /api/v1} on the Quarkus backend at {@code localhost:8080}
 * with an {@code Authorization: Bearer}
 * header attached.
 *
 * <p>The production configuration uses a {@code %dev}-profile {@code quarkus.http.cors.*} block
 * in application.yml. A {@code @QuarkusTest} runs under the
 * "test" profile, not "dev", so it cannot exercise that {@code %dev} section directly. Rather
 * than fight Quarkus's profile activation inside the test harness, this profile pins the SAME
 * config shape via {@code getConfigOverrides()} — proving the values Quarkus needs (allowed dev
 * origin + the {@code authorization} header) produce a passing CORS preflight. This is a
 * deliberate substitute for testing {@code %dev} activation itself, which QuarkusTest cannot do.
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
