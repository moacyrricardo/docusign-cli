package io.github.moacyrricardo.docusign.output;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Human-facing {@link OutputWriter}: renders aligned columns for {@link #table} and a key/value
 * layout for {@link #record} (spec 002 §5). Honors {@code table}/{@code record}/{@code message};
 * ignores {@link #object(Object)} (that is the JSON writer's job).
 */
public final class TableWriter implements OutputWriter {

    private static final String COLUMN_GAP = "  ";

    private final PrintWriter out;

    public TableWriter(Writer out) {
        this.out = new PrintWriter(out, false);
    }

    @Override
    public void table(List<String> headers, List<List<String>> rows) {
        int columns = headers.size();
        int[] widths = new int[columns];
        for (int i = 0; i < columns; i++) {
            widths[i] = cell(headers.get(i)).length();
        }
        for (List<String> row : rows) {
            for (int i = 0; i < columns; i++) {
                widths[i] = Math.max(widths[i], cell(i < row.size() ? row.get(i) : "").length());
            }
        }

        out.println(formatRow(headers, widths));
        List<String> rule = new ArrayList<>(columns);
        for (int width : widths) {
            rule.add("-".repeat(width));
        }
        out.println(formatRow(rule, widths));
        for (List<String> row : rows) {
            out.println(formatRow(row, widths));
        }
        out.flush();
    }

    @Override
    public void record(Map<String, ?> fields) {
        int keyWidth = 0;
        for (String key : fields.keySet()) {
            keyWidth = Math.max(keyWidth, key.length());
        }
        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            out.println(pad(entry.getKey(), keyWidth) + COLUMN_GAP + cell(stringOf(entry.getValue())));
        }
        out.flush();
    }

    @Override
    public void message(String text) {
        out.println(text);
        out.flush();
    }

    @Override
    public void object(Object payload) {
        // Intentionally ignored: the table writer renders the human view via table()/record().
    }

    @Override
    public void close() {
        out.flush();
        out.close();
    }

    private static String formatRow(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) {
                sb.append(COLUMN_GAP);
            }
            String value = (i < cells.size()) ? cell(cells.get(i)) : "";
            sb.append(pad(value, widths[i]));
        }
        return stripTrailing(sb.toString());
    }

    private static String pad(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        return value + " ".repeat(width - value.length());
    }

    private static String cell(String value) {
        return value == null ? "" : value;
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }
}
