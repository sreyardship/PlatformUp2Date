package org.yardship.unit.adapters.in.metrics;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.in.metrics.PrometheusDriftRenderer;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusDriftRendererTests {

    private static final String HELP_LINE =
            "# HELP pu2d_version_drift_level How far the deployed version is behind latest "
                    + "(0=current, 1=patch, 2=minor, 3=major)";
    private static final String TYPE_LINE = "# TYPE pu2d_version_drift_level gauge";

    private final PrometheusDriftRenderer sut = new PrometheusDriftRenderer();

    @Test
    void render_emitsHeaderOnce_andOneSampleLinePerApp() {
        VersionApplication major = new VersionApplication("major-app",
                new Version("1.1.1"), new Version("2.2.2"));
        VersionApplication minor = new VersionApplication("minor-app",
                new Version("2.1.0"), new Version("2.2.0"));
        VersionApplication patch = new VersionApplication("patch-app",
                new Version("2.2.1"), new Version("2.2.2"));
        VersionApplication current = new VersionApplication("current-app",
                new Version("2.0.0"), new Version("2.0.0"));

        String output = sut.render(List.of(major, minor, patch, current));

        assertTrue(output.contains(HELP_LINE), "expected single HELP line in: " + output);
        assertTrue(output.contains(TYPE_LINE), "expected single TYPE line in: " + output);
        assertEquals(1, countOccurrences(output, HELP_LINE), "HELP must appear exactly once");
        assertEquals(1, countOccurrences(output, TYPE_LINE), "TYPE must appear exactly once");

        assertTrue(output.contains("pu2d_version_drift_level{app=\"major-app\"} 3"), output);
        assertTrue(output.contains("pu2d_version_drift_level{app=\"minor-app\"} 2"), output);
        assertTrue(output.contains("pu2d_version_drift_level{app=\"patch-app\"} 1"), output);
        assertTrue(output.contains("pu2d_version_drift_level{app=\"current-app\"} 0"), output);
    }

    @Test
    void render_emptyList_emitsHeaderOnly_noSampleLines() {
        String output = sut.render(List.of());

        assertTrue(output.contains(HELP_LINE), "expected HELP line in: " + output);
        assertTrue(output.contains(TYPE_LINE), "expected TYPE line in: " + output);
        assertFalse(output.contains("pu2d_version_drift_level{"),
                "expected no sample lines in: " + output);
    }

    @Test
    void render_escapesLabelValue_perExpositionSpec() {
        VersionApplication weird = new VersionApplication("we\"ird\\name",
                new Version("1.1.1"), new Version("2.2.2"));

        String output = sut.render(List.of(weird));

        assertTrue(output.contains("pu2d_version_drift_level{app=\"we\\\"ird\\\\name\"} 3"),
                "expected escaped label value in: " + output);
    }

    @Test
    void render_escapesNewlineInLabelValue_asLiteralBackslashN() {
        VersionApplication multiline = new VersionApplication("line1\nline2",
                new Version("1.1.1"), new Version("2.2.2"));

        String output = sut.render(List.of(multiline));

        // The embedded newline must become a literal backslash-n, not a real line break,
        // otherwise the sample would split across two physical lines and break parsing.
        assertTrue(output.contains("pu2d_version_drift_level{app=\"line1\\nline2\"} 3"),
                "expected newline escaped as literal \\n in: " + output);
    }

    @Test
    void render_everyLineNonEmpty_andEndsWithNewline() {
        VersionApplication app = new VersionApplication("some-app",
                new Version("1.1.1"), new Version("2.2.2"));

        String output = sut.render(List.of(app));

        assertTrue(output.endsWith("\n"), "output must end with newline: " + output);
        String[] lines = output.split("\n", -1);
        // last token after trailing newline is empty; ignore it
        for (int i = 0; i < lines.length - 1; i++) {
            assertFalse(lines[i].isEmpty(), "line " + i + " must be non-empty in: " + output);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
