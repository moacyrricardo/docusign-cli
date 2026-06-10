package io.github.moacyrricardo.docusign.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.List;
import java.util.Map;

/**
 * Machine-facing {@link OutputWriter}: serializes the structured payload passed to
 * {@link #object(Object)} as one pretty-printed JSON document per invocation (spec 002 §5).
 * {@link #message(String)} is a no-op so JSON output stays clean and pipe-safe;
 * {@link #table}/{@link #record} are ignored (the JSON shape comes solely from {@code object}).
 */
public final class JsonWriter implements OutputWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final PrintWriter out;
    private boolean wrote;

    public JsonWriter(Writer out) {
        this.out = new PrintWriter(out, false);
    }

    @Override
    public void table(List<String> headers, List<List<String>> rows) {
        // Ignored: JSON output is driven by object(...).
    }

    @Override
    public void record(Map<String, ?> fields) {
        // Ignored: JSON output is driven by object(...).
    }

    @Override
    public void message(String text) {
        // No-op so JSON stays a single clean document.
    }

    @Override
    public void object(Object payload) {
        try {
            out.println(MAPPER.writeValueAsString(payload));
            wrote = true;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize output payload to JSON", e);
        }
        out.flush();
    }

    /** Whether any JSON document was emitted (test/diagnostic aid). */
    public boolean wrote() {
        return wrote;
    }

    @Override
    public void close() {
        out.flush();
        out.close();
    }
}
