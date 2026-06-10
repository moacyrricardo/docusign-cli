package io.github.moacyrricardo.docusign.send;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.Locale;

/**
 * A {@link InteractivePrompter} backed by stdin/stderr (spec 005 §6). Prompts go to stderr so the
 * primary (table/JSON) output on stdout stays clean and pipe-safe.
 */
public final class ConsoleInteractivePrompter implements InteractivePrompter {

    private final BufferedReader in;
    private final PrintStream prompt;

    public ConsoleInteractivePrompter() {
        this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.err);
    }

    ConsoleInteractivePrompter(BufferedReader in, PrintStream prompt) {
        this.in = in;
        this.prompt = prompt;
    }

    @Override
    public boolean confirm(String question, boolean defaultYes) {
        String suffix = defaultYes ? " [Y/n] " : " [y/N] ";
        while (true) {
            prompt.print(question + suffix);
            prompt.flush();
            String line = readLine();
            if (line == null || line.isBlank()) {
                return defaultYes;
            }
            String answer = line.trim().toLowerCase(Locale.ROOT);
            if (answer.equals("y") || answer.equals("yes")) {
                return true;
            }
            if (answer.equals("n") || answer.equals("no")) {
                return false;
            }
            prompt.println("Please answer y or n.");
        }
    }

    @Override
    public String ask(String question, String defaultValue) {
        while (true) {
            prompt.print(question + (defaultValue != null ? " [" + defaultValue + "] " : " "));
            prompt.flush();
            String line = readLine();
            if (line == null || line.isBlank()) {
                if (defaultValue != null) {
                    return defaultValue;
                }
                prompt.println("An answer is required.");
                continue;
            }
            return line.trim();
        }
    }

    private String readLine() {
        try {
            return in.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
