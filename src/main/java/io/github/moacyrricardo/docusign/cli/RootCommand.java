package io.github.moacyrricardo.docusign.cli;

import io.github.moacyrricardo.docusign.anchor.ScanCommand;
import io.github.moacyrricardo.docusign.auth.AuthCommand;
import io.github.moacyrricardo.docusign.auth.CachingTokenProvider;
import io.github.moacyrricardo.docusign.auth.JwtTokenMinter;
import io.github.moacyrricardo.docusign.auth.LoginCommand;
import io.github.moacyrricardo.docusign.auth.TokenProvider;
import io.github.moacyrricardo.docusign.config.Config;
import io.github.moacyrricardo.docusign.docusign.ApiClientFactory;
import io.github.moacyrricardo.docusign.docusign.Environment;
import io.github.moacyrricardo.docusign.envelope.EnvelopeCommand;
import io.github.moacyrricardo.docusign.envelope.EnvelopesCommand;
import io.github.moacyrricardo.docusign.output.JsonWriter;
import io.github.moacyrricardo.docusign.output.OutputFormat;
import io.github.moacyrricardo.docusign.output.OutputWriter;
import io.github.moacyrricardo.docusign.output.TableWriter;
import io.github.moacyrricardo.docusign.send.SendCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Callable;

/**
 * Root command and composition root (spec 002 §3.1). Registers the six subcommands and builds the
 * runtime {@link CliContext} the subcommands consume. The auth/API graph is built lazily — auth and
 * network only happen when a command actually calls {@code tokens.accessToken()} — so {@code scan}
 * and {@code --help} never touch DocuSign.
 *
 * <p>Invoked with no subcommand it prints usage and exits {@link ExitCode#USAGE}.
 */
@Command(
        name = "docusign-cli",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class,
        description = "Drive DocuSign eSignature workflows: login, list/inspect envelopes, "
                + "and send PDFs with anchor scanning.",
        subcommands = {
                LoginCommand.class,
                AuthCommand.class,
                ScanCommand.class,
                SendCommand.class,
                EnvelopesCommand.class,
                EnvelopeCommand.class
        })
public final class RootCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions globalOptions = new GlobalOptions();

    private CliContext context;

    /** The resolved global options (read by {@link CliExceptionHandler} for JSON/verbose mode). */
    public GlobalOptions globalOptions() {
        return globalOptions;
    }

    /**
     * Builds (once) and returns the runtime context subcommands consume. The token graph is wired
     * here but only exercised lazily by {@link CliContext#authenticatedApiClient()}.
     */
    public CliContext context() {
        if (context == null) {
            context = buildContext();
        }
        return context;
    }

    private CliContext buildContext() {
        Config config = Config.open();
        Environment environment = resolveEnvironment(config);
        ApiClientFactory factory = new ApiClientFactory(environment, config);
        JwtTokenMinter minter = new JwtTokenMinter(factory, config, environment);
        TokenProvider tokens = new CachingTokenProvider(config, minter);
        OutputWriter output = buildOutputWriter();
        return new CliContext(environment, output, globalOptions.yes, config, factory, tokens);
    }

    /** Environment resolution order (spec 002 §3.2, §7): explicit flag, then config, then DEMO. */
    private Environment resolveEnvironment(Config config) {
        return globalOptions.explicitEnvironment().orElseGet(() -> environmentFromConfig(config));
    }

    private static Environment environmentFromConfig(Config config) {
        if (!config.exists()) {
            return Environment.DEMO;
        }
        String baseUri = config.baseUri();
        if (baseUri == null) {
            return Environment.DEMO;
        }
        String hint = baseUri.toLowerCase();
        return hint.contains("prod") || hint.contains("account.docusign")
                ? Environment.PROD
                : Environment.DEMO;
    }

    private OutputWriter buildOutputWriter() {
        Writer sink = openSink();
        OutputFormat format = globalOptions.json ? OutputFormat.JSON : OutputFormat.TABLE;
        return format == OutputFormat.JSON ? new JsonWriter(sink) : new TableWriter(sink);
    }

    private Writer openSink() {
        if (globalOptions.output != null) {
            try {
                return Files.newBufferedWriter(globalOptions.output, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)), false);
    }

    @Override
    public Integer call() {
        // No subcommand selected: print usage and signal a usage error.
        new picocli.CommandLine(this).usage(System.err);
        return ExitCode.USAGE.code();
    }
}
