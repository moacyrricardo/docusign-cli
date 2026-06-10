package io.github.moacyrricardo.docusign.cli;

import io.github.moacyrricardo.docusign.auth.AuthCommand;
import io.github.moacyrricardo.docusign.auth.AuthStatusCommand;
import io.github.moacyrricardo.docusign.auth.LoginCommand;
import io.github.moacyrricardo.docusign.envelope.EnvelopeCommand;
import io.github.moacyrricardo.docusign.envelope.EnvelopeStatusCommand;
import io.github.moacyrricardo.docusign.envelope.EnvelopesCommand;
import io.github.moacyrricardo.docusign.envelope.EnvelopesListCommand;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParseResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliWiringTest {

    private CommandLine root() {
        return new CommandLine(new RootCommand())
                .setCaseInsensitiveEnumValuesAllowed(true);
    }

    @Test
    void allRootSubcommandsResolve() {
        Map<String, CommandLine> subs = root().getSubcommands();
        for (String name : new String[] {"login", "auth", "scan", "send", "envelopes", "envelope"}) {
            assertTrue(subs.containsKey(name), "missing root subcommand: " + name);
        }
    }

    @Test
    void authHostsStatusLeaf() {
        CommandLine auth = root().getSubcommands().get("auth");
        assertNotNull(auth);
        assertTrue(auth.getSubcommands().containsKey("status"));
        assertEquals(AuthStatusCommand.class,
                auth.getSubcommands().get("status").getCommand().getClass());
        assertEquals(AuthCommand.class, auth.getCommand().getClass());
        assertEquals(LoginCommand.class,
                root().getSubcommands().get("login").getCommand().getClass());
    }

    @Test
    void envelopesHostsListLeaf() {
        CommandLine envelopes = root().getSubcommands().get("envelopes");
        assertTrue(envelopes.getSubcommands().containsKey("list"));
        assertEquals(EnvelopesListCommand.class,
                envelopes.getSubcommands().get("list").getCommand().getClass());
        assertEquals(EnvelopesCommand.class, envelopes.getCommand().getClass());
    }

    @Test
    void envelopeHostsStatusLeaf() {
        CommandLine envelope = root().getSubcommands().get("envelope");
        assertTrue(envelope.getSubcommands().containsKey("status"));
        assertEquals(EnvelopeStatusCommand.class,
                envelope.getSubcommands().get("status").getCommand().getClass());
        assertEquals(EnvelopeCommand.class, envelope.getCommand().getClass());
    }

    @Test
    void globalOptionParsesBeforeSubcommand() {
        // `scan` requires a <pdf> positional (spec 004 §5); supply a dummy so parsing completes.
        ParseResult result = root().parseArgs("--json", "scan", "doc.pdf");
        RootCommand rootCmd = (RootCommand) result.commandSpec().userObject();
        assertTrue(rootCmd.globalOptions().json);
        assertTrue(result.subcommand().commandSpec().name().equals("scan"));
    }

    @Test
    void globalOptionParsesAfterSubcommand() {
        // --json is mixed into the subcommand too, so it parses after the name.
        ParseResult result = root().parseArgs("scan", "doc.pdf", "--json");
        ParseResult scan = result.subcommand();
        assertEquals("scan", scan.commandSpec().name());
        // The mixin on the subcommand captured --json.
        CommandSpec scanSpec = scan.commandSpec();
        assertTrue(scan.hasMatchedOption("--json"));
        assertNotNull(scanSpec);
    }

    @Test
    void demoAndProdAreMutuallyExclusive() {
        CommandLine cli = new CommandLine(new RootCommand());
        int exit = cli.execute("--demo", "--prod", "scan");
        assertEquals(ExitCode.USAGE.code(), exit);
    }

    @Test
    void bareInvocationIsUsageError() {
        CommandLine cli = new CommandLine(new RootCommand());
        int exit = cli.execute();
        assertEquals(ExitCode.USAGE.code(), exit);
    }

    // --- spec 008: per-subcommand --help and de-duplicated --demo/--prod ---

    @Test
    void everySubcommandAdvertisesHelp() {
        CommandLine root = root();
        for (String name : new String[] {"login", "auth", "scan", "send", "envelopes", "envelope"}) {
            String usage = root.getSubcommands().get(name).getUsageMessage();
            assertTrue(usage.contains("-h, --help"),
                    name + " usage should advertise -h, --help:\n" + usage);
        }
    }

    @Test
    void subcommandHelpShortCircuitsToSuccess() {
        // Previously `send --help` errored with "Missing required parameter: '<pdf>'".
        CommandLine cli = new CommandLine(new RootCommand());
        assertEquals(ExitCode.OK.code(), cli.execute("send", "--help"));
        assertEquals(ExitCode.OK.code(), cli.execute("envelopes", "list", "--help"));
    }

    @Test
    void environmentFlagsAreListedOnce() {
        // Regression: --demo/--prod were each listed twice (ArgGroup-in-mixin double registration).
        assertEquals(1, optionListings(root().getUsageMessage(), "--demo"));
        assertEquals(1, optionListings(root().getUsageMessage(), "--prod"));
        String sendUsage = root().getSubcommands().get("send").getUsageMessage();
        assertEquals(1, optionListings(sendUsage, "--demo"));
        assertEquals(1, optionListings(sendUsage, "--prod"));
    }

    @Test
    void rootAdvertisesVersion() {
        assertTrue(root().getUsageMessage().contains("-V, --version"));
    }

    /** Counts option-list entries for {@code flag} (lines that begin with it), ignoring the synopsis. */
    private static long optionListings(String usage, String flag) {
        return usage.lines().filter(l -> l.strip().startsWith(flag)).count();
    }
}
