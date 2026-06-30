package org.yardship.adapters.out.versionsource.current.ssh;

/**
 * Extracts a named field from {@code /etc/os-release} text.
 *
 * <p>Fields follow the {@code KEY=VALUE} or {@code KEY="VALUE"} format defined by the
 * <a href="https://www.freedesktop.org/software/systemd/man/os-release.html">os-release spec</a>.
 * Surrounding double-quotes or single-quotes are stripped so callers receive a bare string
 * (e.g. {@code VERSION_ID="24.04"} → {@code 24.04}).
 *
 * <p>This collaborator is a pure function over the captured text returned by
 * {@code cat /etc/os-release}: it performs no I/O and holds no state. It is held and called
 * by {@link SshOsReleaseCurrentSource} after the SSH command output has been captured.
 *
 * <p>Public API surface:
 * <ul>
 *   <li>{@link #extractField(String, String)} — the single entry point.</li>
 * </ul>
 */
public class OsReleaseParser {

    /**
     * Extracts the value of {@code fieldName} from the given {@code /etc/os-release} text.
     *
     * @param osReleaseText raw text returned by {@code cat /etc/os-release}
     * @param fieldName     the field to look up (e.g. {@code VERSION_ID} or {@code BUILD_ID})
     * @return the field value with surrounding quotes stripped
     * @throws IllegalStateException if the field is not present in the text
     */
    public String extractField(String osReleaseText, String fieldName) {
        String prefix = fieldName + "=";
        for (String line : osReleaseText.split("\n")) {
            if (line.startsWith(prefix)) {
                String value = line.substring(prefix.length()).trim();
                return stripSurroundingQuotes(value);
            }
        }
        throw new IllegalStateException(
                "Field '" + fieldName + "' not found in /etc/os-release output");
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
}
