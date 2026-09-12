package org.yardship.adapters.out.versionsource.current.ssh;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.current.CurrentVersionSourceFactory;
import org.yardship.adapters.out.versionsource.current.FailedCurrentSource;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.util.Optional;

/**
 * Factory for the {@code ssh-os-release} current-version kind. Discovered as a CDI bean;
 * validates its own config fragment and constructs a per-app {@link SshOsReleaseCurrentSource}.
 *
 * <h2>Validation split</h2>
 * <ul>
 *   <li><b>Boot-fail (throws {@link IllegalArgumentException})</b>: blank/absent {@code host};
 *       blank/absent {@code user}.</li>
 *   <li><b>Value-fail (returns {@link FailedCurrentSource})</b>: both {@code private-key} and
 *       {@code private-key-file} set; neither set; both {@code host-key} and {@code known-hosts}
 *       set; neither set.</li>
 *   <li><b>Runtime (thrown from {@code version()})</b>: unreachable host, host-key mismatch, auth
 *       failure, missing release-field, unparseable version value, bad {@code private-key-file}
 *       path.</li>
 * </ul>
 */
@ApplicationScoped
public class SshOsReleaseCurrentSourceFactory implements CurrentVersionSourceFactory {

    public SshOsReleaseCurrentSourceFactory() {
        // No CDI collaborators needed.
    }

    @Override
    public String type() {
        return "ssh-os-release";
    }

    @Override
    public CurrentVersionSource create(ApplicationConfigLoader.VersionSource cfg, VersionParser parser) {
        String host = cfg.host()
                .filter(v -> !v.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The 'ssh-os-release' current source requires a non-blank 'host'."));
        String user = cfg.user()
                .filter(v -> !v.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The 'ssh-os-release' current source requires a non-blank 'user'."));
        int port = cfg.port().orElse(22);
        String releaseField = cfg.releaseField().orElse(SshOsReleaseCurrentSource.DEFAULT_RELEASE_FIELD);

        Optional<String> keyValidationError = validateKeyCredentials(cfg, host);
        if (keyValidationError.isPresent()) {
            return new FailedCurrentSource(keyValidationError.get());
        }
        Optional<String> hostKeyValidationError = validateHostKeyConfig(cfg, host);
        if (hostKeyValidationError.isPresent()) {
            return new FailedCurrentSource(hostKeyValidationError.get());
        }

        SshOsReleaseCurrentSource.KeyLoader keyLoader = buildKeyLoader(cfg);
        ServerKeyVerifier serverKeyVerifier = buildServerKeyVerifier(cfg);

        return new SshOsReleaseCurrentSource(host, port, user, keyLoader, serverKeyVerifier,
                releaseField, parser);
    }

    private static Optional<String> validateKeyCredentials(
            ApplicationConfigLoader.VersionSource cfg, String host) {
        boolean hasInlineKey = cfg.privateKey().filter(v -> !v.isBlank()).isPresent();
        boolean hasKeyFile = cfg.privateKeyFile().filter(v -> !v.isBlank()).isPresent();
        if (hasInlineKey && hasKeyFile) {
            return Optional.of("The 'ssh-os-release' current source has both 'private-key' and "
                    + "'private-key-file' set for host '" + host + "'; this is ambiguous and refused.");
        }
        if (!hasInlineKey && !hasKeyFile) {
            return Optional.of("The 'ssh-os-release' current source for host '" + host + "' requires "
                    + "exactly one of 'private-key' or 'private-key-file'.");
        }
        return Optional.empty();
    }

    private static Optional<String> validateHostKeyConfig(
            ApplicationConfigLoader.VersionSource cfg, String host) {
        boolean hasPinnedKey = cfg.hostKey().filter(v -> !v.isBlank()).isPresent();
        boolean hasKnownHosts = cfg.knownHosts().filter(v -> !v.isBlank()).isPresent();
        if (hasPinnedKey && hasKnownHosts) {
            return Optional.of("The 'ssh-os-release' current source has both 'host-key' and "
                    + "'known-hosts' set for host '" + host + "'; this is ambiguous and refused.");
        }
        if (!hasPinnedKey && !hasKnownHosts) {
            return Optional.of("The 'ssh-os-release' current source for host '" + host + "' requires "
                    + "exactly one of 'host-key' or 'known-hosts' for server key verification "
                    + "(no trust-on-first-use).");
        }
        return Optional.empty();
    }

    private static SshOsReleaseCurrentSource.KeyLoader buildKeyLoader(
            ApplicationConfigLoader.VersionSource cfg) {
        return cfg.privateKey().filter(v -> !v.isBlank()).isPresent()
                ? SshOsReleaseCurrentSource.inlineKeyLoader(cfg.privateKey().get())
                : SshOsReleaseCurrentSource.fileKeyLoader(cfg.privateKeyFile().get());
    }

    private static ServerKeyVerifier buildServerKeyVerifier(ApplicationConfigLoader.VersionSource cfg) {
        return cfg.hostKey().filter(v -> !v.isBlank()).isPresent()
                ? SshOsReleaseCurrentSource.pinnedHostKeyVerifier(cfg.hostKey().get())
                : SshOsReleaseCurrentSource.knownHostsVerifier(cfg.knownHosts().get());
    }
}
