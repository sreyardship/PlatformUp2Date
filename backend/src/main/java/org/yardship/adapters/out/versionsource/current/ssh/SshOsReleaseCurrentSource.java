package org.yardship.adapters.out.versionsource.current.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RequiredServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.EnumSet;

/**
 * The {@code ssh-os-release} {@link CurrentVersionSource}: reads a VM's running version over SSH
 * by executing {@code cat /etc/os-release} and extracting a configured field (default
 * {@code VERSION_ID}).
 *
 * <p>Host-key verification is mandatory — no trust-on-first-use. Either a pinned {@code host-key}
 * (a single {@code ssh-rsa AAAA…} line) or a {@code known-hosts} file path is required; they are
 * mutually exclusive. Private-key authentication is required; exactly one of {@code private-key}
 * (inline PEM) or {@code private-key-file} (path, read at connect time) must be configured.
 *
 * <p>Implements {@link Closeable} so the resolver can release the underlying {@link SshClient}
 * on shutdown.
 */
public class SshOsReleaseCurrentSource implements CurrentVersionSource, Closeable {

    static final String READ_COMMAND = "cat /etc/os-release";
    static final String DEFAULT_RELEASE_FIELD = "VERSION_ID";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CHANNEL_TIMEOUT = Duration.ofSeconds(30);

    private final SshClient client;
    private final String host;
    private final int port;
    private final String user;
    private final KeyLoader keyLoader;
    private final String releaseField;
    private final VersionParser parser;

    SshOsReleaseCurrentSource(
            String host,
            int port,
            String user,
            KeyLoader keyLoader,
            ServerKeyVerifier serverKeyVerifier,
            String releaseField,
            VersionParser parser) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.keyLoader = keyLoader;
        this.releaseField = releaseField;
        this.parser = parser;

        this.client = SshClient.setUpDefaultClient();
        this.client.setServerKeyVerifier(serverKeyVerifier);
        this.client.start();
    }

    @Override
    public VersionValue version() {
        try {
            KeyPair keyPair = keyLoader.load();
            try (ClientSession session = openAuthenticatedSession(keyPair)) {
                String osReleaseContent = execReadCommand(session);
                String rawValue = extractField(osReleaseContent, releaseField);
                return parser.parse(rawValue);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                    "SSH os-release source failed for " + host + ":" + port + " — " + e.getMessage(), e);
        }
    }

    private ClientSession openAuthenticatedSession(KeyPair keyPair) throws Exception {
        ClientSession session = client.connect(user, host, port)
                .verify(CONNECT_TIMEOUT)
                .getSession();
        session.setKeyIdentityProvider(KeyIdentityProvider.wrapKeyPairs(keyPair));
        session.auth().verify(AUTH_TIMEOUT);
        return session;
    }

    private static String execReadCommand(ClientSession session) throws IOException {
        try (ChannelExec channel = session.createExecChannel(READ_COMMAND)) {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            channel.setOut(stdout);
            channel.open().verify(CHANNEL_TIMEOUT);
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), CHANNEL_TIMEOUT.toMillis());
            return stdout.toString(StandardCharsets.UTF_8);
        }
    }

    private static String extractField(String osReleaseContent, String field) {
        String prefix = field + "=";
        for (String line : osReleaseContent.split("\n")) {
            if (line.startsWith(prefix)) {
                String value = line.substring(prefix.length()).trim();
                value = stripSurroundingQuotes(value);
                return value;
            }
        }
        throw new IllegalStateException(
                "Field '" + field + "' not found in /etc/os-release output");
    }

    private static String stripSurroundingQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    @Override
    public void close() throws IOException {
        client.stop();
    }

    // -----------------------------------------------------------------------
    // Static factory methods for verifiers and key loaders
    // -----------------------------------------------------------------------

    static ServerKeyVerifier pinnedHostKeyVerifier(String hostKeyLine) {
        return (session, remoteAddress, serverKey) -> {
            PublicKey expected;
            try {
                expected = parsePublicKey(hostKeyLine);
            } catch (Exception e) {
                throw new RuntimeException("Cannot parse pinned host-key: " + e.getMessage(), e);
            }
            return KeyUtils.compareKeys(expected, serverKey);
        };
    }

    static ServerKeyVerifier knownHostsVerifier(String knownHostsFilePath) {
        return new KnownHostsServerKeyVerifier(
                RejectAllServerKeyVerifier.INSTANCE,
                Path.of(knownHostsFilePath));
    }

    static KeyLoader inlineKeyLoader(String pemString) {
        return () -> parseOpenSshPrivateKey(pemString);
    }

    static KeyLoader fileKeyLoader(String filePath) {
        return () -> {
            String pem = Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
            return parseOpenSshPrivateKey(pem);
        };
    }

    // -----------------------------------------------------------------------
    // Key parsing helpers
    // -----------------------------------------------------------------------

    private static KeyPair parseOpenSshPrivateKey(String pem) throws Exception {
        byte[] bytes = pem.getBytes(StandardCharsets.UTF_8);
        Iterable<KeyPair> pairs = SecurityUtils.loadKeyPairIdentities(
                null,
                () -> "inline-private-key",
                new ByteArrayInputStream(bytes),
                null);
        for (KeyPair pair : pairs) {
            return pair;
        }
        throw new IllegalStateException("No key pairs found in the provided private key PEM");
    }

    private static PublicKey parsePublicKey(String hostKeyLine) throws Exception {
        AuthorizedKeyEntry entry = AuthorizedKeyEntry.parseAuthorizedKeyEntry(hostKeyLine);
        return entry.resolvePublicKey(null, PublicKeyEntryResolver.FAILING);
    }

    // -----------------------------------------------------------------------
    // Inner type: key-loading strategy
    // -----------------------------------------------------------------------

    @FunctionalInterface
    interface KeyLoader {
        KeyPair load() throws Exception;
    }
}
