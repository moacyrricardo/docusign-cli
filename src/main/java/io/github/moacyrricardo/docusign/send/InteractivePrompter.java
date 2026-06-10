package io.github.moacyrricardo.docusign.send;

/**
 * The stdin/stdout prompting seam for Mode B and the pre-send confirmation (spec 005 §6/§7). An
 * interface so tests inject scripted answers without real I/O.
 */
public interface InteractivePrompter {

    /** Yes/no question; {@code defaultYes} is returned on an empty (Enter) answer. */
    boolean confirm(String question, boolean defaultYes);

    /**
     * Free-text question with a default returned on Enter. {@code null} default means an answer is
     * required (empty input re-prompts).
     */
    String ask(String question, String defaultValue);
}
