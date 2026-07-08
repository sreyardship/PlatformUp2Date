package org.yardship.confcheck;

import org.yardship.confcheck.command.CalverCommand;
import org.yardship.confcheck.command.ChangelogCommand;
import org.yardship.confcheck.command.ConfigCommand;
import org.yardship.confcheck.command.PointerCommand;
import org.yardship.confcheck.command.RegexCommand;
import picocli.CommandLine.Command;

/**
 * The {@code conf-check} root: subcommands attach here one per issue (regex, pointer, changelog,
 * calver, config).
 */
@Command(name = "conf-check", mixinStandardHelpOptions = true,
        subcommands = {RegexCommand.class, PointerCommand.class, ChangelogCommand.class, CalverCommand.class,
                ConfigCommand.class})
public final class RootCommand implements Runnable {

    @Override
    public void run() {
        // No-op: picocli prints usage when no subcommand is given (see Main).
    }
}
