package com.replayx;

import com.replayx.cli.CaptureCommand;
import com.replayx.cli.DiffCommand;
import com.replayx.cli.InspectCommand;
import com.replayx.cli.ReplayCommand;
import com.replayx.cli.ValidateCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** ReplayX: record HTTP traffic, replay it against a new backend, detect regressions. */
@Command(
        name = "replayx",
        description = "Record HTTP traffic, replay it against a new backend, and detect regressions.",
        subcommands = {
                CaptureCommand.class,
                ReplayCommand.class,
                DiffCommand.class,
                InspectCommand.class,
                ValidateCommand.class
        },
        mixinStandardHelpOptions = true,
        version = "ReplayX 0.1.0")
public class ReplayX implements Runnable {
    /** Success. */
    public static final int EXIT_OK = 0;
    /** Operational / usage error. */
    public static final int EXIT_ERROR = 1;
    /** Regressions detected with --fail-on-regression. */
    public static final int EXIT_REGRESSION = 2;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int code = new CommandLine(new ReplayX()).execute(args);
        System.exit(code);
    }
}
