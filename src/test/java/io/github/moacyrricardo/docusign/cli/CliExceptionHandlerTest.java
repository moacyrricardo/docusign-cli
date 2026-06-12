package io.github.moacyrricardo.docusign.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moacyrricardo.docusign.config.ConfigException;
import io.github.moacyrricardo.docusign.docusign.DocuSignException;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliExceptionHandlerTest {

    /** A throwaway root whose single subcommand throws whatever we inject. */
    @Command(name = "docusign-cli", subcommands = ThrowingCommand.class)
    static final class TestRoot extends RootCommand {
    }

    @Command(name = "boom")
    static final class ThrowingCommand implements Callable<Integer> {
        static RuntimeException toThrow;

        @Mixin
        GlobalOptions globalOptions;

        @Override
        public Integer call() {
            throw toThrow;
        }
    }

    private record Run(int exit, String out, String err) {
    }

    private Run execute(RuntimeException ex, String... args) {
        ThrowingCommand.toThrow = ex;
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new TestRoot())
                .setExecutionExceptionHandler(new CliExceptionHandler())
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err));
        int exit = cli.execute(args);
        return new Run(exit, out.toString(), err.toString());
    }

    @Test
    void configExceptionMapsToConfigCode() {
        Run run = execute(new ConfigException("missing creds"), "boom");
        assertEquals(ExitCode.CONFIG.code(), run.exit());
        assertTrue(run.err().contains("missing creds"));
        assertTrue(run.out().isEmpty(), "diagnostics must not pollute stdout");
    }

    @Test
    void docuSignNotFoundMapsToNotFoundCode() {
        Run run = execute(DocuSignException.notFound("envelope 404", null), "boom");
        assertEquals(ExitCode.NOTFOUND.code(), run.exit());
    }

    @Test
    void docuSignNetworkMapsToNetworkCode() {
        Run run = execute(DocuSignException.network("timeout", null), "boom");
        assertEquals(ExitCode.NETWORK.code(), run.exit());
    }

    @Test
    void genericCliExceptionUsesItsCarriedCode() {
        Run run = execute(new CliException(ExitCode.INPUT, "bad pdf"), "boom");
        assertEquals(ExitCode.INPUT.code(), run.exit());
    }

    @Test
    void unexpectedExceptionMapsToSoftwareCode() {
        Run run = execute(new IllegalStateException("kaboom"), "boom");
        assertEquals(ExitCode.SOFTWARE.code(), run.exit());
        assertTrue(run.err().contains("kaboom"));
    }

    @Test
    void jsonModeEmitsErrorObjectOnStdout() throws Exception {
        Run run = execute(new ConfigException("missing creds"), "--json", "boom");
        assertEquals(ExitCode.CONFIG.code(), run.exit());
        var node = new ObjectMapper().readTree(run.out());
        assertEquals("missing creds", node.get("error").asText());
        assertTrue(run.err().contains("missing creds"));
    }

    @Test
    void nonJsonModeWritesNothingToStdout() {
        Run run = execute(new ConfigException("x"), "boom");
        assertFalse(run.out().contains("error"));
    }
}
