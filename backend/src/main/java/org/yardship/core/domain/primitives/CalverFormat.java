package org.yardship.core.domain.primitives;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and represents a calver.org format string (e.g. {@code "YYYY.0M.MICRO"}).
 *
 * <p>A format string is a sequence of calver.org tokens separated by literal {@code .}, {@code -},
 * or {@code _} characters. The set of recognised tokens is defined by {@link TokenType}.
 *
 * <p>Instances are immutable and are shared between the {@link CalverVersion} instances that
 * use them.
 */
public final class CalverFormat {

    /**
     * Every calver.org token. Zero-padded tokens from the calver.org spec ({@code 0Y}, {@code 0M},
     * {@code 0W}, {@code 0D}) use a {@code ZERO_} prefix because Java enum names cannot start
     * with a digit.
     */
    public enum TokenType {
        YYYY, YY, ZERO_Y,
        MM, ZERO_M,
        WW, ZERO_W,
        DD, ZERO_D,
        MAJOR, MINOR, MICRO, MODIFIER;

        /**
         * The {@link VersionValue.Diff} category that a change in this token represents.
         *
         * <ul>
         *   <li>MAJOR: year-class tokens ({@code YYYY}, {@code YY}, {@code 0Y}) and the
         *       semantic {@code MAJOR} token.</li>
         *   <li>MINOR: sub-year date tokens ({@code MM}, {@code 0M}, {@code WW}, {@code 0W},
         *       {@code DD}, {@code 0D}) and the semantic {@code MINOR} token.</li>
         *   <li>PATCH: fine-grain ({@code MICRO}) and pre-release ({@code MODIFIER}) tokens.</li>
         * </ul>
         */
        public VersionValue.Diff diffCategory() {
            return switch (this) {
                case YYYY, YY, ZERO_Y, MAJOR -> VersionValue.Diff.MAJOR;
                case MM, ZERO_M, WW, ZERO_W, DD, ZERO_D, MINOR -> VersionValue.Diff.MINOR;
                case MICRO, MODIFIER -> VersionValue.Diff.PATCH;
            };
        }
    }

    /** Maps format-string symbols to their {@link TokenType} enum constants. */
    private static final Map<String, TokenType> SYMBOL_TO_TYPE = Map.ofEntries(
            Map.entry("YYYY",     TokenType.YYYY),
            Map.entry("YY",       TokenType.YY),
            Map.entry("0Y",       TokenType.ZERO_Y),
            Map.entry("MM",       TokenType.MM),
            Map.entry("0M",       TokenType.ZERO_M),
            Map.entry("WW",       TokenType.WW),
            Map.entry("0W",       TokenType.ZERO_W),
            Map.entry("DD",       TokenType.DD),
            Map.entry("0D",       TokenType.ZERO_D),
            Map.entry("MAJOR",    TokenType.MAJOR),
            Map.entry("MINOR",    TokenType.MINOR),
            Map.entry("MICRO",    TokenType.MICRO),
            Map.entry("MODIFIER", TokenType.MODIFIER)
    );

    /**
     * Pattern to consume one element (token or separator) from the format string at a time.
     * Longer alternatives precede shorter ones to prevent partial matches
     * (e.g. {@code YYYY} before {@code YY}, {@code MODIFIER} before {@code MM}).
     */
    private static final Pattern FORMAT_ELEMENT = Pattern.compile(
            "YYYY|MODIFIER|MAJOR|MINOR|MICRO|YY|0Y|0M|0W|0D|MM|WW|DD|[.\\-_]"
    );

    private final List<TokenType> tokenList;
    private final List<String> separatorList; // separatorList.get(i) precedes tokenList.get(i); "" for i=0
    private final Pattern versionPattern;
    private final int modifierIndex;          // index of MODIFIER in tokenList, or -1

    /**
     * Constructs a {@code CalverFormat} by parsing the given format string.
     *
     * @param format the calver.org format string (e.g. {@code "YYYY.0M.MICRO"})
     * @throws IllegalArgumentException if {@code format} is null, blank, contains no tokens,
     *                                  or contains an unrecognised token symbol
     */
    public CalverFormat(String format) {
        if (format == null || format.isBlank()) {
            throw new IllegalArgumentException(
                    "CalverFormat: format string must not be null or blank");
        }

        List<TokenType> tokens = new ArrayList<>();
        List<String> separators = new ArrayList<>();
        String pendingSeparator = "";

        Matcher matcher = FORMAT_ELEMENT.matcher(format);
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() != lastEnd) {
                throw new IllegalArgumentException(
                        "CalverFormat: unrecognised content at position " + lastEnd
                        + " in format '" + format + "'");
            }
            String symbol = matcher.group();
            if (SYMBOL_TO_TYPE.containsKey(symbol)) {
                tokens.add(SYMBOL_TO_TYPE.get(symbol));
                separators.add(pendingSeparator);
                pendingSeparator = "";
            } else {
                pendingSeparator = symbol; // separator character — consumed by the next token
            }
            lastEnd = matcher.end();
        }
        if (lastEnd != format.length()) {
            throw new IllegalArgumentException(
                    "CalverFormat: unrecognised content at position " + lastEnd
                    + " in format '" + format + "'");
        }
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException(
                    "CalverFormat: format '" + format + "' contains no recognised tokens");
        }

        this.tokenList = Collections.unmodifiableList(tokens);
        this.separatorList = Collections.unmodifiableList(separators);
        this.versionPattern = buildVersionPattern(tokens, separators);
        this.modifierIndex = tokens.indexOf(TokenType.MODIFIER);
    }

    /** Returns the ordered list of parsed token types (separators are NOT included). */
    public List<TokenType> tokens() {
        return tokenList;
    }

    /**
     * Looks up the {@link TokenType} for a calver.org format-string symbol (e.g. {@code "0M"} →
     * {@link TokenType#ZERO_M}), or {@link Optional#empty()} if {@code symbol} names no known
     * calver.org token. Used by {@link ChangelogTemplate} to validate a placeholder token name.
     */
    public static Optional<TokenType> tokenTypeForSymbol(String symbol) {
        return Optional.ofNullable(SYMBOL_TO_TYPE.get(symbol));
    }

    /**
     * {@code true} when {@code symbol} names a calver.org token that this format actually declares
     * (i.e. is present in {@link #tokens()}). A symbol that is a real calver.org token in general
     * but absent from THIS format's declared tokens (e.g. {@code MICRO} on a {@code "YY.0M"} format)
     * returns {@code false}.
     */
    public boolean declaresSymbol(String symbol) {
        return tokenTypeForSymbol(symbol).map(tokenList::contains).orElse(false);
    }

    /** Package-private: the separator that precedes token {@code i} (empty string for index 0). */
    List<String> separators() {
        return separatorList;
    }

    /**
     * Package-private: index of the {@link TokenType#MODIFIER} token in {@link #tokens()}, or
     * {@code -1} if the format contains no {@code MODIFIER} token.
     */
    int modifierIndex() {
        return modifierIndex;
    }

    /**
     * Package-private: attempts to match {@code raw} against this format and returns the parsed
     * components, or {@code null} if the raw string does not match.
     */
    ParsedComponents tryParse(String raw) {
        if (raw == null) return null;
        Matcher matcher = versionPattern.matcher(raw);
        if (!matcher.matches()) return null;

        int n = tokenList.size();
        int[] numericValues = new int[n];
        String[] rawGroups = new String[n];
        String modifier = null;

        for (int i = 0; i < n; i++) {
            String group = matcher.group(i + 1);
            rawGroups[i] = group;
            if (tokenList.get(i) == TokenType.MODIFIER) {
                modifier = group; // null means absent
            } else {
                numericValues[i] = (group == null) ? 0 : Integer.parseInt(group);
            }
        }
        return new ParsedComponents(numericValues, modifier, rawGroups);
    }

    /**
     * Parsed numeric values, optional modifier string, and the per-token displayed substrings
     * (raw regex capture groups, zero-padding preserved; {@code null} for a trailing token absent
     * from the matched version string) extracted from a raw version string.
     */
    record ParsedComponents(int[] numericValues, String modifier, String[] rawGroups) {}

    // -----------------------------------------------------------------------
    // Regex builder
    // -----------------------------------------------------------------------

    /**
     * Builds a regex pattern that matches version strings against this format.
     *
     * <p>Trailing tokens are wrapped in nested optional groups so that a version string with
     * fewer components than the format is still valid (missing trailing components default to
     * {@code 0} or absent). For example, {@code "YY.0M.MICRO"} produces:
     * <pre>{@code ^(\d+)(?:\.(\d{2})(?:\.(\d+))?)?$}</pre>
     */
    private static Pattern buildVersionPattern(List<TokenType> tokens, List<String> separators) {
        int n = tokens.size();
        StringBuilder regex = new StringBuilder("^");

        regex.append("(").append(tokenPattern(tokens.get(0))).append(")");

        for (int i = 1; i < n; i++) {
            regex.append("(?:").append(Pattern.quote(separators.get(i)));
            regex.append("(").append(tokenPattern(tokens.get(i))).append(")");
        }

        // Close one ')?'  per additional token to produce nested optional groups.
        for (int i = 1; i < n; i++) {
            regex.append(")?");
        }

        regex.append("$");
        return Pattern.compile(regex.toString());
    }

    private static String tokenPattern(TokenType token) {
        return switch (token) {
            case YYYY              -> "\\d{4}";
            case YY, ZERO_Y        -> "\\d+";
            case MM, DD, WW        -> "\\d{1,2}";
            case ZERO_M, ZERO_W, ZERO_D -> "\\d{2}";
            case MAJOR, MINOR, MICRO    -> "\\d+";
            case MODIFIER          -> "[a-zA-Z][a-zA-Z0-9]*";
        };
    }
}
