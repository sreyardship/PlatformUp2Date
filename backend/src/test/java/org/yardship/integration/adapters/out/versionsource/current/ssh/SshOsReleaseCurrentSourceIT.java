package org.yardship.integration.adapters.out.versionsource.current.ssh;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.MappedKeyPairProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.current.ssh.SshOsReleaseCurrentSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@code SshOsReleaseCurrentSource} against an <em>embedded</em>
 * Apache MINA SSHD server ({@link SshServer}) running in the same process.
 *
 * <p>No {@code @QuarkusTest} — the source and factory are plain Java objects that use MINA
 * directly, just as {@code HttpRegexLatestSourceIT} uses WireMock without Quarkus context.
 *
 * <h2>Embedded server design</h2>
 * <ul>
 *   <li>Server key pair: RSA 2048, generated once per class in {@link #startSshServer}.</li>
 *   <li>Client key pair: RSA 2048, generated once; the server's {@code PublickeyAuthenticator}
 *       accepts exactly this client public key.</li>
 *   <li>A second {@link #wrongServerKeyPair} is generated for the host-key-mismatch test.</li>
 *   <li>The {@code CommandFactory} handles {@value #FIXED_READ_COMMAND} by returning the
 *       current fixture body held in {@link #currentFixture}. Every other command throws
 *       {@link UnsupportedOperationException}.</li>
 * </ul>
 *
 * <h2>Assumed fixed read command</h2>
 * <pre>{@code cat /etc/os-release}</pre>
 * The source always issues this exact exec command — not configurable.
 *
 * <h2>Key / host-key / known-hosts formats assumed</h2>
 * <ul>
 *   <li><b>{@code private-key}</b>: OpenSSH private key PEM block
 *       ({@code -----BEGIN OPENSSH PRIVATE KEY-----}), produced by
 *       {@link OpenSSHKeyPairResourceWriter#writePrivateKey}.</li>
 *   <li><b>{@code private-key-file}</b>: a file containing the same OpenSSH private key PEM,
 *       written to a temp path; read by the source at CONNECT TIME (not at {@code create()} time).</li>
 *   <li><b>{@code host-key}</b>: an OpenSSH public-key one-liner without hostname,
 *       e.g. {@code ssh-rsa AAAA...}, produced by
 *       {@link OpenSSHKeyPairResourceWriter#writePublicKey} (trimmed).</li>
 *   <li><b>{@code known-hosts}</b>: a standard OpenSSH known_hosts file with one entry in
 *       bracket notation: {@code [127.0.0.1]:PORT keytype base64key}.</li>
 * </ul>
 *
 * <h2>Fixture variants</h2>
 * <ul>
 *   <li>{@link #UBUNTU_OS_RELEASE} — {@code VERSION_ID="24.04"}; calver {@code YY.0M}.</li>
 *   <li>{@link #OPENWRT_OS_RELEASE} — {@code VERSION_ID="23.05.5"}; calver {@code YY.0M.MICRO}.</li>
 *   <li>{@link #SEMVER_VM_OS_RELEASE} — {@code VERSION_ID="1.0.0"}; semver.</li>
 *   <li>{@link #CUSTOM_FIELD_OS_RELEASE} — no {@code VERSION_ID}; has {@code BUILD_ID="1.2.3"}.</li>
 * </ul>
 */
class SshOsReleaseCurrentSourceIT {

    // -----------------------------------------------------------------------
    // Fixed command the source must issue
    // -----------------------------------------------------------------------

    static final String FIXED_READ_COMMAND = "cat /etc/os-release";

    // -----------------------------------------------------------------------
    // /etc/os-release fixture bodies
    // -----------------------------------------------------------------------

    /** Ubuntu 24.04 — VERSION_ID is double-quoted; quote stripping must yield "24.04". */
    private static final String UBUNTU_OS_RELEASE = """
            NAME="Ubuntu"
            VERSION="24.04.2 LTS (Noble Numbat)"
            ID=ubuntu
            ID_LIKE=debian
            VERSION_ID="24.04"
            PRETTY_NAME="Ubuntu 24.04.2 LTS"
            """;

    /** OpenWRT 23.05.5 — VERSION_ID is double-quoted; quote stripping must yield "23.05.5". */
    private static final String OPENWRT_OS_RELEASE = """
            NAME="OpenWrt"
            VERSION="23.05.5"
            ID="openwrt"
            ID_LIKE="lede openwrt"
            VERSION_ID="23.05.5"
            PRETTY_NAME="OpenWrt 23.05.5"
            """;

    /**
     * A hypothetical VM that reports a three-part semver version — lets us exercise the semver
     * parser without complicating Ubuntu/OpenWRT tests with calver.
     */
    private static final String SEMVER_VM_OS_RELEASE = """
            NAME="MyApp"
            ID=myapp
            VERSION_ID="1.0.0"
            """;

    /** A fixture with NO VERSION_ID but a custom BUILD_ID field for custom-field tests. */
    private static final String CUSTOM_FIELD_OS_RELEASE = """
            NAME="CustomOS"
            ID=custom
            BUILD_ID="1.2.3"
            """;

    // -----------------------------------------------------------------------
    // Parsers
    // -----------------------------------------------------------------------

    private static final VersionParser SEMVER_PARSER  = new VersionParser(VersionScheme.SEMVER);
    private static final VersionParser CALVER_UBUNTU  = new VersionParser(VersionScheme.CALVER, "YY.0M");
    private static final VersionParser CALVER_OPENWRT = new VersionParser(VersionScheme.CALVER, "YY.0M.MICRO");

    // -----------------------------------------------------------------------
    // Shared server state (set up once per test class)
    // -----------------------------------------------------------------------

    static SshServer server;
    static KeyPair serverKeyPair;
    static KeyPair wrongServerKeyPair;   // a DIFFERENT server key for the mismatch test
    static KeyPair clientKeyPair;

    static String clientPrivateKeyPem;   // inline private-key config value
    static String serverPublicKeyLine;   // host-key config value (correct pinned key)
    static String wrongPublicKeyLine;    // host-key config value (wrong key — mismatch test)

    // ed25519 variants — the PRODUCTION host-key type (ADR-0018). A second embedded server presents
    // an ed25519 host key and accepts an ed25519 client key, so the SSH client path is exercised
    // against the prod key type in addition to RSA. Real ssh-keygen ed25519 keys are embedded as
    // constants and only ever PARSED (never re-serialized): this goes through the exact production
    // key-loading path and avoids MINA's BouncyCastle OpenSSH-writer quirk, which CCEs when asked to
    // serialize a JDK-generated ed25519 key while BouncyCastle is registered.
    static SshServer ed25519Server;

    /** ssh-keygen ed25519 server host key (OpenSSH private key); its public half is pinned below. */
    private static final String ED25519_SERVER_PRIVATE_KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            QyNTUxOQAAACCkhyuy5aj+qg8v0VIgVLCdSfgSBb3B/8IzlIeArDzj/AAAAJjFcU9lxXFP
            ZQAAAAtzc2gtZWQyNTUxOQAAACCkhyuy5aj+qg8v0VIgVLCdSfgSBb3B/8IzlIeArDzj/A
            AAAEADnlvZypZw1v9XsWTzDTzD7A62k4rQS6svXpEqrhVDZaSHK7LlqP6qDy/RUiBUsJ1J
            +BIFvcH/wjOUh4CsPOP8AAAAFGVtYmVkZGVkLXRlc3Qtc2VydmVyAQ==
            -----END OPENSSH PRIVATE KEY-----
            """;

    /** The ed25519 server host-key public line — the {@code host-key} config value (no comment). */
    private static final String ED25519_SERVER_PUBLIC_LINE =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKSHK7LlqP6qDy/RUiBUsJ1J+BIFvcH/wjOUh4CsPOP8";

    /** ssh-keygen ed25519 client key (OpenSSH private key) — the inline {@code private-key} config value. */
    private static final String ED25519_CLIENT_PRIVATE_KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            QyNTUxOQAAACBS31IRyP7DZNZD4ZgZAUAzvmGbTLYdtlG/Pc2PDQ/m+gAAAJg6fer1On3q
            9QAAAAtzc2gtZWQyNTUxOQAAACBS31IRyP7DZNZD4ZgZAUAzvmGbTLYdtlG/Pc2PDQ/m+g
            AAAEA4fT5MqyEKjc2T6Hk/D6mbI+HDcTaID31F4dgJ2WTz6VLfUhHI/sNk1kPhmBkBQDO+
            YZtMth22Ub89zY8ND+b6AAAAFGVtYmVkZGVkLXRlc3QtY2xpZW50AQ==
            -----END OPENSSH PRIVATE KEY-----
            """;

    /**
     * Mutable fixture body: tests set this before calling {@code source.version()} to control
     * what the command factory returns for that test. Reset to Ubuntu in {@link #resetFixture}.
     */
    static final AtomicReference<String> currentFixture = new AtomicReference<>(UBUNTU_OS_RELEASE);

    static final SshOsReleaseCurrentSourceFactory FACTORY = new SshOsReleaseCurrentSourceFactory();

    // -----------------------------------------------------------------------
    // Server lifecycle
    // -----------------------------------------------------------------------

    @BeforeAll
    static void startSshServer() throws Exception {
        // --- Generate key pairs ---
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        serverKeyPair      = kpg.generateKeyPair();
        wrongServerKeyPair = kpg.generateKeyPair();
        clientKeyPair      = kpg.generateKeyPair();

        // --- Serialize client private key → OpenSSH PEM (for inline private-key config) ---
        ByteArrayOutputStream privateBos = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(clientKeyPair, null, null, privateBos);
        clientPrivateKeyPem = privateBos.toString(StandardCharsets.UTF_8);

        // --- Serialize server public key → OpenSSH one-liner (for host-key config) ---
        // Format: "ssh-rsa AAAA..."  (no hostname, no comment)
        ByteArrayOutputStream serverPubBos = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePublicKey(serverKeyPair, null, serverPubBos);
        serverPublicKeyLine = serverPubBos.toString(StandardCharsets.UTF_8).trim();

        // --- Serialize wrong key for mismatch test ---
        ByteArrayOutputStream wrongBos = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePublicKey(wrongServerKeyPair, null, wrongBos);
        wrongPublicKeyLine = wrongBos.toString(StandardCharsets.UTF_8).trim();

        // --- Configure embedded MINA SSHD server ---
        server = SshServer.setUpDefaultServer();
        server.setPort(0); // ephemeral — read back after start()

        server.setKeyPairProvider(new MappedKeyPairProvider(serverKeyPair));

        // Accept exactly our generated client public key
        server.setPublickeyAuthenticator((username, key, session) ->
                KeyUtils.compareKeys(clientKeyPair.getPublic(), key));

        // Handle the fixed read command; anything else is unexpected
        server.setCommandFactory((channel, command) -> {
            if (FIXED_READ_COMMAND.equals(command)) {
                return new FixtureCommand(currentFixture.get());
            }
            throw new UnsupportedOperationException(
                    "Embedded test server received unexpected command: '" + command + "'");
        });

        server.start();
        // server.getPort() is now the bound ephemeral port

        // --- ed25519 server + client (production host-key type, ADR-0018) ---
        // Parse the embedded ssh-keygen keys via the same loader the production source uses.
        KeyPair ed25519ServerKeyPair = parseOpenSshKeyPair(ED25519_SERVER_PRIVATE_KEY);
        KeyPair ed25519ClientKeyPair = parseOpenSshKeyPair(ED25519_CLIENT_PRIVATE_KEY);

        ed25519Server = SshServer.setUpDefaultServer();
        ed25519Server.setPort(0); // ephemeral
        ed25519Server.setKeyPairProvider(new MappedKeyPairProvider(ed25519ServerKeyPair));
        ed25519Server.setPublickeyAuthenticator((username, key, session) ->
                KeyUtils.compareKeys(ed25519ClientKeyPair.getPublic(), key));
        ed25519Server.setCommandFactory((channel, command) -> {
            if (FIXED_READ_COMMAND.equals(command)) {
                return new FixtureCommand(currentFixture.get());
            }
            throw new UnsupportedOperationException(
                    "Embedded test server received unexpected command: '" + command + "'");
        });
        ed25519Server.start();
    }

    @AfterAll
    static void stopSshServer() throws Exception {
        if (server != null && server.isOpen()) {
            server.stop();
        }
        if (ed25519Server != null && ed25519Server.isOpen()) {
            ed25519Server.stop();
        }
    }

    @BeforeEach
    void resetFixture() {
        currentFixture.set(UBUNTU_OS_RELEASE);
    }

    // -----------------------------------------------------------------------
    // Happy path: Ubuntu fixture, inline key, pinned host-key
    // -----------------------------------------------------------------------

    @Test
    void ubuntuFixture_inlinePrivateKey_pinnedHostKey_returnsVersionId_24_04() throws Exception {
        CurrentVersionSource source = FACTORY.create(
                sourceBuilder().build(),
                CALVER_UBUNTU);

        VersionValue result = source.version();

        assertEquals("24.04", result.value(),
                "must extract VERSION_ID=\"24.04\" from the Ubuntu fixture");
    }

    @Test
    void ubuntuFixture_quotedVersionId_isStrippedCorrectly() throws Exception {
        // The fixture has VERSION_ID="24.04" — double-quote stripping must happen before parsing.
        CurrentVersionSource source = FACTORY.create(
                sourceBuilder().build(),
                CALVER_UBUNTU);

        VersionValue result = source.version();

        // If quotes are NOT stripped, the parser would receive `"24.04"` and either throw or
        // return a wrong value — so a successful parse proves quote stripping is in place.
        assertEquals("24.04", result.value(),
                "surrounding double-quotes in VERSION_ID must be stripped before parsing");
    }

    // -----------------------------------------------------------------------
    // Happy path: semver parser
    // -----------------------------------------------------------------------

    @Test
    void semverVmFixture_semverParser_returnsVersionValue_1_0_0() throws Exception {
        currentFixture.set(SEMVER_VM_OS_RELEASE);

        CurrentVersionSource source = FACTORY.create(
                sourceBuilder().build(),
                SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("1.0.0", result.value(),
                "a three-part version in VERSION_ID must parse under the semver scheme");
    }

    // -----------------------------------------------------------------------
    // Happy path: OpenWRT fixture
    // -----------------------------------------------------------------------

    @Test
    void openWrtFixture_returnsVersionId_23_05_5() throws Exception {
        currentFixture.set(OPENWRT_OS_RELEASE);

        CurrentVersionSource source = FACTORY.create(
                sourceBuilder().build(),
                CALVER_OPENWRT);

        VersionValue result = source.version();

        assertEquals("23.05.5", result.value(),
                "must extract VERSION_ID=\"23.05.5\" from the OpenWRT fixture");
    }

    // -----------------------------------------------------------------------
    // private-key-file form
    // -----------------------------------------------------------------------

    @Test
    void privateKeyFile_authenticatesSuccessfully(@TempDir Path dir) throws Exception {
        Path keyFile = dir.resolve("client-key");
        Files.writeString(keyFile, clientPrivateKeyPem);

        CurrentVersionSource source = FACTORY.create(
                sourceBuilder()
                        .withPrivateKey(Optional.empty())
                        .withPrivateKeyFile(Optional.of(keyFile.toString()))
                        .build(),
                CALVER_UBUNTU);

        VersionValue result = source.version();

        assertEquals("24.04", result.value(),
                "private-key-file form must authenticate with the same key as inline private-key");
    }

    @Test
    void privateKeyFile_isReadAtConnectTime_notAtCreate(@TempDir Path dir) throws Exception {
        // The key file does NOT exist when create() is called. Source creation must succeed
        // (value-level isolation: a missing file at boot must NOT be a FailedCurrentSource —
        // the path itself is valid, the file may appear before the first version() call).
        Path keyFile = dir.resolve("late-key");

        CurrentVersionSource source = FACTORY.create(
                sourceBuilder()
                        .withPrivateKey(Optional.empty())
                        .withPrivateKeyFile(Optional.of(keyFile.toString()))
                        .build(),
                CALVER_UBUNTU);

        assertNotNull(source,
                "source must be created even though the key file does not exist yet");
        assertFalse(source instanceof org.yardship.adapters.out.versionsource.current.FailedCurrentSource,
                "an absent key-file path must not produce a FailedCurrentSource at create() time");

        // Now write the file — source must read it at connect time
        Files.writeString(keyFile, clientPrivateKeyPem);

        VersionValue result = source.version();

        assertEquals("24.04", result.value(),
                "key file written after create() must be used successfully at connect time");
    }

    @Test
    void badPrivateKeyFilePath_throws_isolatingTheScrapeFailure(@TempDir Path dir) {
        Path nonExistent = dir.resolve("no-such-key");

        CurrentVersionSource source = FACTORY.create(
                sourceBuilder()
                        .withPrivateKey(Optional.empty())
                        .withPrivateKeyFile(Optional.of(nonExistent.toString()))
                        .build(),
                CALVER_UBUNTU);

        // File still doesn't exist at connect time → version() must throw (isolate this app)
        assertThrows(Exception.class, source::version,
                "a private-key-file path that does not exist at connect time must throw from version()");
    }

    // -----------------------------------------------------------------------
    // known-hosts form
    // -----------------------------------------------------------------------

    @Test
    void knownHostsFile_correctKey_returnsVersionId(@TempDir Path dir) throws Exception {
        // Write a known_hosts file containing the server's real public key in bracket notation
        // (required when port != 22 — our ephemeral port is always != 22).
        // Format: [hostname]:port keytype base64key
        Path knownHostsFile = dir.resolve("known_hosts");
        String entry = "[127.0.0.1]:" + server.getPort() + " " + serverPublicKeyLine;
        Files.writeString(knownHostsFile, entry + System.lineSeparator());

        CurrentVersionSource source = FACTORY.create(
                sourceBuilder()
                        .withHostKey(Optional.empty())
                        .withKnownHosts(Optional.of(knownHostsFile.toString()))
                        .build(),
                CALVER_UBUNTU);

        VersionValue result = source.version();

        assertEquals("24.04", result.value(),
                "known-hosts form must verify the server key and return the version");
    }

    // -----------------------------------------------------------------------
    // Host-key verification failures
    // -----------------------------------------------------------------------

    @Test
    void hostKeyMismatch_pinnedWrongKey_throws_isolatingTheScrapeFailure() {
        // Pin the WRONG public key: the server presents a different key → mismatch → throws.
        CurrentVersionSource source = FACTORY.create(
                sourceBuilder()
                        .withHostKey(Optional.of(wrongPublicKeyLine))
                        .build(),
                CALVER_UBUNTU);

        assertThrows(Exception.class, source::version,
                "a pinned host-key that does not match the server's actual key must throw from version(), " +
                "isolating this app's scrape without affecting other apps");
    }

    @Test
    void knownHostsFile_wrongKey_throws_isolatingTheScrapeFailure(@TempDir Path dir) throws Exception {
        // known_hosts file contains the WRONG public key
        Path knownHostsFile = dir.resolve("known_hosts");
        String entry = "[127.0.0.1]:" + server.getPort() + " " + wrongPublicKeyLine;
        Files.writeString(knownHostsFile, entry + System.lineSeparator());

        CurrentVersionSource source = FACTORY.create(
                sourceBuilder()
                        .withHostKey(Optional.empty())
                        .withKnownHosts(Optional.of(knownHostsFile.toString()))
                        .build(),
                CALVER_UBUNTU);

        assertThrows(Exception.class, source::version,
                "a known-hosts file whose entry does not match the server's actual key must throw");
    }

    // -----------------------------------------------------------------------
    // release-field: missing and custom
    // -----------------------------------------------------------------------

    @Test
    void missingReleaseField_throws_isolatingTheScrapeFailure() {
        // The fixture has no VERSION_ID (the default release-field)
        currentFixture.set(CUSTOM_FIELD_OS_RELEASE);

        CurrentVersionSource source = FACTORY.create(
                sourceBuilder().build(), // default release-field = VERSION_ID
                SEMVER_PARSER);

        assertThrows(Exception.class, source::version,
                "a missing release-field in /etc/os-release must throw from version(), isolating this app");
    }

    @Test
    void customReleaseField_extractsAlternativeField() throws Exception {
        // The fixture has no VERSION_ID but has BUILD_ID="1.2.3"
        currentFixture.set(CUSTOM_FIELD_OS_RELEASE);

        CurrentVersionSource source = FACTORY.create(
                sourceBuilder()
                        .withReleaseField(Optional.of("BUILD_ID"))
                        .build(),
                SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("1.2.3", result.value(),
                "release-field=BUILD_ID must extract BUILD_ID instead of VERSION_ID");
    }

    @Test
    void defaultReleaseField_isVersionId() throws Exception {
        // Explicitly confirm that absent release-field defaults to VERSION_ID.
        currentFixture.set(UBUNTU_OS_RELEASE); // has VERSION_ID="24.04"

        CurrentVersionSource source = FACTORY.create(
                sourceBuilder().withReleaseField(Optional.empty()).build(),
                CALVER_UBUNTU);

        VersionValue result = source.version();

        assertEquals("24.04", result.value(),
                "absent release-field must default to VERSION_ID");
    }

    // -----------------------------------------------------------------------
    // Closeable contract
    // -----------------------------------------------------------------------

    @Test
    void source_canBeClosedWithoutError() throws Exception {
        CurrentVersionSource source = FACTORY.create(sourceBuilder().build(), CALVER_UBUNTU);

        assertInstanceOf(java.io.Closeable.class, source);
        assertDoesNotThrow(() -> ((java.io.Closeable) source).close(),
                "closing a source must not throw");
    }

    // -----------------------------------------------------------------------
    // ed25519 (production host-key type, ADR-0018)
    // -----------------------------------------------------------------------

    @Test
    void ed25519_inlinePrivateKey_pinnedHostKey_returnsVersionId_24_04() throws Exception {
        // ed25519 is the production host-key type: exercise the full client connect/auth/exec path
        // against it, mirroring the RSA happy path.
        CurrentVersionSource source = FACTORY.create(ed25519SourceBuilder().build(), CALVER_UBUNTU);

        VersionValue result = source.version();

        assertEquals("24.04", result.value(),
                "ed25519 inline key + pinned ed25519 host-key must authenticate and return VERSION_ID");
    }

    @Test
    void ed25519_privateKeyFile_authenticatesSuccessfully(@TempDir Path dir) throws Exception {
        Path keyFile = dir.resolve("ed25519-client-key");
        Files.writeString(keyFile, ED25519_CLIENT_PRIVATE_KEY);

        CurrentVersionSource source = FACTORY.create(
                ed25519SourceBuilder()
                        .withPrivateKey(Optional.empty())
                        .withPrivateKeyFile(Optional.of(keyFile.toString()))
                        .build(),
                CALVER_UBUNTU);

        VersionValue result = source.version();

        assertEquals("24.04", result.value(),
                "ed25519 private-key-file form must authenticate and return VERSION_ID");
    }

    @Test
    void ed25519_knownHostsFile_correctKey_returnsVersionId(@TempDir Path dir) throws Exception {
        Path knownHostsFile = dir.resolve("known_hosts");
        String entry = "[127.0.0.1]:" + ed25519Server.getPort() + " " + ED25519_SERVER_PUBLIC_LINE;
        Files.writeString(knownHostsFile, entry + System.lineSeparator());

        CurrentVersionSource source = FACTORY.create(
                ed25519SourceBuilder()
                        .withHostKey(Optional.empty())
                        .withKnownHosts(Optional.of(knownHostsFile.toString()))
                        .build(),
                CALVER_UBUNTU);

        VersionValue result = source.version();

        assertEquals("24.04", result.value(),
                "ed25519 known-hosts form must verify the server key and return the version");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns a builder pre-configured with all parameters pointing at the embedded server:
     * host=127.0.0.1, port=(ephemeral), user=testuser, inline private-key, pinned host-key.
     */
    private VersionSourceBuilder sourceBuilder() {
        return new VersionSourceBuilder()
                .withHost(Optional.of("127.0.0.1"))
                .withPort(Optional.of(server.getPort()))
                .withUser(Optional.of("testuser"))
                .withPrivateKey(Optional.of(clientPrivateKeyPem))
                .withHostKey(Optional.of(serverPublicKeyLine));
    }

    /**
     * Like {@link #sourceBuilder()} but pointing at the ed25519 embedded server with the ed25519
     * client key and pinned ed25519 host-key — the production key type (ADR-0018).
     */
    private VersionSourceBuilder ed25519SourceBuilder() {
        return new VersionSourceBuilder()
                .withHost(Optional.of("127.0.0.1"))
                .withPort(Optional.of(ed25519Server.getPort()))
                .withUser(Optional.of("testuser"))
                .withPrivateKey(Optional.of(ED25519_CLIENT_PRIVATE_KEY))
                .withHostKey(Optional.of(ED25519_SERVER_PUBLIC_LINE));
    }

    /** Parse an OpenSSH private-key PEM into a {@link KeyPair} via MINA's loader (production path). */
    private static KeyPair parseOpenSshKeyPair(String pem) throws Exception {
        Iterable<KeyPair> pairs = SecurityUtils.loadKeyPairIdentities(
                null,
                () -> "embedded-test-key",
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)),
                null);
        for (KeyPair pair : pairs) {
            return pair;
        }
        throw new IllegalStateException("No key pair parsed from embedded ed25519 PEM");
    }

    // -----------------------------------------------------------------------
    // VersionSource fake / builder (mirrors SshOsReleaseCurrentSourceFactoryTests)
    // -----------------------------------------------------------------------

    /**
     * Reusable builder for a fake {@link ApplicationConfigLoader.VersionSource} that supplies
     * all SSH-specific fields. The new SSH methods are added WITHOUT {@code @Override} because
     * they do not yet exist on the interface; once the implementer adds them, the anonymous class
     * will correctly satisfy the interface.
     */
    static class VersionSourceBuilder {

        private Optional<String>  host          = Optional.empty();
        private Optional<Integer> port          = Optional.empty();
        private Optional<String>  user          = Optional.empty();
        private Optional<String>  privateKey    = Optional.empty();
        private Optional<String>  privateKeyFile = Optional.empty();
        private Optional<String>  hostKey       = Optional.empty();
        private Optional<String>  knownHosts    = Optional.empty();
        private Optional<String>  releaseField  = Optional.empty();

        VersionSourceBuilder withHost(Optional<String> v)           { host = v;           return this; }
        VersionSourceBuilder withPort(Optional<Integer> v)          { port = v;           return this; }
        VersionSourceBuilder withUser(Optional<String> v)           { user = v;           return this; }
        VersionSourceBuilder withPrivateKey(Optional<String> v)     { privateKey = v;     return this; }
        VersionSourceBuilder withPrivateKeyFile(Optional<String> v) { privateKeyFile = v; return this; }
        VersionSourceBuilder withHostKey(Optional<String> v)        { hostKey = v;        return this; }
        VersionSourceBuilder withKnownHosts(Optional<String> v)     { knownHosts = v;     return this; }
        VersionSourceBuilder withReleaseField(Optional<String> v)   { releaseField = v;   return this; }

        ApplicationConfigLoader.VersionSource build() {
            final Optional<String>  fHost          = host;
            final Optional<Integer> fPort          = port;
            final Optional<String>  fUser          = user;
            final Optional<String>  fPrivateKey    = privateKey;
            final Optional<String>  fPrivateKeyFile = privateKeyFile;
            final Optional<String>  fHostKey       = hostKey;
            final Optional<String>  fKnownHosts    = knownHosts;
            final Optional<String>  fReleaseField  = releaseField;

            return new ApplicationConfigLoader.VersionSource() {
                // --- Existing interface methods ---
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
                public Optional<String>  host()           { return fHost; }
                public Optional<Integer> port()           { return fPort; }
                public Optional<String>  user()           { return fUser; }
                public Optional<String>  privateKey()     { return fPrivateKey; }
                public Optional<String>  privateKeyFile() { return fPrivateKeyFile; }
                public Optional<String>  hostKey()        { return fHostKey; }
                public Optional<String>  knownHosts()     { return fKnownHosts; }
                public Optional<String>  releaseField()   { return fReleaseField; }
            };
        }
    }

    // -----------------------------------------------------------------------
    // Embedded MINA SSHD command — returns a fixed body and exits 0
    // -----------------------------------------------------------------------

    /**
     * MINA SSHD {@link Command} implementation that writes a fixed string body to stdout
     * and exits 0. Used as the command handler for {@value #FIXED_READ_COMMAND}.
     *
     * <p>In MINA SSHD 2.17.x, {@code Command} extends {@code CommandLifecycle} (which defines
     * {@code start(ChannelSession, Environment)} and {@code destroy(ChannelSession)}) and
     * {@code CommandDirectStreamsAware} (which defines the three stream setters).
     * It does NOT extend {@code Closeable} in this version.
     */
    private static final class FixtureCommand implements Command {

        private final String body;
        private OutputStream out;
        private ExitCallback callback;

        FixtureCommand(String body) {
            this.body = body;
        }

        @Override
        public void setInputStream(InputStream in) { /* not used */ }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) { /* not used */ }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.flush();
            callback.onExit(0);
        }

        @Override
        public void destroy(ChannelSession channel) throws Exception {
            // No-op: no resources to release.
        }
    }
}
