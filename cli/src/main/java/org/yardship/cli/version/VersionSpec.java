package org.yardship.cli.version;

import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;

/**
 * Resolved scheme configuration for a scheme-aware subcommand: {@code --scheme} (+
 * {@code --calver-format}) materialised as a reused {@link VersionParser}. Shared by every
 * scheme-aware subcommand (regex now; pointer/changelog/calver-format/config reuse it later) —
 * kept generic, not regex-specific.
 *
 * <p>Fail-fast: {@link #of(VersionScheme, String)} validates the scheme/format combination
 * immediately, mirroring {@link VersionParser}'s own fail-fast constructors, and wraps any
 * rejection in {@link VersionSpecException} so callers can translate it to
 * {@link org.yardship.cli.outcome.ValidationOutcome.ConfigInvalid} without depending on
 * {@code IllegalArgumentException} (an exception type too generic to safely catch narrowly).
 */
public final class VersionSpec {

    private final VersionScheme scheme;
    private final VersionParser parser;

    private VersionSpec(VersionScheme scheme, VersionParser parser) {
        this.scheme = scheme;
        this.parser = parser;
    }

    /**
     * @param scheme       the configured version scheme.
     * @param calverFormat the calver format string; ignored when {@code scheme} is not
     *                     {@link VersionScheme#CALVER} (may be {@code null} in that case).
     * @throws VersionSpecException if {@code scheme} is {@link VersionScheme#CALVER} and
     *                               {@code calverFormat} is null, blank, or contains an unknown
     *                               token.
     */
    public static VersionSpec of(VersionScheme scheme, String calverFormat) {
        try {
            VersionParser parser = (scheme == VersionScheme.CALVER)
                    ? new VersionParser(scheme, calverFormat)
                    : new VersionParser(scheme);
            return new VersionSpec(scheme, parser);
        } catch (IllegalArgumentException e) {
            throw new VersionSpecException(e.getMessage(), e);
        }
    }

    public VersionScheme scheme() {
        return scheme;
    }

    public VersionParser parser() {
        return parser;
    }

    /** Raised when {@code --scheme}/{@code --calver-format} do not form valid parser config. */
    public static final class VersionSpecException extends RuntimeException {
        public VersionSpecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
