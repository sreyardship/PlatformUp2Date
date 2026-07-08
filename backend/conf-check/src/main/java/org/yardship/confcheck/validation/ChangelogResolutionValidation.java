package org.yardship.confcheck.validation;

import org.yardship.confcheck.outcome.ValidationOutcome;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.CalverFormat;
import org.yardship.core.domain.primitives.ChangelogTemplate;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.Optional;

/**
 * Validates a {@code changelog-url} template (ADR-0021): a PURE-FUNCTION check — no
 * {@link org.yardship.confcheck.port.BodySource}, no network. Parses {@code versionRaw} under
 * {@code parser}, constructs a {@link org.yardship.core.domain.primitives.ChangelogTemplate}
 * (which fail-fasts on an illegal placeholder for the configured scheme), and resolves it against
 * the parsed version.
 *
 * <p>Outcome mapping (see {@link ValidationOutcome.ChangelogOk}'s design note for the full
 * rationale):
 * <ul>
 *   <li>Template legal for {@code scheme} and {@code versionRaw} parses →
 *       {@link ValidationOutcome.ChangelogOk} with the resolved URL.</li>
 *   <li>Template contains a placeholder illegal for {@code scheme} (or, for calver, a symbol not
 *       declared in {@code calverFormat}) → {@link ValidationOutcome.ConfigInvalid}, naming the
 *       offending token — the {@code ChangelogTemplate} constructor's message, verbatim.</li>
 *   <li>{@code versionRaw} does not parse under {@code parser} → {@link ValidationOutcome.ConfigInvalid}.
 *       Chosen over {@link ValidationOutcome.FetchFailed}/{@link ValidationOutcome.ValidButEmpty}
 *       because this subcommand has no body-acquisition step for either of those to describe: with
 *       no {@code --url}/{@code --body-file}, {@code --version} is part of the CLI invocation
 *       itself (like {@code --scheme}), so an unparseable value is a malformed invocation.</li>
 * </ul>
 */
public final class ChangelogResolutionValidation {

    /**
     * @param template   the raw {@code changelog-url} template, e.g. {@code "https://x/{major}.{minor}"}.
     * @param versionRaw the raw {@code --version} string to parse and resolve the template against.
     * @param parser     the scheme-configured parser (built from {@code --scheme}/{@code --calver-format}).
     * @param calverFormat the parsed {@code calver-format}, present only when {@code --scheme=calver}.
     */
    public ValidationOutcome validate(
            String template, String versionRaw, VersionParser parser, Optional<CalverFormat> calverFormat) {
        VersionValue version;
        try {
            version = parser.parse(versionRaw);
        } catch (InvalidVersionException e) {
            return new ValidationOutcome.ConfigInvalid(
                    "--version '" + versionRaw + "' does not parse as a " + parser.scheme() + " version: "
                            + e.getMessage());
        }

        ChangelogTemplate changelogTemplate;
        try {
            changelogTemplate = new ChangelogTemplate(template, parser.scheme(), calverFormat);
        } catch (IllegalArgumentException e) {
            return new ValidationOutcome.ConfigInvalid(e.getMessage());
        }

        return new ValidationOutcome.ChangelogOk(changelogTemplate.resolve(version));
    }
}
