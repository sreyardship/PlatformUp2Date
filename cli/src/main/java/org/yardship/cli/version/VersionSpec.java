package org.yardship.cli.version;

import org.yardship.core.domain.primitives.CalverFormat;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.Optional;

/**
 * Resolved scheme configuration for a scheme-aware subcommand: {@code --scheme} (+
 * {@code --calver-format}) materialised as a reused {@link VersionParser}. Shared by every
 * scheme-aware subcommand (regex, pointer now; changelog/calver-format/config reuse it later) —
 * kept generic, not regex-specific.
 *
 * <p>Fail-fast: {@link #of(VersionScheme, String)} validates the scheme/format combination
 * immediately, mirroring {@link VersionParser}'s own fail-fast constructors, and wraps any
 * rejection in {@link VersionSpecException} so callers can translate it to
 * {@link org.yardship.cli.outcome.ValidationOutcome.ConfigInvalid} without depending on
 * {@code IllegalArgumentException} (an exception type too generic to safely catch narrowly).
 *
 * <p>(Issue 04) {@link #calverFormat()} additionally exposes the parsed {@link CalverFormat}
 * itself — {@code changelog} needs it directly (as {@code Optional<CalverFormat>}) to construct a
 * {@code ChangelogTemplate}, not just a {@link VersionParser} that hides it internally. Built
 * independently from the same {@code calverFormat} string {@link VersionParser} uses, under the
 * same fail-fast/{@link VersionSpecException} contract, so both stay in lockstep.
 */
public final class VersionSpec {

    private final VersionScheme scheme;
    private final VersionParser parser;
    private final CalverFormat calverFormat; // non-null only when scheme == CALVER

    private VersionSpec(VersionScheme scheme, VersionParser parser, CalverFormat calverFormat) {
        this.scheme = scheme;
        this.parser = parser;
        this.calverFormat = calverFormat;
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
            CalverFormat format = (scheme == VersionScheme.CALVER)
                    ? new CalverFormat(calverFormat)
                    : null;
            return new VersionSpec(scheme, parser, format);
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

    /**
     * The parsed {@link CalverFormat}, present only when {@link #scheme()} is
     * {@link VersionScheme#CALVER}.
     */
    public Optional<CalverFormat> calverFormat() {
        return Optional.ofNullable(calverFormat);
    }

    /** Raised when {@code --scheme}/{@code --calver-format} do not form valid parser config. */
    public static final class VersionSpecException extends RuntimeException {
        public VersionSpecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
