package com.ggn.simplequestion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class JsonCodec {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_SETS = 500;
    public static final int MAX_QUESTIONS = 10000;
    public static final int MAX_TEXT = 20000;
    public static final int MAX_NAME = 100;

    private JsonCodec() {
    }

    public static List<QuestionSet> decode(String json, boolean importCopy) throws DataException {
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new DataException("文件不能超过 10 MB");
        }
        try {
            String input = json.startsWith("\uFEFF") ? json.substring(1) : json;
            JSONTokener tokener = new JSONTokener(input);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) {
                throw new DataException("JSON 须为一个完整的对象");
            }
            JSONObject root = (JSONObject) value;
            Object version = root.opt("schemaVersion");
            if (!(version instanceof Number) || ((Number) version).doubleValue() != 1.0) {
                throw new DataException("不支持的格式版本，schemaVersion 须为 1");
            }
            JSONArray sets = array(root, "sets");
            if (sets.length() > MAX_SETS) {
                throw new DataException("最多支持 500 个题集");
            }
            List<QuestionSet> result = new ArrayList<>();
            Set<String> identifiers = new HashSet<>();
            int totalQuestions = 0;
            for (int setIndex = 0; setIndex < sets.length(); setIndex++) {
                JSONObject item = sets.getJSONObject(setIndex);
                String name = text(item, "name", MAX_NAME);
                JSONArray questions = array(item, "questions");
                totalQuestions += questions.length();
                if (totalQuestions > MAX_QUESTIONS) {
                    throw new DataException("全部题集合计最多支持 10,000 道题");
                }
                List<Question> parsedQuestions = new ArrayList<>();
                for (int questionIndex = 0; questionIndex < questions.length(); questionIndex++) {
                    JSONObject question = questions.getJSONObject(questionIndex);
                    parsedQuestions.add(new Question(
                            identifier(question, importCopy, identifiers),
                            text(question, "prompt", MAX_TEXT),
                            text(question, "answer", MAX_TEXT)));
                }
                result.add(new QuestionSet(identifier(item, importCopy, identifiers), name, parsedQuestions));
            }
            return result;
        } catch (JSONException | StackOverflowError error) {
            throw new DataException("JSON 格式不正确，请检查 name、questions、prompt 和 answer 字段", error);
        }
    }

    public static String encode(List<QuestionSet> sets) throws DataException {
        if (sets.size() > MAX_SETS) {
            throw new DataException("最多支持 500 个题集");
        }
        try {
            JSONArray items = new JSONArray();
            int totalQuestions = 0;
            Set<String> identifiers = new HashSet<>();
            for (QuestionSet set : sets) {
                validateText(set.name, "题集名称", MAX_NAME);
                validateIdentifier(set.id, identifiers);
                totalQuestions += set.questions.size();
                if (totalQuestions > MAX_QUESTIONS) {
                    throw new DataException("全部题集合计最多支持 10,000 道题");
                }
                JSONArray questions = new JSONArray();
                for (Question question : set.questions) {
                    validateText(question.prompt, "题面", MAX_TEXT);
                    validateText(question.answer, "答案", MAX_TEXT);
                    validateIdentifier(question.id, identifiers);
                    questions.put(new JSONObject().put("id", question.id)
                            .put("prompt", question.prompt).put("answer", question.answer));
                }
                items.put(new JSONObject().put("id", set.id).put("name", set.name).put("questions", questions));
            }
            String result = new JSONObject().put("schemaVersion", 1).put("sets", items).toString(2);
            if (result.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                throw new DataException("题库总大小不能超过 10 MB，请减少内容后重试");
            }
            return result;
        } catch (JSONException error) {
            throw new DataException("无法生成 JSON", error);
        }
    }

    public static void validateText(String value, String label, int maxLength) throws DataException {
        if (value == null || value.trim().isEmpty()) {
            throw new DataException(label + "不能为空");
        }
        if (value.length() > maxLength) {
            throw new DataException(label + "最多支持 " + maxLength + " 个字符");
        }
    }

    private static JSONArray array(JSONObject object, String key) throws DataException {
        Object value = object.opt(key);
        if (!(value instanceof JSONArray)) {
            throw new DataException(key + " 须为数组");
        }
        return (JSONArray) value;
    }

    private static String text(JSONObject object, String key, int maxLength) throws DataException {
        Object value = object.opt(key);
        if (!(value instanceof String)) {
            throw new DataException(key + " 须为字符串");
        }
        validateText((String) value, key, maxLength);
        return (String) value;
    }

    private static String identifier(JSONObject object, boolean importCopy, Set<String> identifiers)
            throws DataException {
        if (importCopy) {
            String generated = UUID.randomUUID().toString();
            identifiers.add(generated);
            return generated;
        }
        String value = text(object, "id", 100);
        validateIdentifier(value, identifiers);
        return value;
    }

    private static void validateIdentifier(String value, Set<String> identifiers) throws DataException {
        validateText(value, "内部 ID", 100);
        if (!identifiers.add(value)) {
            throw new DataException("题库包含重复 ID");
        }
    }
}
