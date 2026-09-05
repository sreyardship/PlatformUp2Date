package org.yardship.unit.adapters.out.versionsource.current.httpprometheus;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader.VersionSource.Auth;
import org.yardship.adapters.out.versionsource.current.FailedCurrentSource;
import org.yardship.adapters.out.versionsource.current.httpprometheus.HttpPrometheusCurrentSource;
import org.yardship.adapters.out.versionsource.current.httpprometheus.HttpPrometheusCurrentSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpPrometheusCurrentSourceFactory} — the factory for the
 * {@code http-prometheus} current-version kind (ADR-0033). Verifies its discriminator, its own
 * STRUCTURAL config-fragment validation (a non-blank {@code url} and non-blank {@code metric} are
 * required — both THROW an {@link IllegalArgumentException} from {@code create()}, the declared
 * "this config fragment is unusable" signal {@code VersionSourceResolver} maps to a per-app
 * {@code ConfigError} at WARN rather than a boot failure, per ADR-0032), and that VALUE-level
 * {@code auth} / {@code ca-cert} problems are routed through the shared, kind-labelled
 * {@code HttpTransportConfig} collaborator into a {@link FailedCurrentSource} whose message names
 * {@code http-prometheus} (never {@code http}).
 *
 * <p>{@code version-label} defaulting to {@code version} FACTORY-SIDE (not {@code @WithDefault})
 * is exercised behaviorally at the integration level, where the built source can actually be run
 * against a fetched body; here we only prove the structural/value-level wiring, matching
 * {@code HttpHeaderCurrentSourceFactoryTests}' idiom and its own note that the exhaustive
 * {@code auth}/{@code ca-cert} matrix belongs to {@code HttpTransportConfigTests}, not here.
 */
class HttpPrometheusCurrentSourceFactoryTests {

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);
    private static final String URL = "https://blackbox.example.com/metrics";
    private static final String METRIC = "blackbox_exporter_build_info";

    private final HttpPrometheusCurrentSourceFactory factory = new HttpPrometheusCurrentSourceFactory();

    @Test
    void type_isHttpPrometheus() {
        assertEquals("http-prometheus", factory.type());
    }

    @Test
    void create_buildsAWorkingSource_forAMinimalValidFragment() {
        CurrentVersionSource result =
                factory.create(source(URL, METRIC, Optional.empty()), SEMVER_PARSER);

        assertInstanceOf(HttpPrometheusCurrentSource.class, result);
    }

    // --- structural: url --------------------------------------------------------------------

    @Test
    void create_throws_whenUrlIsAbsent() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.empty(), Optional.of(METRIC), Optional.empty()), SEMVER_PARSER));

        assertTrue(ex.getMessage().toLowerCase().contains("url"),
                "the validation error must mention the missing 'url'; was: " + ex.getMessage());
    }

    @Test
    void create_throws_whenUrlIsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of("   "), Optional.of(METRIC), Optional.empty()), SEMVER_PARSER));
    }

    // --- structural: metric -------------------------------------------------------------------

    @Test
    void create_throws_whenMetricIsAbsent() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of(URL), Optional.empty(), Optional.empty()), SEMVER_PARSER));

        assertTrue(ex.getMessage().toLowerCase().contains("metric"),
                "the validation error must mention the missing 'metric'; was: " + ex.getMessage());
    }

    @Test
    void create_throws_whenMetricIsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of(URL), Optional.of("   "), Optional.empty()), SEMVER_PARSER));
    }

    // --- value-level: auth --------------------------------------------------------------------

    @Test
    void create_withAnInvalidAuthValue_returnsAFailedCurrentSource_withAMessageNamingHttpPrometheus() {
        Auth basicMissingCredentials = auth("basic", Optional.empty(), Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth(URL, METRIC, basicMissingCredentials), SEMVER_PARSER);

        assertInstanceOf(FailedCurrentSource.class, result);
        IllegalStateException ex = assertThrows(IllegalStateException.class, result::version);
        assertTrue(ex.getMessage().contains("http-prometheus"),
                "the FailedCurrentSource message must name 'http-prometheus'; was: " + ex.getMessage());
    }

    @Test
    void create_withAValidAuthValue_buildsAWorkingSource() {
        Auth basic = auth("basic", Optional.of("prom-bot"), Optional.of("s3cr3t"));

        CurrentVersionSource result = factory.create(sourceWithAuth(URL, METRIC, basic), SEMVER_PARSER);

        assertInstanceOf(HttpPrometheusCurrentSource.class, result);
    }

    // --- value-level: ca-cert -------------------------------------------------------------------

    @Test
    void create_withABlankCaCert_returnsAFailedCurrentSource_withAMessageNamingHttpPrometheus() {
        CurrentVersionSource result = factory.create(sourceWithCaCert(URL, METRIC, "   "), SEMVER_PARSER);

        assertInstanceOf(FailedCurrentSource.class, result);
        IllegalStateException ex = assertThrows(IllegalStateException.class, result::version);
        assertTrue(ex.getMessage().contains("http-prometheus"),
                "the FailedCurrentSource message must name 'http-prometheus'; was: " + ex.getMessage());
    }

    @Test
    void create_withAMissingCaCertFile_returnsAFailedCurrentSource() {
        CurrentVersionSource result = factory.create(
                sourceWithCaCert(URL, METRIC, "/no/such/path/ca.crt"), SEMVER_PARSER);

        assertInstanceOf(FailedCurrentSource.class, result);
    }

    // --- helpers -------------------------------------------------------------------------------

    private static ApplicationConfigLoader.VersionSource source(String url, String metric, Optional<String> versionLabel) {
        return source(Optional.of(url), Optional.of(metric), versionLabel);
    }

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> url, Optional<String> metric, Optional<String> versionLabel) {
        return new FakeVersionSource(url, metric, versionLabel, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource sourceWithAuth(String url, String metric, Auth auth) {
        return new FakeVersionSource(Optional.of(url), Optional.of(metric), Optional.empty(), Optional.of(auth),
                Optional.empty(), Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource sourceWithCaCert(String url, String metric, String caCert) {
        return new FakeVersionSource(Optional.of(url), Optional.of(metric), Optional.empty(), Optional.empty(),
                Optional.of(caCert), Optional.empty());
    }

    private static Auth auth(String type, Optional<String> username, Optional<String> password) {
        return new Auth() {
            @Override
            public Optional<String> type() {
                return Optional.of(type);
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
                return Optional.empty();
            }

            @Override
            public Optional<String> tokenFile() {
                return Optional.empty();
            }
        };
    }

    /**
     * Fully implements {@link ApplicationConfigLoader.VersionSource}, defaulting every field this
     * test class does not vary to {@link Optional#empty()}. {@code type()} is fixed to
     * {@code "http-prometheus"}.
     */
    private static final class FakeVersionSource implements ApplicationConfigLoader.VersionSource {
        private final Optional<String> url;
        private final Optional<String> metric;
        private final Optional<String> versionLabel;
        private final Optional<Auth> auth;
        private final Optional<String> caCert;
        private final Optional<Boolean> insecureSkipTlsVerify;

        FakeVersionSource(Optional<String> url, Optional<String> metric, Optional<String> versionLabel,
                Optional<Auth> auth, Optional<String> caCert, Optional<Boolean> insecureSkipTlsVerify) {
            this.url = url;
            this.metric = metric;
            this.versionLabel = versionLabel;
            this.auth = auth;
            this.caCert = caCert;
            this.insecureSkipTlsVerify = insecureSkipTlsVerify;
        }

        @Override
        public Optional<String> type() {
            return Optional.of("http-prometheus");
        }

        @Override
        public Optional<String> url() {
            return url;
        }

        @Override
        public Optional<String> metric() {
            return metric;
        }

        @Override
        public Optional<String> versionLabel() {
            return versionLabel;
        }

        @Override
        public Optional<String> versionHeader() {
            return Optional.empty();
        }

        @Override
        public Optional<String> regex() {
            return Optional.empty();
        }

        @Override
        public Optional<Boolean> stripPrerelease() {
            return Optional.empty();
        }

        @Override
        public Optional<Auth> auth() {
            return auth;
        }

        @Override
        public Optional<String> caCert() {
            return caCert;
        }

        @Override
        public Optional<Boolean> insecureSkipTlsVerify() {
            return insecureSkipTlsVerify;
        }

        @Override
        public Optional<String> repo() {
            return Optional.empty();
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
            return Optional.empty();
        }

        @Override
        public Optional<Integer> pageSize() {
            return Optional.empty();
        }

        @Override
        public Optional<String> host() {
            return Optional.empty();
        }

        @Override
        public Optional<Integer> port() {
            return Optional.empty();
        }

        @Override
        public Optional<String> user() {
            return Optional.empty();
        }

        @Override
        public Optional<String> privateKey() {
            return Optional.empty();
        }

        @Override
        public Optional<String> privateKeyFile() {
            return Optional.empty();
        }

        @Override
        public Optional<String> hostKey() {
            return Optional.empty();
        }

        @Override
        public Optional<String> knownHosts() {
            return Optional.empty();
        }

        @Override
        public Optional<String> releaseField() {
            return Optional.empty();
        }

        @Override
        public Optional<Integer> maxTags() {
            return Optional.empty();
        }

        @Override
        public Optional<String> prereleaseFilter() {
            return Optional.empty();
        }

        @Override
        public Optional<String> registry() {
            return Optional.empty();
        }
    }
}
