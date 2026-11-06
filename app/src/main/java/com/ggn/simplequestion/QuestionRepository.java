package com.ggn.simplequestion;

import android.content.Context;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class QuestionRepository {
    private final AtomicFile file;
    private List<QuestionSet> sets = Collections.emptyList();
    private boolean writable;

    public QuestionRepository(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "question-sets.json"));
    }

    public void load() throws DataException {
        writable = false;
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) {
            writable = true;
            return;
        }
        try (InputStream stream = file.openRead()) {
            sets = Collections.unmodifiableList(JsonCodec.decode(readUtf8(stream), false));
            writable = true;
        } catch (IOException error) {
            throw new DataException("无法读取本地题库，原文件已保留，请重试", error);
        }
    }

    public List<QuestionSet> getSets() {
        return sets;
    }

    public QuestionSet find(String id) {
        for (QuestionSet set : sets) {
            if (set.id.equals(id)) {
                return set;
            }
        }
        return null;
    }

    public void create(String name) throws DataException {
        List<QuestionSet> candidate = new ArrayList<>(sets);
        candidate.add(new QuestionSet(UUID.randomUUID().toString(), name.trim(), Collections.emptyList()));
        save(candidate);
    }

    public void rename(String id, String name) throws DataException {
        QuestionSet set = requireSet(id);
        replace(new QuestionSet(set.id, name.trim(), set.questions));
    }

    public void remove(String id) throws DataException {
        requireSet(id);
        List<QuestionSet> candidate = new ArrayList<>(sets);
        candidate.removeIf(set -> set.id.equals(id));
        save(candidate);
    }

    public void putQuestion(String setId, String questionId, String prompt, String answer) throws DataException {
        QuestionSet set = requireSet(setId);
        List<Question> questions = new ArrayList<>(set.questions);
        if (questionId == null) {
            questions.add(new Question(UUID.randomUUID().toString(), prompt, answer));
        } else {
            boolean found = false;
            for (int index = 0; index < questions.size(); index++) {
                if (questions.get(index).id.equals(questionId)) {
                    questions.set(index, new Question(questionId, prompt, answer));
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new DataException("这道题已不存在");
            }
        }
        replace(new QuestionSet(set.id, set.name, questions));
    }

    public void removeQuestion(String setId, String questionId) throws DataException {
        QuestionSet set = requireSet(setId);
        List<Question> questions = new ArrayList<>(set.questions);
        questions.removeIf(question -> question.id.equals(questionId));
        replace(new QuestionSet(set.id, set.name, questions));
    }

    public void importSets(List<QuestionSet> imported) throws DataException {
        List<QuestionSet> candidate = new ArrayList<>(sets);
        candidate.addAll(imported);
        save(candidate);
    }

    public static String readUtf8(InputStream input) throws IOException, DataException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int readCount;
        while ((readCount = input.read(buffer)) != -1) {
            if (output.size() + readCount > JsonCodec.MAX_BYTES) {
                throw new DataException("文件不能超过 10 MB");
            }
            output.write(buffer, 0, readCount);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray())).toString();
        } catch (CharacterCodingException error) {
            throw new DataException("请选择 UTF-8 编码的 JSON 文件", error);
        }
    }

    private QuestionSet requireSet(String id) throws DataException {
        QuestionSet set = find(id);
        if (set == null) {
            throw new DataException("题集已不存在");
        }
        return set;
    }

    private void replace(QuestionSet replacement) throws DataException {
        List<QuestionSet> candidate = new ArrayList<>(sets);
        for (int index = 0; index < candidate.size(); index++) {
            if (candidate.get(index).id.equals(replacement.id)) {
                candidate.set(index, replacement);
                save(candidate);
                return;
            }
        }
        throw new DataException("题集已不存在");
    }

    private void save(List<QuestionSet> candidate) throws DataException {
        if (!writable) {
            throw new DataException("本地题库未成功读取，为保护原数据，已禁止覆盖");
        }
        byte[] encoded = JsonCodec.encode(candidate).getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(encoded);
            file.finishWrite(output);
            sets = Collections.unmodifiableList(new ArrayList<>(candidate));
        } catch (IOException error) {
            if (output != null) {
                file.failWrite(output);
            }
            throw new DataException("保存失败，请检查可用存储空间；原数据未改变", error);
        }
    }
}
