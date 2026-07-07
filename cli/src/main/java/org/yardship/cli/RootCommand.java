package org.yardship.cli;

import org.yardship.cli.command.PointerCommand;
import org.yardship.cli.command.RegexCommand;
import picocli.CommandLine.Command;

/**
 * The {@code cli} root: subcommands attach here one per issue (regex, pointer now; changelog/
 * calver-format/config in issues 04-06).
 */
@Command(name = "cli", mixinStandardHelpOptions = true, subcommands = {RegexCommand.class, PointerCommand.class})
public final class RootCommand implements Runnable {

    @Override
    public void run() {
        // No-op: picocli prints usage when no subcommand is given (see Main).
    }
}
