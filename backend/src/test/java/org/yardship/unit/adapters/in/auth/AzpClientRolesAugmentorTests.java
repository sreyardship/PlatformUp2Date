package org.yardship.unit.adapters.in.auth;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.in.auth.AzpClientRolesAugmentor;

import java.io.StringReader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit contract for {@link AzpClientRolesAugmentor} — the {@code resource_access/<azp>/roles}
 * step of the role-resolution chain (docs/adr/0028). Plain-Java seam: identities are built with
 * {@link QuarkusSecurityIdentity}, the token is a mocked {@link JsonWebToken}, no CDI/Quarkus
 * boot. The end-to-end proof against real Keycloak-minted tokens (claim types included) is
 * {@code WebAuthEnforcedIT}'s clara cases.
 */
class AzpClientRolesAugmentorTests {

    private final AzpClientRolesAugmentor augmentor = new AzpClientRolesAugmentor();

    private static JsonObject json(String literal) {
        return Json.createReader(new StringReader(literal)).readObject();
    }

    private static JsonWebToken tokenWith(String azp, JsonObject resourceAccess) {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getName()).thenReturn("clara");
        when(jwt.getClaim("azp")).thenReturn(azp);
        when(jwt.getClaim("resource_access")).thenReturn(resourceAccess);
        return jwt;
    }

    private static SecurityIdentity identityOf(JsonWebToken jwt, String... existingRoles) {
        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder().setPrincipal(jwt);
        for (String role : existingRoles) {
            builder.addRole(role);
        }
        return builder.build();
    }

    private SecurityIdentity augment(SecurityIdentity identity) {
        return augmentor.augment(identity, null).await().indefinitely();
    }

    @Test
    void addsTheAzpClientsRolesToTheIdentity() {
        JsonWebToken jwt = tokenWith("spa-client",
                json("{\"spa-client\":{\"roles\":[\"pu2d-web\",\"pu2d-mcp\"]}}"));

        SecurityIdentity augmented = augment(identityOf(jwt, "user"));

        assertEquals(Set.of("user", "pu2d-web", "pu2d-mcp"), augmented.getRoles());
    }

    @Test
    void ignoresRolesOfClientsOtherThanTheAzp() {
        JsonWebToken jwt = tokenWith("spa-client",
                json("{\"another-client\":{\"roles\":[\"pu2d-web\"]},\"spa-client\":{\"roles\":[]}}"));

        SecurityIdentity augmented = augment(identityOf(jwt, "user"));

        assertEquals(Set.of("user"), augmented.getRoles());
    }

    @Test
    void passesThroughUntouchedWhenTheTokenHasNoAzp() {
        JsonWebToken jwt = tokenWith(null, json("{\"spa-client\":{\"roles\":[\"pu2d-web\"]}}"));
        SecurityIdentity identity = identityOf(jwt, "user");

        assertSame(identity, augment(identity));
    }

    @Test
    void passesThroughUntouchedWhenTheTokenHasNoResourceAccess() {
        JsonWebToken jwt = tokenWith("spa-client", null);
        SecurityIdentity identity = identityOf(jwt, "user");

        assertSame(identity, augment(identity));
    }

    @Test
    void passesThroughUntouchedWhenTheAzpHasNoResourceAccessEntry() {
        JsonWebToken jwt = tokenWith("spa-client", json("{\"another-client\":{\"roles\":[\"pu2d-web\"]}}"));
        SecurityIdentity identity = identityOf(jwt, "user");

        assertSame(identity, augment(identity));
    }

    @Test
    void passesThroughAnonymousIdentities() {
        SecurityIdentity anonymous = QuarkusSecurityIdentity.builder().setAnonymous(true).build();

        assertSame(anonymous, augment(anonymous));
    }

    @Test
    void passesThroughNonJwtPrincipals() {
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(() -> "basic-user")
                .addRole("user")
                .build();

        assertSame(identity, augment(identity));
    }
}
