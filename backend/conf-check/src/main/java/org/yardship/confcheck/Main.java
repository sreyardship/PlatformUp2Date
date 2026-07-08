package org.yardship.confcheck;

import picocli.CommandLine;

/** Entry point: delegates to picocli and exits with the resolved subcommand's exit code. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RootCommand()).execute(args);
        System.exit(exitCode);
    }
}
