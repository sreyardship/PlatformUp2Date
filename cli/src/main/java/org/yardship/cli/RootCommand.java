package org.yardship.cli;

import org.yardship.cli.command.CalverCommand;
import org.yardship.cli.command.ChangelogCommand;
import org.yardship.cli.command.ConfigCommand;
import org.yardship.cli.command.PointerCommand;
import org.yardship.cli.command.RegexCommand;
import picocli.CommandLine.Command;

/**
 * The {@code cli} root: subcommands attach here one per issue (regex, pointer, changelog, calver,
 * config).
 */
@Command(name = "cli", mixinStandardHelpOptions = true,
        subcommands = {RegexCommand.class, PointerCommand.class, ChangelogCommand.class, CalverCommand.class,
                ConfigCommand.class})
public final class RootCommand implements Runnable {

    @Override
    public void run() {
        // No-op: picocli prints usage when no subcommand is given (see Main).
    }
}
