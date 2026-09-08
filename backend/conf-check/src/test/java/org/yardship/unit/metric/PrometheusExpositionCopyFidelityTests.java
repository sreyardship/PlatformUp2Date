package org.yardship.unit.metric;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mechanical drift guard over issue 05's deliberate parser copy.
 *
 * <p>{@code :backend:conf-check} cannot depend on {@code :backend:server}, so the {@code metric}
 * subcommand carries its own hand copy of the backend's Prometheus text-exposition parser. Issue
 * 05's binding acceptance criterion is that "the command's verdict agrees with the backend source's
 * behaviour for the same body and options — no case where the CLI passes and the scrape fails, or
 * the reverse", and a 280-line grammar duplicated by hand is the most direct possible threat to it.
 *
 * <p>The near-identical parser test classes on both sides are NOT a sufficient guard on their own:
 * they catch a change to this module's copy, but not a change to the BACKEND's — which is the
 * direction that actually matters, since the backend is the authority this CLI exists to predict.
 * A backend-side change would leave both test suites green and the two parsers silently disagreeing.
 *
 * <p>So this test compares the two files directly, ignoring package/import lines, comments and
 * whitespace — everything that legitimately differs — and asserts the executable logic is identical.
 * When it fails, the fix is to port the change across, not to relax the comparison. If the parser
 * ever gains a third consumer, or the copy becomes genuinely expensive to keep in step, that is the
 * signal to promote it to a shared module rather than to delete this test.
 *
 * <p>Note the copy exists because {@code plan.md} classes the exposition parser as adapter-local —
 * "it models a wire format, not a business fact" — so promoting it into {@code :backend:domain}
 * would put an adapter concern in the domain module. The copy is the lesser of the two evils, but
 * only while something enforces it.
 */
class PrometheusExpositionCopyFidelityTests {

    private static final String BACKEND_PARSER =
            "backend/server/src/main/java/org/yardship/adapters/out/versionsource/current/httpprometheus/";
    private static final String CONF_CHECK_PARSER =
            "backend/conf-check/src/main/java/org/yardship/confcheck/metric/";

    @Test
    void prometheusExposition_logicIsIdenticalToTheBackendsCopy() throws IOException {
        assertLogicIdentical("PrometheusExposition.java");
    }

    @Test
    void prometheusSample_logicIsIdenticalToTheBackendsCopy() throws IOException {
        assertLogicIdentical("PrometheusSample.java");
    }

    private static void assertLogicIdentical(String fileName) throws IOException {
        Path repoRoot = repoRoot();
        Path backend = repoRoot.resolve(BACKEND_PARSER + fileName);
        Path confCheck = repoRoot.resolve(CONF_CHECK_PARSER + fileName);

        // A missing file is itself drift: one side was moved, renamed or deleted without the other.
        assertTrue(Files.exists(backend), "backend copy not found at " + backend
                + " — if it moved, update this test and re-verify the copies still agree");
        assertTrue(Files.exists(confCheck), "conf-check copy not found at " + confCheck);

        assertEquals(logicOf(backend), logicOf(confCheck),
                fileName + ": the conf-check copy has drifted from the backend's. Issue 05 requires "
                        + "the metric command's verdict to predict the scrape's, which it cannot do "
                        + "if the two parsers disagree. Port the change across rather than relaxing "
                        + "this comparison.");
    }

    /**
     * Walks up from the working directory to the repo root, identified by {@code settings.gradle}.
     * Gradle runs tests with the module directory as the working directory, so this is one or two
     * levels up, but searching makes the test independent of how it is launched.
     */
    private static Path repoRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("settings.gradle"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("could not locate the repo root (no settings.gradle above "
                    + Path.of("").toAbsolutePath() + ")");
        }
        return candidate;
    }

    /**
     * The executable logic of a source file: every line with its package declaration, imports,
     * javadoc, block and line comments, and all indentation removed. Those are exactly the parts
     * that MUST differ between the two copies (different packages, different cross-references), so
     * comparing anything else would make the guard unusable.
     */
    private static List<String> logicOf(Path file) throws IOException {
        String source = Files.readString(file);
        String withoutBlockComments = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL)
                .matcher(source).replaceAll("");
        return withoutBlockComments.lines()
                .map(line -> line.replaceAll("//.*", ""))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("package ") && !line.startsWith("import "))
                .toList();
    }
}
