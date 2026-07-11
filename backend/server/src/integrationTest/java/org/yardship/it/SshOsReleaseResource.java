package org.yardship.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.MappedKeyPairProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Boots an embedded <b>ed25519</b> Apache MINA SSH server (the production host-key type, ADR-0018)
 * in the test JVM and points a launched {@code ssh-os-release} app at it, so the SSH
 * connect/auth/exec path is exercised end-to-end against the <em>built artifact</em> — in CI's
 * native pipeline that is the GraalVM native binary. This is the guard that catches native-image
 * reachability regressions in the MINA SSH client path (the original
 * {@code KeyPairGenerator.getInstance(algorithm, provider)} {@code NoSuchMethodException}) before
 * they reach prod; a JVM-mode unit test cannot see them.
 *
 * <p>The server serves a fixed {@code cat /etc/os-release} body with {@code VERSION_ID="1.0.0"} and
 * accepts exactly the embedded ed25519 client key. The app's current leg is configured with the
 * client key (as a temp {@code private-key-file}) and the server host key (as a temp
 * {@code known-hosts} file) — both passed as plain filesystem paths so no multi-line / spaced value
 * has to survive system-property injection into the launched process. The latest leg is a small
 * WireMock {@code http-regex} source so the app fully resolves and appears in {@code /api/v1/version}.
 *
 * <p>The embedded ed25519 keys are real {@code ssh-keygen} keys, parsed (never re-serialized) so the
 * harness goes through the production key-loading path and avoids MINA's BouncyCastle OpenSSH-writer
 * quirk for ed25519. They mirror the keys in {@code SshOsReleaseCurrentSourceIT}.
 */
public class SshOsReleaseResource implements QuarkusTestResourceLifecycleManager {

    static final String READ_COMMAND = "cat /etc/os-release";

    /** The os-release the embedded VM reports: semver VERSION_ID the scrape must resolve as current. */
    static final String OS_RELEASE_BODY = """
            NAME="MyVM"
            ID=myvm
            VERSION_ID="1.0.0"
            PRETTY_NAME="MyVM 1.0.0"
            """;

    static final String EXPECTED_CURRENT_VERSION = "1.0.0";
    static final String EXPECTED_LATEST_VERSION = "2.0.0";

    // ssh-keygen ed25519 server host key (OpenSSH private key); its public half is pinned below.
    private static final String ED25519_SERVER_PRIVATE_KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            QyNTUxOQAAACCkhyuy5aj+qg8v0VIgVLCdSfgSBb3B/8IzlIeArDzj/AAAAJjFcU9lxXFP
            ZQAAAAtzc2gtZWQyNTUxOQAAACCkhyuy5aj+qg8v0VIgVLCdSfgSBb3B/8IzlIeArDzj/A
            AAAEADnlvZypZw1v9XsWTzDTzD7A62k4rQS6svXpEqrhVDZaSHK7LlqP6qDy/RUiBUsJ1J
            +BIFvcH/wjOUh4CsPOP8AAAAFGVtYmVkZGVkLXRlc3Qtc2VydmVyAQ==
            -----END OPENSSH PRIVATE KEY-----
            """;

    // The ed25519 server host-key public line — used to build the known-hosts entry (no comment).
    private static final String ED25519_SERVER_PUBLIC_LINE =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKSHK7LlqP6qDy/RUiBUsJ1J+BIFvcH/wjOUh4CsPOP8";

    // ssh-keygen ed25519 client key (OpenSSH private key) — written to a temp file and used as the
    // app's private-key-file. The server accepts exactly this key's public half.
    private static final String ED25519_CLIENT_PRIVATE_KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            QyNTUxOQAAACBS31IRyP7DZNZD4ZgZAUAzvmGbTLYdtlG/Pc2PDQ/m+gAAAJg6fer1On3q
            9QAAAAtzc2gtZWQyNTUxOQAAACBS31IRyP7DZNZD4ZgZAUAzvmGbTLYdtlG/Pc2PDQ/m+g
            AAAEA4fT5MqyEKjc2T6Hk/D6mbI+HDcTaID31F4dgJ2WTz6VLfUhHI/sNk1kPhmBkBQDO+
            YZtMth22Ub89zY8ND+b6AAAAFGVtYmVkZGVkLXRlc3QtY2xpZW50AQ==
            -----END OPENSSH PRIVATE KEY-----
            """;

    private SshServer sshServer;
    private WireMockServer wireMockServer;
    private Path tempDir;

    @Override
    public Map<String, String> start() {
        try {
            return doStart();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start SshOsReleaseResource", e);
        }
    }

    private Map<String, String> doStart() throws Exception {
        KeyPair serverKeyPair = parseOpenSshKeyPair(ED25519_SERVER_PRIVATE_KEY);
        KeyPair clientKeyPair = parseOpenSshKeyPair(ED25519_CLIENT_PRIVATE_KEY);

        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0); // ephemeral
        sshServer.setKeyPairProvider(new MappedKeyPairProvider(serverKeyPair));
        sshServer.setPublickeyAuthenticator((username, key, session) ->
                KeyUtils.compareKeys(clientKeyPair.getPublic(), key));
        sshServer.setCommandFactory((channel, command) -> {
            if (READ_COMMAND.equals(command)) {
                return new FixtureCommand(OS_RELEASE_BODY);
            }
            throw new UnsupportedOperationException(
                    "Embedded test server received unexpected command: '" + command + "'");
        });
        sshServer.start();
        int sshPort = sshServer.getPort();

        // The launched artifact connects back to this embedded SSH/HTTP server. As a host process
        // (JVM jar / raw native binary) that is 127.0.0.1; as the shipped container image (the
        // native PR job) 127.0.0.1 is the container itself, so CI sets
        // PU2D_IT_CALLBACK_HOST=host.docker.internal and adds the matching --add-host to the
        // container. The MINA server binds all interfaces, so the host gateway reaches it either
        // way. The known-hosts entry must be keyed on whatever host the client dials, so it uses
        // the same value. Defaults to 127.0.0.1 so host-process runs are unchanged.
        String callbackHost = System.getenv().getOrDefault("PU2D_IT_CALLBACK_HOST", "127.0.0.1");

        // Materialise the client key + known-hosts as temp files so only plain paths are injected.
        tempDir = Files.createTempDirectory("ssh-os-release-it");
        Path keyFile = tempDir.resolve("client_ed25519");
        Files.writeString(keyFile, ED25519_CLIENT_PRIVATE_KEY);
        Path knownHostsFile = tempDir.resolve("known_hosts");
        Files.writeString(knownHostsFile,
                "[" + callbackHost + "]:" + sshPort + " " + ED25519_SERVER_PUBLIC_LINE + System.lineSeparator());

        // Latest leg: a trivial http-regex source so the app fully resolves and is published.
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
        wireMockServer.stubFor(get(urlEqualTo("/latest")).willReturn(text(EXPECTED_LATEST_VERSION)));
        int httpPort = wireMockServer.port();

        return Map.ofEntries(
                Map.entry("platform-config.scrape-interval", "1s"),
                Map.entry("platform-config.apps[0].name", "ssh-vm"),
                Map.entry("platform-config.apps[0].version-scheme", "semver"),
                Map.entry("platform-config.apps[0].current.type", "ssh-os-release"),
                Map.entry("platform-config.apps[0].current.host", callbackHost),
                Map.entry("platform-config.apps[0].current.port", Integer.toString(sshPort)),
                Map.entry("platform-config.apps[0].current.user", "testuser"),
                Map.entry("platform-config.apps[0].current.private-key-file", keyFile.toString()),
                Map.entry("platform-config.apps[0].current.known-hosts", knownHostsFile.toString()),
                Map.entry("platform-config.apps[0].latest.type", "http-regex"),
                Map.entry("platform-config.apps[0].latest.url", "http://" + callbackHost + ":" + httpPort + "/latest"),
                Map.entry("platform-config.apps[0].latest.regex", "(\\d+\\.\\d+\\.\\d+)"));
    }

    @Override
    public void stop() {
        if (sshServer != null) {
            try {
                sshServer.stop();
            } catch (IOException e) {
                // best-effort teardown
            }
        }
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
        if (tempDir != null) {
            deleteRecursively(tempDir);
        }
    }

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

    private static ResponseDefinitionBuilder text(String body) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/plain")
                .withBody(body);
    }

    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // best-effort cleanup
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * MINA SSHD {@link Command} that writes a fixed body to stdout and exits 0 — the embedded
     * server's handler for {@value #READ_COMMAND}. Mirrors the fixture command in
     * {@code SshOsReleaseCurrentSourceIT}.
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
        public void destroy(ChannelSession channel) {
            // No-op: no resources to release.
        }
    }
}
