package org.yardship.adapters.in.auth;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import org.eclipse.microprofile.jwt.Claims;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adds the verified token's OWN client's Keycloak client roles —
 * {@code resource_access/<azp>/roles}, keyed by the token's {@code azp} (authorized party)
 * claim — to the {@link SecurityIdentity} (docs/adr/0028).
 *
 * <p>Quarkus's built-in role extraction covers the first two steps of the resolution chain this
 * app wants ({@code groups}, then Keycloak's {@code realm_access/roles}) but only consults
 * {@code resource_access/<client-id>/roles} when {@code quarkus.oidc.client-id} is configured —
 * and this app is a bearer-only RESOURCE SERVER (docs/adr/0026): it validates other clients'
 * tokens and never acts as an OIDC client itself, so it deliberately has no client-id to key that
 * lookup with. Any statically configured id would also be wrong for all but one caller: each
 * calling client (the SPA, MCP clients) mints tokens under its own {@code azp}. Keying the lookup
 * off the token's own {@code azp} instead lets an operator grant a surface role (e.g.
 * {@code pu2d-web}) as a CLIENT role on the calling client in Keycloak — the natural place to
 * scope an app-specific role in a shared realm — without this app ever learning client ids.
 *
 * <p>Together with the built-in extraction the effective chain is
 * {@code groups} → {@code realm_access/roles} → {@code resource_access/<azp>/roles} (this
 * augmentor UNIONS the azp client roles into whatever the built-in steps produced — roles are
 * additive grants, so combining sources can only widen what an operator explicitly granted).
 *
 * <p>Inert everywhere auth is off or inapplicable: anonymous identities and non-JWT principals
 * pass through untouched, as do tokens with no {@code azp}, no {@code resource_access}, or no
 * roles entry under their own {@code azp}. Claims are read from the ALREADY VERIFIED token —
 * augmentors run after signature/issuer/audience validation, so this adds no trust surface.
 */
@ApplicationScoped
public class AzpClientRolesAugmentor implements SecurityIdentityAugmentor {

    private static final String RESOURCE_ACCESS_CLAIM = "resource_access";
    private static final String ROLES_KEY = "roles";

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        return Uni.createFrom().item(withAzpClientRoles(identity));
    }

    private static SecurityIdentity withAzpClientRoles(SecurityIdentity identity) {
        if (identity.isAnonymous() || !(identity.getPrincipal() instanceof JsonWebToken jwt)) {
            return identity;
        }
        Set<String> azpClientRoles = azpClientRoles(jwt);
        if (azpClientRoles.isEmpty()) {
            return identity;
        }
        return QuarkusSecurityIdentity.builder(identity).addRoles(azpClientRoles).build();
    }

    private static Set<String> azpClientRoles(JsonWebToken jwt) {
        String azp = jwt.getClaim(Claims.azp.name());
        if (azp == null
                || !(jwt.getClaim(RESOURCE_ACCESS_CLAIM) instanceof JsonObject resourceAccess)
                || !(resourceAccess.get(azp) instanceof JsonObject azpClient)
                || !(azpClient.get(ROLES_KEY) instanceof JsonArray roles)) {
            return Set.of();
        }
        return roles.stream()
                .filter(role -> role.getValueType() == JsonValue.ValueType.STRING)
                .map(role -> ((JsonString) role).getString())
                .collect(Collectors.toSet());
    }
}
