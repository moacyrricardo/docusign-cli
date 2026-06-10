package io.github.moacyrricardo.docusign.output;

import java.util.List;
import java.util.Map;

/**
 * The single sink all commands write through (spec 002 §5). Commands must not call
 * {@code System.out} directly; they go through {@code CliContext.output()}.
 *
 * <p><b>Dual-representation contract.</b> A format-agnostic command emits <i>both</i>
 * representations unconditionally and never branches on the format: it calls {@link #object(Object)}
 * with a full structured payload <i>and</i> {@link #table(List, List)} / {@link #record(Map)} for
 * the human view. {@code JsonWriter} honors {@code object(...)} and ignores
 * {@code table/record/message}; {@code TableWriter} honors {@code table/record} and ignores
 * {@code object}.
 */
public interface OutputWriter extends AutoCloseable {

    /** A tabular result: ordered column headers + rows of cells. */
    void table(List<String> headers, List<List<String>> rows);

    /** A single record (e.g. envelope status) as key to value. */
    void record(Map<String, ?> fields);

    /** Free-form human message (status lines); suppressed in JSON mode. */
    void message(String text);

    /**
     * Structured payload commands hand off; the JSON writer serializes it, the table writer
     * ignores it in favor of {@link #table}/{@link #record}.
     */
    void object(Object payload);

    /** Flush; close the {@code --output} file if any. */
    @Override
    void close();
}
