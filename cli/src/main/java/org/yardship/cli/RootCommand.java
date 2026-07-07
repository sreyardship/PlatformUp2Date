package org.yardship.cli;

import org.yardship.cli.command.RegexCommand;
import picocli.CommandLine.Command;

/**
 * The {@code cli} root: subcommands attach here one per issue (regex now; pointer/changelog/
 * calver-format/config in issues 03-06).
 */
@Command(name = "cli", mixinStandardHelpOptions = true, subcommands = {RegexCommand.class})
public final class RootCommand implements Runnable {

    @Override
    public void run() {
        // No-op: picocli prints usage when no subcommand is given (see Main).
    }
}
