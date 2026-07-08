package org.yardship.unit.adapters.out.versionsource.current.ssh;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.current.FailedCurrentSource;
import org.yardship.adapters.out.versionsource.current.ssh.SshOsReleaseCurrentSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SshOsReleaseCurrentSourceFactory} — the factory for the
 * {@code ssh-os-release} current-version kind.
 *
 * <p>Verifies the discriminator, boot-fail conditions (blank/absent host and user — the only
 * STRUCTURAL failures), and value-level failures (the mutual-exclusion constraints on
 * {@code private-key}/{@code private-key-file} and {@code host-key}/{@code known-hosts})
 * that are returned as {@link FailedCurrentSource} to preserve per-app isolation at startup.
 *
 * <p>No server connection is made here — the factory only validates config and builds the
 * source object. Actual SSH connectivity is exercised in {@code SshOsReleaseCurrentSourceIT}.
 *
 * <h2>Assumed factory API</h2>
 * <pre>{@code
 * public class SshOsReleaseCurrentSourceFactory implements CurrentVersionSourceFactory {
 *     public SshOsReleaseCurrentSourceFactory() {}   // no CDI collaborator needed
 *     public String type() { return "ssh-os-release"; }
 *     public CurrentVersionSource create(ApplicationConfigLoader.VersionSource cfg, VersionParser parser)
 * }
 * }</pre>
 *
 * <h2>Boot-fail vs value-fail split</h2>
 * <ul>
 *   <li><b>Boot-fail (throws {@link IllegalArgumentException})</b>: blank/absent {@code host};
 *       blank/absent {@code user}. These are STRUCTURAL missing required fields, mirroring
 *       how {@code HttpCurrentSourceFactory} throws on a blank {@code url}.</li>
 *   <li><b>Value-fail (returns {@link FailedCurrentSource})</b>: both {@code private-key} and
 *       {@code private-key-file} set; neither set; both {@code host-key} and {@code known-hosts}
 *       set; neither set. These are mutual-exclusion config problems that degrade one app without
 *       blocking startup for all others.</li>
 * </ul>
 *
 * <h2>New {@code VersionSource} config getter names assumed</h2>
 * <ul>
 *   <li>{@code Optional<String> host()} — YAML key {@code host}</li>
 *   <li>{@code Optional<Integer> port()} — YAML key {@code port}, default 22</li>
 *   <li>{@code Optional<String> user()} — YAML key {@code user}</li>
 *   <li>{@code Optional<String> privateKey()} — YAML key {@code private-key}</li>
 *   <li>{@code Optional<String> privateKeyFile()} — YAML key {@code private-key-file}</li>
 *   <li>{@code Optional<String> hostKey()} — YAML key {@code host-key}</li>
 *   <li>{@code Optional<String> knownHosts()} — YAML key {@code known-hosts}</li>
 *   <li>{@code Optional<String> releaseField()} — YAML key {@code release-field}</li>
 * </ul>
 */
class SshOsReleaseCurrentSourceFactoryTests {

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);

    // The factory has no CDI collaborators: it is constructed directly in tests.
    private final SshOsReleaseCurrentSourceFactory factory = new SshOsReleaseCurrentSourceFactory();

    // -----------------------------------------------------------------------
    // Type discriminator
    // -----------------------------------------------------------------------

    @Test
    void type_isSshOsRelease() {
        assertEquals("ssh-os-release", factory.type());
    }

    // -----------------------------------------------------------------------
    // Boot-fail: blank / absent host (structural required field)
    // -----------------------------------------------------------------------

    @Test
    void create_rejectsAbsentHost_withBootFail() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        minimalSsh().withHost(Optional.empty()).build(),
                        SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("host"),
                "the boot error must mention 'host'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsBlankHost_withBootFail() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        minimalSsh().withHost(Optional.of("   ")).build(),
                        SEMVER_PARSER));
    }

    // -----------------------------------------------------------------------
    // Boot-fail: blank / absent user (structural required field)
    // -----------------------------------------------------------------------

    @Test
    void create_rejectsAbsentUser_withBootFail() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        minimalSsh().withUser(Optional.empty()).build(),
                        SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("user"),
                "the boot error must mention 'user'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsBlankUser_withBootFail() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        minimalSsh().withUser(Optional.of("")).build(),
                        SEMVER_PARSER));
    }

    // -----------------------------------------------------------------------
    // Value-fail: private-key mutual exclusion
    // -----------------------------------------------------------------------

    @Test
    void create_withBothPrivateKeyAndPrivateKeyFile_returnsFailedCurrentSource() {
        // Both set is ambiguous — no precedence rule. Must return FailedCurrentSource, never throw.
        CurrentVersionSource result = factory.create(
                minimalSsh()
                        .withPrivateKey(Optional.of("-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n-----END OPENSSH PRIVATE KEY-----"))
                        .withPrivateKeyFile(Optional.of("/var/run/secrets/ssh/id_rsa"))
                        .build(),
                SEMVER_PARSER);

        assertInstanceOf(FailedCurrentSource.class, result,
                "both private-key and private-key-file must return FailedCurrentSource, not throw");
    }

    @Test
    void create_withBothPrivateKeyAndPrivateKeyFile_doesNotThrow() {
        // Isolation guarantee: a value-problem in one app's config must not throw out of create()
        // because the resolver builds all apps eagerly at CDI startup.
        assertDoesNotThrow(() -> factory.create(
                minimalSsh()
                        .withPrivateKey(Optional.of("inline-key"))
                        .withPrivateKeyFile(Optional.of("/path/to/key"))
                        .build(),
                SEMVER_PARSER));
    }

    @Test
    void create_withNeitherPrivateKeyNorPrivateKeyFile_returnsFailedCurrentSource() {
        // No credential at all — no TOFU. Must degrade to FailedCurrentSource, not throw.
        CurrentVersionSource result = factory.create(
                minimalSsh()
                        .withPrivateKey(Optional.empty())
                        .withPrivateKeyFile(Optional.empty())
                        .build(),
                SEMVER_PARSER);

        assertInstanceOf(FailedCurrentSource.class, result,
                "neither private-key nor private-key-file must return FailedCurrentSource, not throw");
    }

    @Test
    void create_withNeitherPrivateKeyNorPrivateKeyFile_doesNotThrow() {
        assertDoesNotThrow(() -> factory.create(
                minimalSsh()
                        .withPrivateKey(Optional.empty())
                        .withPrivateKeyFile(Optional.empty())
                        .build(),
                SEMVER_PARSER));
    }

    // -----------------------------------------------------------------------
    // Value-fail: host-key / known-hosts mutual exclusion
    // -----------------------------------------------------------------------

    @Test
    void create_withBothHostKeyAndKnownHosts_returnsFailedCurrentSource() {
        // Both set is ambiguous — no precedence rule.
        CurrentVersionSource result = factory.create(
                minimalSsh()
                        .withHostKey(Optional.of("ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAgQ..."))
                        .withKnownHosts(Optional.of("/home/user/.ssh/known_hosts"))
                        .build(),
                SEMVER_PARSER);

        assertInstanceOf(FailedCurrentSource.class, result,
                "both host-key and known-hosts must return FailedCurrentSource, not throw");
    }

    @Test
    void create_withBothHostKeyAndKnownHosts_doesNotThrow() {
        assertDoesNotThrow(() -> factory.create(
                minimalSsh()
                        .withHostKey(Optional.of("ssh-rsa AAAA..."))
                        .withKnownHosts(Optional.of("/etc/ssh/known_hosts"))
                        .build(),
                SEMVER_PARSER));
    }

    @Test
    void create_withNeitherHostKeyNorKnownHosts_returnsFailedCurrentSource() {
        // No host verification: no TOFU. Must degrade to FailedCurrentSource, not throw.
        CurrentVersionSource result = factory.create(
                minimalSsh()
                        .withHostKey(Optional.empty())
                        .withKnownHosts(Optional.empty())
                        .build(),
                SEMVER_PARSER);

        assertInstanceOf(FailedCurrentSource.class, result,
                "neither host-key nor known-hosts (no TOFU) must return FailedCurrentSource, not throw");
    }

    @Test
    void create_withNeitherHostKeyNorKnownHosts_doesNotThrow() {
        assertDoesNotThrow(() -> factory.create(
                minimalSsh()
                        .withHostKey(Optional.empty())
                        .withKnownHosts(Optional.empty())
                        .build(),
                SEMVER_PARSER));
    }

    // -----------------------------------------------------------------------
    // Happy path: valid config builds a real source
    // -----------------------------------------------------------------------

    @Test
    void create_withInlinePrivateKeyAndPinnedHostKey_buildsASource() {
        // The minimal valid form: inline key + pinned host-key.
        CurrentVersionSource result = factory.create(minimalSsh().build(), SEMVER_PARSER);

        assertNotNull(result);
        assertFalse(result instanceof FailedCurrentSource,
                "fully valid config must produce a real source, not a FailedCurrentSource");
    }

    @Test
    void create_withPrivateKeyFileAndKnownHostsFile_buildsASource() {
        // The file-based form: key file + known-hosts file.
        CurrentVersionSource result = factory.create(
                minimalSsh()
                        .withPrivateKey(Optional.empty())
                        .withPrivateKeyFile(Optional.of("/var/run/secrets/ssh/id_rsa"))
                        .withHostKey(Optional.empty())
                        .withKnownHosts(Optional.of("/home/app/.ssh/known_hosts"))
                        .build(),
                SEMVER_PARSER);

        assertNotNull(result);
        assertFalse(result instanceof FailedCurrentSource,
                "private-key-file + known-hosts form must also build a real source");
    }

    @Test
    void create_withCustomReleaseField_buildsASource() {
        CurrentVersionSource result = factory.create(
                minimalSsh()
                        .withReleaseField(Optional.of("BUILD_ID"))
                        .build(),
                SEMVER_PARSER);

        assertNotNull(result);
        assertFalse(result instanceof FailedCurrentSource,
                "a custom release-field must still produce a real source");
    }

    @Test
    void create_withAbsentReleaseField_defaultsToVersionId_andBuildsASource() {
        // Absent release-field defaults to VERSION_ID — the source is built fine at config time.
        CurrentVersionSource result = factory.create(
                minimalSsh().withReleaseField(Optional.empty()).build(),
                SEMVER_PARSER);

        assertNotNull(result);
        assertFalse(result instanceof FailedCurrentSource);
    }

    @Test
    void create_withCustomPort_buildsASource() {
        CurrentVersionSource result = factory.create(
                minimalSsh().withPort(Optional.of(2222)).build(),
                SEMVER_PARSER);

        assertNotNull(result);
        assertFalse(result instanceof FailedCurrentSource);
    }

    @Test
    void create_withAbsentPort_defaultsTo22_andBuildsASource() {
        // Absent port defaults to 22 — the source is built fine at config time.
        CurrentVersionSource result = factory.create(
                minimalSsh().withPort(Optional.empty()).build(),
                SEMVER_PARSER);

        assertNotNull(result);
        assertFalse(result instanceof FailedCurrentSource);
    }

    // -----------------------------------------------------------------------
    // Closeable contract
    // -----------------------------------------------------------------------

    @Test
    void source_implementsCloseable() {
        CurrentVersionSource source = factory.create(minimalSsh().build(), SEMVER_PARSER);

        assertInstanceOf(java.io.Closeable.class, source,
                "SshOsReleaseCurrentSource must implement Closeable so the resolver can close it on shutdown");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns a builder pre-configured with the minimum valid SSH fields:
     * <ul>
     *   <li>{@code host} = myvm.example.com</li>
     *   <li>{@code user} = admin</li>
     *   <li>{@code private-key} = a fake inline PEM placeholder</li>
     *   <li>{@code host-key} = a fake pinned public-key placeholder</li>
     * </ul>
     * Individual tests call {@code withXxx()} to override specific fields.
     */
    private static VersionSourceBuilder minimalSsh() {
        return new VersionSourceBuilder()
                .withHost(Optional.of("myvm.example.com"))
                .withUser(Optional.of("admin"))
                .withPrivateKey(Optional.of(
                        "-----BEGIN OPENSSH PRIVATE KEY-----\nfakefakefake\n-----END OPENSSH PRIVATE KEY-----"))
                .withHostKey(Optional.of("ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAgQtest...placeholder"));
    }

    // -----------------------------------------------------------------------
    // VersionSource fake / builder
    // -----------------------------------------------------------------------

    /**
     * Reusable builder for a fake {@link ApplicationConfigLoader.VersionSource} that supplies
     * the SSH-specific fields the factory reads. All fields default to {@link Optional#empty()}
     * unless overridden via the fluent setters.
     *
     * <p>The new SSH methods ({@code host()}, {@code user()}, etc.) are added here WITHOUT
     * {@code @Override} because they do not yet exist on the interface. Once the implementer
     * adds them to {@code ApplicationConfigLoader.VersionSource}, the anonymous class below
     * will correctly satisfy the interface without any change to this test.
     */
    static class VersionSourceBuilder {

        private Optional<String>  host         = Optional.empty();
        private Optional<Integer> port         = Optional.empty();
        private Optional<String>  user         = Optional.empty();
        private Optional<String>  privateKey   = Optional.empty();
        private Optional<String>  privateKeyFile = Optional.empty();
        private Optional<String>  hostKey      = Optional.empty();
        private Optional<String>  knownHosts   = Optional.empty();
        private Optional<String>  releaseField = Optional.empty();

        VersionSourceBuilder withHost(Optional<String> v)          { host = v;          return this; }
        VersionSourceBuilder withPort(Optional<Integer> v)         { port = v;          return this; }
        VersionSourceBuilder withUser(Optional<String> v)          { user = v;          return this; }
        VersionSourceBuilder withPrivateKey(Optional<String> v)    { privateKey = v;    return this; }
        VersionSourceBuilder withPrivateKeyFile(Optional<String> v){ privateKeyFile = v; return this; }
        VersionSourceBuilder withHostKey(Optional<String> v)       { hostKey = v;       return this; }
        VersionSourceBuilder withKnownHosts(Optional<String> v)    { knownHosts = v;    return this; }
        VersionSourceBuilder withReleaseField(Optional<String> v)  { releaseField = v;  return this; }

        ApplicationConfigLoader.VersionSource build() {
            // Capture as effectively-final locals for the anonymous class.
            final Optional<String>  fHost          = host;
            final Optional<Integer> fPort          = port;
            final Optional<String>  fUser          = user;
            final Optional<String>  fPrivateKey    = privateKey;
            final Optional<String>  fPrivateKeyFile = privateKeyFile;
            final Optional<String>  fHostKey       = hostKey;
            final Optional<String>  fKnownHosts    = knownHosts;
            final Optional<String>  fReleaseField  = releaseField;

            return new ApplicationConfigLoader.VersionSource() {
                // --- Existing interface methods (always @Override) ----------
                @Override public String type()                          { return "ssh-os-release"; }
                @Override public Optional<String> url()                { return Optional.empty(); }
                @Override public Optional<String> caCert()             { return Optional.empty(); }
                @Override
                public Optional<String> registry() { return Optional.empty(); }

                @Override
                public Optional<Integer> maxTags() { return Optional.empty(); }

                @Override
                public Optional<String> prereleaseFilter() { return Optional.empty(); }

                @Override public Optional<String> repo()               { return Optional.empty(); }
                @Override public Optional<String> regex()              { return Optional.empty(); }
                @Override public Optional<String> namespace()          { return Optional.empty(); }
                @Override public Optional<String> workload()           { return Optional.empty(); }
                @Override public Optional<String> container()          { return Optional.empty(); }
                @Override public Optional<String> versionKey()         { return Optional.empty(); }
                @Override public Optional<Boolean> stripPrerelease()   { return Optional.empty(); }
                @Override public Optional<ApplicationConfigLoader.VersionSource.Auth> auth() { return Optional.empty(); }
                @Override public Optional<Integer> pageSize()          { return Optional.empty(); }

                // --- New SSH methods (no @Override yet — interface not yet updated) ---
                public Optional<String>  host()          { return fHost; }
                public Optional<Integer> port()          { return fPort; }
                public Optional<String>  user()          { return fUser; }
                public Optional<String>  privateKey()    { return fPrivateKey; }
                public Optional<String>  privateKeyFile(){ return fPrivateKeyFile; }
                public Optional<String>  hostKey()       { return fHostKey; }
                public Optional<String>  knownHosts()    { return fKnownHosts; }
                public Optional<String>  releaseField()  { return fReleaseField; }
            };
        }
    }
}
