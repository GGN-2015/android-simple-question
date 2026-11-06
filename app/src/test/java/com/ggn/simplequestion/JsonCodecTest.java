package com.ggn.simplequestion;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class JsonCodecTest {
    private static final String VALID = "{\"schemaVersion\":1,\"sets\":[{\"name\":\"中文题集\",\"questions\":["
            + "{\"prompt\":\"题面\\n第二行\",\"answer\":\" Answer\\n答案！ \"}]}]}";

    @Test
    public void roundTripPreservesExactTextAndIds() throws Exception {
        List<QuestionSet> imported = JsonCodec.decode(VALID, true);
        String encoded = JsonCodec.encode(imported);
        List<QuestionSet> restored = JsonCodec.decode(encoded, false);
        assertEquals(imported.get(0).id, restored.get(0).id);
        assertEquals(imported.get(0).questions.get(0).id, restored.get(0).questions.get(0).id);
        assertEquals(" Answer\n答案！ ", restored.get(0).questions.get(0).answer);
        assertEquals("题面\n第二行", restored.get(0).questions.get(0).prompt);
    }

    @Test
    public void repeatedImportsAlwaysGetFreshIds() throws Exception {
        String encoded = JsonCodec.encode(JsonCodec.decode(VALID, true));
        QuestionSet first = JsonCodec.decode(encoded, true).get(0);
        QuestionSet second = JsonCodec.decode(encoded, true).get(0);
        assertNotEquals(first.id, second.id);
        assertNotEquals(first.questions.get(0).id, second.questions.get(0).id);
    }

    @Test
    public void acceptsUtf8BomAndEmptyQuestionSet() throws Exception {
        assertEquals(1, JsonCodec.decode("\uFEFF" + VALID, true).size());
        assertEquals(0, JsonCodec.decode("{\"schemaVersion\":1,\"sets\":[{\"name\":\"Empty\",\"questions\":[]}]}", true)
                .get(0).questions.size());
        assertTrue(JsonCodec.decode(JsonCodec.encode(Collections.emptyList()), false).isEmpty());
    }

    @Test
    public void rejectsWrongVersionMissingFieldsAndWrongFieldTypes() {
        for (String invalid : new String[]{"{}", "[]", "null", "", "{", "{\"schemaVersion\":2,\"sets\":[]}",
                "{\"schemaVersion\":\"1\",\"sets\":[]}", "{\"schemaVersion\":1,\"sets\":{}}",
                VALID.replace("\"中文题集\"", "42"), VALID.replace("\"中文题集\"", "\"   \""),
                VALID.replace("\"answer\"", "\"missing\""), VALID.replace("\"questions\":[", "\"questions\":null,\"other\":["),
                VALID + " garbage", VALID + "{}"}) {
            assertThrows("Should reject " + invalid, DataException.class, () -> JsonCodec.decode(invalid, true));
        }
    }

    @Test
    public void rejectsDuplicateStoredIds() throws Exception {
        String encoded = JsonCodec.encode(JsonCodec.decode(VALID, true));
        QuestionSet set = JsonCodec.decode(encoded, false).get(0);
        String duplicate = encoded.replace(set.questions.get(0).id, set.id);
        assertThrows(DataException.class, () -> JsonCodec.decode(duplicate, false));
    }

    @Test
    public void rejectsMissingStoredIdsButImportAllowsThem() throws Exception {
        assertThrows(DataException.class, () -> JsonCodec.decode(VALID, false));
        assertEquals(1, JsonCodec.decode(VALID, true).size());
    }

    @Test
    public void rejectsOverlongNameAndAnswer() {
        assertThrows(DataException.class, () -> JsonCodec.decode(VALID.replace("中文题集", "a".repeat(101)), true));
        assertThrows(DataException.class, () -> JsonCodec.decode(VALID.replace("答案！", "a".repeat(20001)), true));
    }

    @Test
    public void rejectsTooManySetsAndOversizedFile() {
        assertThrows(DataException.class, () -> JsonCodec.decode("{\"schemaVersion\":1,\"sets\":["
                + String.join(",", Collections.nCopies(501, "{\"name\":\"n\",\"questions\":[]}")) + "]}", true));
        assertThrows(DataException.class, () -> JsonCodec.decode(" ".repeat(JsonCodec.MAX_BYTES + 1), true));
    }

    @Test
    public void encoderAlsoEnforcesLimitsBeforeWriting() {
        QuestionSet invalid = new QuestionSet("set", "valid", Collections.singletonList(new Question("q", "prompt", " ")));
        assertThrows(DataException.class, () -> JsonCodec.encode(Collections.singletonList(invalid)));
        assertThrows(DataException.class, () -> JsonCodec.encode(Collections.nCopies(501, invalid)));
    }
}
