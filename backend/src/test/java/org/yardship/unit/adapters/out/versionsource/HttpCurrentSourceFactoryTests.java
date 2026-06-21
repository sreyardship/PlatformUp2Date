package org.yardship.unit.adapters.out.versionsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionclient.ApplicationConfigLoader;
import org.yardship.adapters.out.versionclient.ApplicationConfigLoader.VersionSource.Auth;
import org.yardship.adapters.out.versionclient.BasicAuthFilter;
import org.yardship.adapters.out.versionclient.BearerAuthFilter;
import org.yardship.adapters.out.versionclient.CurrentVersionClient;
import org.yardship.adapters.out.versionclient.CurrentVersionClientFactory;
import org.yardship.adapters.out.versionsource.FailedCurrentSource;
import org.yardship.adapters.out.versionsource.HttpCurrentSource;
import org.yardship.adapters.out.versionsource.HttpCurrentSourceFactory;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpCurrentSourceFactory} — the factory for the {@code http} current-version
 * kind. Verifies its discriminator, its own config-fragment validation (the {@code http} kind
 * requires a {@code url}; {@code version-key} — if present — must be a syntactically valid JSON
 * Pointer), and — new in this slice — that it builds the {@link CurrentVersionClient} EAGERLY during
 * {@code create(cfg)} via an injected {@link CurrentVersionClientFactory} collaborator, rather than
 * lazily inside the source. A FAKE collaborator is used so this stays a true POJO unit test: no Arc,
 * no {@code @QuarkusTest}. The real, REST-client-backed collaborator is exercised at the integration
 * level ({@code CurrentVersionClientFactoryIT}).
 */
class HttpCurrentSourceFactoryTests {

    private final FakeCurrentVersionClientFactory clientFactory = new FakeCurrentVersionClientFactory();
    private final HttpCurrentSourceFactory factory = new HttpCurrentSourceFactory(clientFactory);

    @Test
    void type_isHttp() {
        assertEquals("http", factory.type());
    }

    @Test
    void create_buildsASource_whenUrlIsPresent() {
        assertNotNull(factory.create(source(Optional.of("http://localhost:8089/current"), Optional.empty(), Optional.empty())));
    }

    @Test
    void create_buildsTheClientEagerly_viaTheCollaborator_withTheResolvedUrl() {
        factory.create(source(Optional.of("http://localhost:8089/current"), Optional.empty(), Optional.empty()));

        assertEquals(1, clientFactory.buildCalls.size(),
                "the client must be built eagerly during create(cfg), not lazily on first version()");
        assertEquals("http://localhost:8089/current", clientFactory.buildCalls.get(0));
    }

    @Test
    void create_rejectsAbsentUrl_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.empty(), Optional.empty(), Optional.empty())));
        assertTrue(ex.getMessage().toLowerCase().contains("url"),
                "the validation error must mention the missing 'url'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsAbsentUrl_withoutInvokingTheCollaborator() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.empty(), Optional.empty(), Optional.empty())));

        assertTrue(clientFactory.buildCalls.isEmpty(),
                "validation must fail before any client is built");
    }

    @Test
    void create_rejectsBlankUrl_withAClearMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of("   "), Optional.empty(), Optional.empty())));
    }

    @Test
    void create_defaultsVersionKey_toSlashVersion_whenAbsent() {
        // No 'version-key' configured: the factory must still construct a source successfully,
        // defaulting the pointer to '/version' so existing {"version":"…"} endpoints keep working.
        assertNotNull(factory.create(
                source(Optional.of("http://localhost:8089/current"), Optional.empty(), Optional.empty())));
    }

    @Test
    void create_acceptsAConfiguredVersionKey() {
        assertNotNull(factory.create(
                source(Optional.of("http://localhost:8089/current"), Optional.of("/harbor_version"), Optional.empty())));
    }

    @Test
    void create_rejectsASyntacticallyInvalidVersionKey_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        source(Optional.of("http://localhost:8089/current"), Optional.of("harbor_version"), Optional.empty())));
        assertTrue(ex.getMessage().contains("harbor_version"),
                "the validation error must name the bad pointer; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsASyntacticallyInvalidVersionKey_withoutInvokingTheCollaborator() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        source(Optional.of("http://localhost:8089/current"), Optional.of("harbor_version"), Optional.empty())));

        assertTrue(clientFactory.buildCalls.isEmpty(),
                "version-key validation must fail before any client is built");
    }

    @Test
    void create_buildsASource_whenStripPrereleaseIsAbsent_defaultingToFalse() {
        // No 'strip-prerelease' configured: the factory must still construct a source successfully,
        // defaulting to false so prerelease segments are preserved for every existing app.
        assertNotNull(factory.create(
                source(Optional.of("http://localhost:8089/current"), Optional.empty(), Optional.empty())));
    }

    @Test
    void create_buildsASource_whenStripPrereleaseIsExplicitlyTrue() {
        assertNotNull(factory.create(
                source(Optional.of("http://localhost:8089/current"), Optional.empty(), Optional.of(true))));
    }

    @Test
    void create_buildsASource_whenStripPrereleaseIsExplicitlyFalse() {
        assertNotNull(factory.create(
                source(Optional.of("http://localhost:8089/current"), Optional.empty(), Optional.of(false))));
    }

    // --- Issue 02: auth resolution (Harbor case study) ----------------------------------------

    @Test
    void create_withNoAuth_stillBuildsAnHttpCurrentSource_withNoFilter() {
        // Existing (slice 01) behaviour must be preserved unchanged when 'auth' is absent.
        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/current", Optional.empty()));

        assertInstanceOf(HttpCurrentSource.class, result);
        assertEquals(1, clientFactory.buildCalls.size());
        assertEquals(Optional.empty(), clientFactory.lastAuthFilter);
    }

    @Test
    void create_withValidBasicAuth_buildsTheClient_withABasicAuthFilter() {
        Auth basic = auth("basic", Optional.of("harbor-bot"), Optional.of("s3cr3t"), Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/systeminfo", Optional.of(basic)));

        assertInstanceOf(HttpCurrentSource.class, result,
                "valid basic auth must still produce a real HttpCurrentSource, not a FailedCurrentSource");
        assertEquals(1, clientFactory.buildCalls.size());
        assertTrue(clientFactory.lastAuthFilter.isPresent(),
                "the collaborator must be called with a present auth filter for valid basic auth");
        assertInstanceOf(BasicAuthFilter.class, clientFactory.lastAuthFilter.get());
    }

    @Test
    void create_withUnknownAuthType_returnsAFailedCurrentSource_withoutInvokingTheCollaborator() {
        Auth unknown = auth("oauth2", Optional.of("user"), Optional.of("pass"), Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/systeminfo", Optional.of(unknown)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty(),
                "an unsupported auth type must fail before any client is built");
    }

    @Test
    void create_withBasicAuthMissingUsername_returnsAFailedCurrentSource() {
        Auth missingUsername = auth("basic", Optional.empty(), Optional.of("s3cr3t"), Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/systeminfo", Optional.of(missingUsername)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty());
    }

    @Test
    void create_withBasicAuthMissingPassword_returnsAFailedCurrentSource() {
        Auth missingPassword = auth("basic", Optional.of("harbor-bot"), Optional.empty(), Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/systeminfo", Optional.of(missingPassword)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty());
    }

    @Test
    void create_withBasicAuthBlankUsername_returnsAFailedCurrentSource() {
        // An unset env var (e.g. ${HARBOR_USER:}) resolves to "" via SmallRye expansion, not absent —
        // blank must be treated the same as missing.
        Auth blankUsername = auth("basic", Optional.of("   "), Optional.of("s3cr3t"), Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/systeminfo", Optional.of(blankUsername)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty());
    }

    @Test
    void create_withBasicAuthBlankPassword_returnsAFailedCurrentSource() {
        Auth blankPassword = auth("basic", Optional.of("harbor-bot"), Optional.of(""), Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/systeminfo", Optional.of(blankPassword)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty());
    }

    // --- Issue 03: bearer auth resolution -----------------------------------------------------

    @Test
    void create_withValidBearerAuth_buildsTheClient_withABearerAuthFilter() {
        Auth bearer = authWithToken("bearer", Optional.of("gh-token"));

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/current", Optional.of(bearer)));

        assertInstanceOf(HttpCurrentSource.class, result,
                "valid bearer auth must produce a real HttpCurrentSource, not a FailedCurrentSource");
        assertEquals(1, clientFactory.buildCalls.size());
        assertTrue(clientFactory.lastAuthFilter.isPresent(),
                "the collaborator must be called with a present auth filter for valid bearer auth");
        assertInstanceOf(BearerAuthFilter.class, clientFactory.lastAuthFilter.get());
    }

    @Test
    void create_withBearerAuthMissingToken_returnsAFailedCurrentSource_withoutInvokingTheCollaborator() {
        Auth missingToken = authWithToken("bearer", Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/current", Optional.of(missingToken)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty(),
                "a missing bearer token must fail before any client is built");
    }

    @Test
    void create_withBearerAuthBlankToken_returnsAFailedCurrentSource() {
        // An unset env var (e.g. ${GH_TOKEN:}) resolves to "" via SmallRye expansion, not absent —
        // blank must be treated the same as missing, consistent with the basic-auth branch.
        Auth blankToken = authWithToken("bearer", Optional.of(""));

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/current", Optional.of(blankToken)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty());
    }

    @Test
    void create_withBearerAuthWhitespaceToken_returnsAFailedCurrentSource() {
        Auth whitespaceToken = authWithToken("bearer", Optional.of("   "));

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/current", Optional.of(whitespaceToken)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty());
    }

    @Test
    void create_withFailedAuth_doesNotThrow_soOneBadAppCannotBlockTheOthersAtStartup() {
        // VersionSourceResolver builds every app's sources eagerly at CDI construction; a thrown
        // exception here (rather than a returned FailedCurrentSource) would take down the whole
        // resolver, defeating per-app isolation. create(cfg) must return, never throw, for a VALUE
        // problem in auth.
        Auth unknown = auth("oauth2", Optional.empty(), Optional.empty(), Optional.empty());

        assertNotNull(factory.create(sourceWithAuth("http://localhost:8089/systeminfo", Optional.of(unknown))));
    }

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> url, Optional<String> versionKey, Optional<Boolean> stripPrerelease) {
        return source(url, versionKey, stripPrerelease, Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource sourceWithAuth(String url, Optional<Auth> auth) {
        return source(Optional.of(url), Optional.empty(), Optional.empty(), auth);
    }

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> url, Optional<String> versionKey, Optional<Boolean> stripPrerelease,
            Optional<Auth> auth) {
        return new ApplicationConfigLoader.VersionSource() {
            @Override
            public String type() {
                return "http";
            }

            @Override
            public Optional<String> url() {
                return url;
            }

            @Override
            public Optional<String> namespace() {
                return Optional.empty();
            }

            @Override
            public Optional<String> workload() {
                return Optional.empty();
            }

            @Override
            public Optional<String> container() {
                return Optional.empty();
            }

            @Override
            public Optional<String> versionKey() {
                return versionKey;
            }

            @Override
            public Optional<Boolean> stripPrerelease() {
                return stripPrerelease;
            }

            @Override
            public Optional<Auth> auth() {
                return auth;
            }

            @Override
            public Optional<Integer> pageSize() {
                return Optional.empty();
            }
        };
    }

    private static Auth authWithToken(String type, Optional<String> token) {
        return auth(type, Optional.empty(), Optional.empty(), token);
    }

    private static Auth auth(
            String type, Optional<String> username, Optional<String> password, Optional<String> token) {
        return new Auth() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Optional<String> username() {
                return username;
            }

            @Override
            public Optional<String> password() {
                return password;
            }

            @Override
            public Optional<String> token() {
                return token;
            }
        };
    }

    /**
     * Fake {@link CurrentVersionClientFactory} collaborator: records every {@code build(url, filter)}
     * invocation (so tests can assert eagerness + the resolved url) and returns a trivial
     * {@link CurrentVersionClient} stub without touching Arc or the network.
     */
    private static class FakeCurrentVersionClientFactory extends CurrentVersionClientFactory {

        private final List<String> buildCalls = new ArrayList<>();
        private Optional<ClientRequestFilter> lastAuthFilter = Optional.empty();

        @Override
        public CurrentVersionClient build(String url, Optional<ClientRequestFilter> authFilter) {
            buildCalls.add(url);
            lastAuthFilter = authFilter;
            return stubClient();
        }

        private static CurrentVersionClient stubClient() {
            JsonNode empty = NullNode.getInstance();
            return () -> empty;
        }
    }
}
