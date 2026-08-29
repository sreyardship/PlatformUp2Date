package org.yardship.documentation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PrWorkflowTests {

    @Test
    void fastJob_runsConfCheckAlongsideBackendTests() throws IOException {
        String workflow = Files.readString(Path.of("../../.github/workflows/pr.yml"));
        int fastStart = workflow.indexOf("\n  fast:\n");
        int nextJob = workflow.indexOf("\n  native:\n", fastStart);
        assertTrue(fastStart >= 0, "the PR workflow must define a fast job");
        assertTrue(nextJob > fastStart, "the fast job must end before the next job");

        String fastJob = workflow.substring(fastStart, nextJob);
        assertTrue(fastJob.contains(
                        "run: gradle --no-daemon :backend:domain:test :backend:server:test :backend:conf-check:test"),
                "the PR fast job must run conf-check tests with the existing backend tests");
    }
}
