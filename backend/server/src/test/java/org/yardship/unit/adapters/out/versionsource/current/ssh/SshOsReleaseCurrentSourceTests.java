package org.yardship.unit.adapters.out.versionsource.current.ssh;

import org.apache.sshd.client.SshClient;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.yardship.adapters.out.versionsource.current.ssh.SshOsReleaseCurrentSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.io.Closeable;
import java.net.ServerSocket;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@code SshOsReleaseCurrentSource} — specifically the <em>deferred-lifecycle
 * contract</em> required by slice 01:
 *
 * <ol>
 *   <li>The constructor must store config and collaborators ONLY — no {@link SshClient} built,
 *       started, or network/security-provider touched. (Criterion 1)</li>
 *   <li>{@code version()} must create-and-close an {@code SshClient} per call; a connect failure
 *       surfaces as an exception thrown from {@code version()}, not from the constructor or from
 *       {@code factory.create(...)}. (Criteria 2–3)</li>
 *   <li>{@code close()} must be a no-op — no long-lived client to stop, safe to call any number
 *       of times. (Criterion 2)</li>
 * </ol>
 *
 * <p>No real SSH server is needed. The connect failure against a closed port is the stimulus for
 * the {@code version()}-throws assertion.
 *
 * <p>Fleet-level isolation (other apps still succeed when one SSH source's {@code version()}
 * throws) is already covered by {@code ScrapeServiceTests} — this class does NOT duplicate
 * that service-level coverage.
 */
class SshOsReleaseCurrentSourceTests {

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);
    private static final SshOsReleaseCurrentSourceFactory FACTORY = new SshOsReleaseCurrentSourceFactory();

    // -----------------------------------------------------------------------
    // Criterion 1: constructor MUST NOT build or start an SshClient
    // -----------------------------------------------------------------------

    /**
     * FAILS NOW: {@code SshOsReleaseCurrentSource}'s constructor calls
     * {@code SshClient.setUpDefaultClient()} (and {@code client.start()}) eagerly at line ~77.
     * <p>
     * PASSES AFTER FIX: the constructor only stores its arguments; all SSH client
     * construction is deferred to {@code version()}.
     */
    @Test
    void factoryCreate_doesNotCallSetUpDefaultClient_constructorStoresConfigOnly() {
        try (MockedStatic<SshClient> mockedStatic = Mockito.mockStatic(SshClient.class)) {
            // Provide a mock client so that IF setUpDefaultClient() is called it doesn't NPE.
            SshClient mockClient = Mockito.mock(SshClient.class);
            mockedStatic.when(SshClient::setUpDefaultClient).thenReturn(mockClient);

            // Calling factory.create() invokes the SshOsReleaseCurrentSource constructor.
            CurrentVersionSource source = FACTORY.create(minimalSsh().build(), SEMVER_PARSER);

            // The constructor must NOT have called setUpDefaultClient() — all SSH setup is
            // deferred to version() which creates and closes a client per call.
            // (MockedStatic.verify() does not accept a message argument; failure is self-evident
            //  because the only caller is SshOsReleaseCurrentSource's constructor.)
            mockedStatic.verify(SshClient::setUpDefaultClient, Mockito.never());
        }
    }

    // -----------------------------------------------------------------------
    // Criterion 3: connect/auth/exec failure surfaces from version(), not constructor
    // -----------------------------------------------------------------------

    /**
     * A source pointed at a closed port must be constructable without error; the
     * connect failure must surface only when {@code version()} is called.
     *
     * <p>This also acts as a timing guard: construction must return promptly because
     * no network I/O happens there.
     */
    @Test
    void construction_againstClosedPort_doesNotThrow_andVersionThrows() throws Exception {
        // Bind then immediately close to obtain a port guaranteed to refuse connections.
        int closedPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            closedPort = ss.getLocalPort();
        }

        // Construction MUST succeed — no network contact happens in the constructor.
        CurrentVersionSource source = FACTORY.create(
                minimalSsh()
                        .withHost(Optional.of("127.0.0.1"))
                        .withPort(Optional.of(closedPort))
                        .build(),
                SEMVER_PARSER);
        assertNotNull(source, "factory.create() must return a source even for an unreachable host");

        // version() MUST throw — the connect failure surfaces here, isolating only this app.
        assertThrows(Exception.class, source::version,
                "connect failure to a closed port must throw from version(), not from construction; " +
                "this is the per-app isolation contract");
    }

    // -----------------------------------------------------------------------
    // Criterion 2: close() is a no-op
    // -----------------------------------------------------------------------

    /**
     * Closing a source whose {@code version()} was never called must not throw.
     * After the fix there is no long-lived client to stop, so close() is truly a no-op.
     */
    @Test
    void close_beforeAnyVersionCall_doesNotThrow() {
        CurrentVersionSource source = FACTORY.create(minimalSsh().build(), SEMVER_PARSER);
        assertInstanceOf(Closeable.class, source);

        assertDoesNotThrow(
                () -> ((Closeable) source).close(),
                "close() must be a no-op and must not throw, even when version() was never called");
    }

    /**
     * close() must be idempotent — safe to call any number of times.
     *
     * <p>With the current implementation, the second call invokes {@code client.stop()} on an
     * already-stopped client, which may or may not throw depending on MINA's internals. After the
     * fix, close() is a no-op and trivially safe to repeat.
     */
    @Test
    void close_calledMultipleTimes_doesNotThrow() throws Exception {
        CurrentVersionSource source = FACTORY.create(minimalSsh().build(), SEMVER_PARSER);
        Closeable closeable = (Closeable) source;

        closeable.close(); // first close
        assertDoesNotThrow(
                closeable::close,
                "close() called a second time must not throw — it is a no-op on the fixed source; " +
                "with the current implementation client.stop() is called on an already-stopped client");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Minimal valid SSH config: fake inline PEM + fake pinned host-key.
     * The host/port are left as-is from minimalSsh() (unreachable domain, default port 22) unless
     * overridden by the individual test — construction must succeed regardless.
     */
    private static SshOsReleaseCurrentSourceFactoryTests.VersionSourceBuilder minimalSsh() {
        return new SshOsReleaseCurrentSourceFactoryTests.VersionSourceBuilder()
                .withHost(Optional.of("myvm.example.com"))
                .withUser(Optional.of("admin"))
                .withPrivateKey(Optional.of(
                        "-----BEGIN OPENSSH PRIVATE KEY-----\nfakefakefake\n-----END OPENSSH PRIVATE KEY-----"))
                .withHostKey(Optional.of("ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAgQtest...placeholder"));
    }
}
