package org.yardship.confcheck.validation;

import org.yardship.confcheck.outcome.CalverMapping;
import org.yardship.confcheck.outcome.ValidationOutcome;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.CalverFormat;
import org.yardship.core.domain.primitives.CalverVersion;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates a {@code calver-format} spec (ADR-tbd): a PURE-FUNCTION check — no
 * {@link org.yardship.confcheck.port.BodySource}, no network. Unlike {@code changelog}
 * ({@link ChangelogResolutionValidation}), there is no {@code --scheme} flag here: the scheme is
 * implicitly {@code CALVER}, so this validation builds its own
 * {@link org.yardship.core.domain.primitives.CalverFormat} and
 * {@link org.yardship.core.domain.primitives.VersionParser} internally from {@code format} rather
 * than receiving them pre-built.
 *
 * <p>Outcome mapping (see {@link ValidationOutcome.CalverOk}'s design note for the full
 * rationale — mirrors issue 04's {@code changelog} precedent):
 * <ul>
 *   <li>{@code format} parses as a legal {@code CalverFormat} and {@code versionRaw} parses
 *       against it → {@link ValidationOutcome.CalverOk} with the resolved token -> displayed-value
 *       mapping.</li>
 *   <li>{@code format} is null/blank/malformed/contains an unknown token →
 *       {@link ValidationOutcome.ConfigInvalid}, the {@code CalverFormat} constructor's message,
 *       verbatim.</li>
 *   <li>{@code versionRaw} does not parse under the built {@code format} →
 *       {@link ValidationOutcome.ConfigInvalid} (design call, consistent with issue 04: no
 *       body-acquisition step for {@code FetchFailed}/{@code ValidButEmpty} to describe —
 *       {@code --version} is part of the invocation itself, like {@code --format}).</li>
 * </ul>
 */
public final class CalverFormatValidation {

    /**
     * Reverse of {@link CalverFormat}'s own {@code SYMBOL_TO_TYPE} table (which is not exposed),
     * used to render each declared token back to its format-string symbol (e.g.
     * {@link CalverFormat.TokenType#ZERO_M} -> {@code "0M"}) in {@link CalverMapping.TokenDisplay}.
     */
    private static final Map<CalverFormat.TokenType, String> TYPE_TO_SYMBOL = Map.ofEntries(
            Map.entry(CalverFormat.TokenType.YYYY, "YYYY"),
            Map.entry(CalverFormat.TokenType.YY, "YY"),
            Map.entry(CalverFormat.TokenType.ZERO_Y, "0Y"),
            Map.entry(CalverFormat.TokenType.MM, "MM"),
            Map.entry(CalverFormat.TokenType.ZERO_M, "0M"),
            Map.entry(CalverFormat.TokenType.WW, "WW"),
            Map.entry(CalverFormat.TokenType.ZERO_W, "0W"),
            Map.entry(CalverFormat.TokenType.DD, "DD"),
            Map.entry(CalverFormat.TokenType.ZERO_D, "0D"),
            Map.entry(CalverFormat.TokenType.MAJOR, "MAJOR"),
            Map.entry(CalverFormat.TokenType.MINOR, "MINOR"),
            Map.entry(CalverFormat.TokenType.MICRO, "MICRO"),
            Map.entry(CalverFormat.TokenType.MODIFIER, "MODIFIER")
    );

    /**
     * @param format     the raw {@code --format} calver.org format string, e.g. {@code "YY.0M.MICRO"}.
     * @param versionRaw the raw {@code --version} string to parse and resolve against {@code format}.
     */
    public ValidationOutcome validate(String format, String versionRaw) {
        CalverFormat calverFormat;
        try {
            calverFormat = new CalverFormat(format);
        } catch (IllegalArgumentException e) {
            return new ValidationOutcome.ConfigInvalid(e.getMessage());
        }

        VersionValue parsed;
        try {
            parsed = new VersionParser(VersionScheme.CALVER, format).parse(versionRaw);
        } catch (InvalidVersionException e) {
            return new ValidationOutcome.ConfigInvalid(
                    "--version '" + versionRaw + "' does not parse as a CALVER version: " + e.getMessage());
        }
        CalverVersion calverVersion = (CalverVersion) parsed;

        List<CalverMapping.TokenDisplay> tokenDisplays = new ArrayList<>();
        for (CalverFormat.TokenType tokenType : calverFormat.tokens()) {
            String displayedValue = calverVersion.displayedValue(tokenType);
            if (displayedValue != null) {
                tokenDisplays.add(new CalverMapping.TokenDisplay(TYPE_TO_SYMBOL.get(tokenType), displayedValue));
            }
        }

        return new ValidationOutcome.CalverOk(new CalverMapping(tokenDisplays));
    }
}
