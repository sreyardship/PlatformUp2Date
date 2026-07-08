package org.yardship.core.domain.primitives;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed, immutable app-level changelog URL template (ADR-0021: the changelog link is a
 * read-time projection from an app-level template — no source kind gets a default).
 *
 * <p>Pure domain value object — no I/O. Placeholders of the form {@code {token}} are validated
 * against the app's {@link VersionScheme} (and, for {@link VersionScheme#CALVER}, its declared
 * {@link CalverFormat}) fail-fast at construction time, so an illegal placeholder can never
 * survive to read time. Placeholders:
 * <ul>
 *   <li>{@code {version}} — {@link VersionValue#value()}, legal for both schemes.</li>
 *   <li>{@code {major}}/{@code {minor}}/{@code {patch}} — legal only for {@link VersionScheme#SEMVER}.</li>
 *   <li>A calver.org format-symbol token (e.g. {@code {YY}}, {@code {0M}}, {@code {MICRO}}) —
 *       legal only for {@link VersionScheme#CALVER}, and only when the symbol is one of the
 *       app's declared {@code calver-format} tokens. Values are the displayed substrings of the
 *       matched version string, zero-padding preserved — never re-rendered numbers.</li>
 * </ul>
 * A token-free template is legal (a constant URL).
 */
public final class ChangelogTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");
    private static final String VERSION_TOKEN = "version";
    private static final List<String> SEMVER_COMPONENT_TOKENS = List.of("major", "minor", "patch");

    private final String rawTemplate;
    private final VersionScheme scheme;
    private final CalverFormat calverFormat; // non-null only when scheme == CALVER

    /**
     * @throws IllegalArgumentException if {@code rawTemplate} contains a placeholder that is
     *         unknown, illegal for {@code scheme} (a semver token on a calver app or vice versa),
     *         or — for a calver app — a token symbol absent from {@code calverFormat}'s declared
     *         tokens. The message names the offending placeholder.
     */
    public ChangelogTemplate(String rawTemplate, VersionScheme scheme, Optional<CalverFormat> calverFormat) {
        this.rawTemplate = rawTemplate;
        this.scheme = scheme;
        this.calverFormat = calverFormat.orElse(null);
        validatePlaceholders();
    }

    /** Substitutes every placeholder with the corresponding component of {@code version}. */
    public String resolve(VersionValue version) {
        Matcher matcher = PLACEHOLDER.matcher(rawTemplate);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1);
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(resolveToken(token, version)));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private void validatePlaceholders() {
        Matcher matcher = PLACEHOLDER.matcher(rawTemplate);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!isLegal(token)) {
                throw new IllegalArgumentException(
                        "Changelog template placeholder '{" + token + "}' is not a legal token for a "
                                + scheme + " app (template: '" + rawTemplate + "').");
            }
        }
    }

    private boolean isLegal(String token) {
        if (VERSION_TOKEN.equals(token)) {
            return true;
        }
        return switch (scheme) {
            case SEMVER -> SEMVER_COMPONENT_TOKENS.contains(token);
            case CALVER -> calverFormat != null && calverFormat.declaresSymbol(token);
        };
    }

    private String resolveToken(String token, VersionValue version) {
        if (VERSION_TOKEN.equals(token)) {
            return version.value();
        }
        return switch (scheme) {
            case SEMVER -> resolveSemverToken(token, (SemverVersion) version);
            case CALVER -> resolveCalverToken(token, (CalverVersion) version);
        };
    }

    private static String resolveSemverToken(String token, SemverVersion version) {
        return switch (token) {
            case "major" -> version.major();
            case "minor" -> version.minor();
            case "patch" -> version.patch();
            default -> throw new IllegalStateException(
                    "Unreachable: '" + token + "' was validated as legal at construction");
        };
    }

    private String resolveCalverToken(String token, CalverVersion version) {
        CalverFormat.TokenType type = CalverFormat.tokenTypeForSymbol(token)
                .orElseThrow(() -> new IllegalStateException(
                        "Unreachable: '" + token + "' was validated as legal at construction"));
        // A declared-but-trailing token can be absent from the actual matched version string
        // (e.g. format YY.0M.MICRO but version "23.05"). That's a legitimate application state,
        // not a construction-time error, so it displays as nothing rather than throwing.
        String displayedValue = version.displayedValue(type);
        return displayedValue != null ? displayedValue : "";
    }
}
