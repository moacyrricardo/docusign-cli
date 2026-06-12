package io.github.moacyrricardo.docusign.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void tableWriterAlignsColumns() {
        StringWriter sink = new StringWriter();
        try (TableWriter writer = new TableWriter(sink)) {
            writer.table(
                    List.of("ID", "Status"),
                    List.of(
                            List.of("abc", "sent"),
                            List.of("longerid", "completed")));
        }
        String[] lines = sink.toString().split("\\R");
        // header, rule, two rows
        assertEquals(4, lines.length);
        assertEquals("ID        Status", lines[0]);
        assertEquals("--------  ---------", lines[1]);
        assertEquals("abc       sent", lines[2]);
        assertEquals("longerid  completed", lines[3]);
    }

    @Test
    void tableWriterRendersRecord() {
        StringWriter sink = new StringWriter();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("envelopeId", "abc-123");
        fields.put("status", "sent");
        try (TableWriter writer = new TableWriter(sink)) {
            writer.record(fields);
        }
        String[] lines = sink.toString().split("\\R");
        assertEquals("envelopeId  abc-123", lines[0]);
        assertEquals("status      sent", lines[1]);
    }

    @Test
    void tableWriterIgnoresObject() {
        StringWriter sink = new StringWriter();
        try (TableWriter writer = new TableWriter(sink)) {
            writer.object(Map.of("ignored", true));
        }
        assertEquals("", sink.toString());
    }

    @Test
    void jsonWriterSerializesObjectAsValidJson() throws Exception {
        StringWriter sink = new StringWriter();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("envelopeId", "abc-123");
        payload.put("count", 2);
        try (JsonWriter writer = new JsonWriter(sink)) {
            writer.object(payload);
        }
        JsonNode node = MAPPER.readTree(sink.toString());
        assertEquals("abc-123", node.get("envelopeId").asText());
        assertEquals(2, node.get("count").asInt());
    }

    @Test
    void jsonWriterSuppressesMessageAndTableAndRecord() {
        StringWriter sink = new StringWriter();
        try (JsonWriter writer = new JsonWriter(sink)) {
            writer.message("human noise");
            writer.table(List.of("A"), List.of(List.of("x")));
            writer.record(Map.of("k", "v"));
        }
        assertTrue(sink.toString().isEmpty(), "JSON writer must ignore message/table/record");
    }

    @Test
    void jsonWriterTracksWhetherItEmitted() {
        StringWriter sink = new StringWriter();
        try (JsonWriter writer = new JsonWriter(sink)) {
            assertFalse(writer.wrote());
            writer.object(Map.of("k", "v"));
            assertTrue(writer.wrote());
        }
    }
}
