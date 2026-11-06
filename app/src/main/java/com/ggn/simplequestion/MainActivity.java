package com.ggn.simplequestion;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(246, 247, 243);
    private static final int INK = Color.rgb(31, 47, 43);
    private static final int MUTED = Color.rgb(110, 122, 115);
    private static final int GREEN = Color.rgb(25, 115, 103);
    private static final int PALE = Color.rgb(230, 240, 232);
    private static final int RED = Color.rgb(167, 62, 51);
    private static final int IMPORT_FILE = 41;
    private static final int EXPORT_FILE = 42;
    private static final int PAGE_SIZE = 30;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private QuestionRepository repository;
    private SharedPreferences preferences;
    private LinearLayout content;
    private QuizSession quiz;
    private EditText answerInput;
    private String quizDraft = "";
    private String selectedId;
    private String screen = "home";
    private int visibleCount = PAGE_SIZE;
    private boolean ready;
    private boolean busy;
    private boolean filePickerOpen;
    private AlertDialog editor;
    private ProgressDialog activeProgress;
    private EditText editorFirst;
    private EditText editorSecond;
    private String editorKind;
    private String editorSetId;
    private String editorQuestionId;
    private Bundle initialState;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        repository = new QuestionRepository(this);
        preferences = getSharedPreferences("quiz", MODE_PRIVATE);
        initialState = state;
        if (state != null) {
            screen = state.getString("screen", "home");
            selectedId = state.getString("selectedId");
            visibleCount = state.getInt("visibleCount", PAGE_SIZE);
            filePickerOpen = state.getBoolean("filePickerOpen");
        }
        loadData();
    }

    private void loadData() {
        page("简问", "正在读取本地题库…", false);
        WORKER.execute(() -> {
            try {
                repository.load();
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    ready = true;
                    restoreQuiz();
                    render();
                    restoreEditor();
                });
            } catch (DataException error) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        new AlertDialog.Builder(this).setTitle("题库读取失败")
                                .setMessage(error.getMessage() + "\n\n原数据未被覆盖。")
                                .setPositiveButton("重试", (dialog, which) -> loadData())
                                .setNegativeButton("退出", (dialog, which) -> finish())
                                .setCancelable(false).show();
                    }
                });
            }
        });
    }

    private void render() {
        answerInput = null;
        if ("quiz".equals(screen) && quiz != null) {
            showQuiz();
        } else if ("detail".equals(screen) && repository.find(selectedId) != null) {
            showDetail();
        } else {
            screen = "home";
            showHome();
        }
    }

    private void showHome() {
        page("我的题集", "把知识，变成自己的答案。", false);
        int total = 0;
        for (QuestionSet set : repository.getSets()) {
            total += set.questions.size();
        }
        LinearLayout summary = card(PALE);
        text(summary, "简问  /  SIMPLE QUESTION", 11, GREEN, true);
        TextView count = text(summary, repository.getSets().size() + " 个题集   ·   " + total + " 道题", 23, INK, true);
        topMargin(count, 10);
        TextView note = text(summary, "本地保存，随时练习。无需账户，无需联网。", 12, MUTED, false);
        topMargin(note, 8);
        LinearLayout actions = row(content);
        weightedButton(actions, "+ 新建题集", true, this::newSet);
        weightedButton(actions, "导入 JSON", false, this::pickImport);
        Button export = weightedButton(actions, "导出全部", false, () -> exportSets(repository.getSets(), "简问-全部题集.json"));
        export.setEnabled(!repository.getSets().isEmpty());
        section("题集", "点击题集管理问题");
        List<QuestionSet> sets = repository.getSets();
        if (sets.isEmpty()) {
            LinearLayout empty = card(Color.WHITE);
            text(empty, "从第一个问题开始", 21, INK, true);
            TextView description = text(empty, "创建一个题集，记下题面和答案。\n也可以导入已有的 JSON 题库。", 14, MUTED, false);
            topMargin(description, 12);
            addButton(empty, "创建我的第一个题集", true, this::newSet);
            return;
        }
        for (int index = 0; index < Math.min(visibleCount, sets.size()); index++) {
            QuestionSet set = sets.get(index);
            LinearLayout item = card(Color.WHITE);
            LinearLayout heading = row(item);
            TextView title = text(heading, set.name, 19, INK, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button more = smallButton(heading, "更多", () -> { });
            more.setContentDescription(set.name + "：重命名、导出或删除");
            more.setOnClickListener(view -> setMenu(view, set));
            TextView meta = text(item, set.questions.size() + " 道问题  ·  自动保存", 12, MUTED, false);
            topMargin(meta, 5);
            LinearLayout buttons = row(item);
            weightedButton(buttons, "管理问题", false, () -> openSet(set.id));
            Button start = weightedButton(buttons, "开始自测  →", true, () -> startQuiz(set));
            start.setEnabled(!set.questions.isEmpty());
            item.setOnClickListener(view -> openSet(set.id));
            item.setContentDescription(set.name + "，" + set.questions.size() + " 道题");
        }
        moreItems(sets.size());
        footer("数据仅保存在此设备。建议定期导出 JSON 备份。");
    }

    private void showDetail() {
        QuestionSet set = repository.find(selectedId);
        page(set.name, set.questions.size() + " 道问题  ·  更改后自动保存", true);
        LinearLayout actions = row(content);
        weightedButton(actions, "+ 添加问题", true, () -> questionEditor(set, null, null, null));
        weightedButton(actions, "导出题集", false, () -> exportSets(Collections.singletonList(set), safeFilename(set.name)));
        Button more = weightedButton(actions, "题集设置", false, () -> { });
        more.setOnClickListener(view -> setMenu(view, set));
        if (!set.questions.isEmpty()) {
            addButton(content, "开始自测  →", true, () -> startQuiz(set));
        }
        section("全部问题", "题面与答案均可编辑");
        if (set.questions.isEmpty()) {
            LinearLayout empty = card(Color.WHITE);
            text(empty, "这里还没有问题", 20, INK, true);
            TextView hint = text(empty, "添加一道题，让下一次复习更有方向。", 14, MUTED, false);
            topMargin(hint, 10);
        }
        for (int index = 0; index < Math.min(visibleCount, set.questions.size()); index++) {
            Question question = set.questions.get(index);
            LinearLayout item = card(Color.WHITE);
            text(item, String.format(Locale.ROOT, "问题 %02d", index + 1), 11, GREEN, true);
            TextView prompt = text(item, question.prompt, 17, INK, true);
            prompt.setMaxLines(4);
            prompt.setEllipsize(TextUtils.TruncateAt.END);
            topMargin(prompt, 10);
            TextView answer = text(item, "答案  " + question.answer, 14, MUTED, false);
            answer.setMaxLines(3);
            answer.setEllipsize(TextUtils.TruncateAt.END);
            topMargin(answer, 10);
            LinearLayout tools = row(item);
            weightedButton(tools, "编辑", false, () -> questionEditor(set, question, null, null));
            Button delete = weightedButton(tools, "删除", false, () -> confirm("删除这道问题？", "此操作无法撤销。",
                    () -> mutate("正在删除…", () -> repository.removeQuestion(set.id, question.id), this::render)));
            delete.setTextColor(RED);
        }
        moreItems(set.questions.size());
        footer("判题严格区分大小写、空格、标点与换行。");
    }

    private void showQuiz() {
        QuestionSet set = quiz.getSet();
        page(set.name, "专注于眼前这一题。", true);
        LinearLayout stats = card(PALE);
        text(stats, "本次准确率", 12, GREEN, true);
        TextView rate = text(stats, quiz.getAnswered() == 0 ? "—" : accuracy(), 44, GREEN, true);
        topMargin(rate, 6);
        text(stats, "答对 " + quiz.getCorrect() + "  /  已答 " + quiz.getAnswered()
                + "    ·    答错 " + (quiz.getAnswered() - quiz.getCorrect()), 13, MUTED, false);
        TextView progressLabel = text(content, "第 " + quiz.getRound() + " 轮   ·   " + quiz.getPosition()
                + " / " + set.questions.size(), 12, MUTED, true);
        topMargin(progressLabel, 10);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(set.questions.size());
        progress.setProgress(quiz.getPosition());
        progress.setProgressTintList(ColorStateList.valueOf(GREEN));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(5));
        progressParams.setMargins(0, dp(10), 0, dp(8));
        content.addView(progress, progressParams);
        LinearLayout questionCard = card(Color.WHITE);
        text(questionCard, "QUESTION  /  题面", 11, GREEN, true);
        TextView prompt = text(questionCard, quiz.current().prompt, 23, INK, true);
        prompt.setTextIsSelectable(true);
        topMargin(prompt, 16);
        TextView label = text(content, "你的答案", 13, INK, true);
        topMargin(label, 16);
        answerInput = input("输入完整答案", quiz.isSubmitted() ? quiz.getInput() : quizDraft, JsonCodec.MAX_TEXT, true);
        answerInput.setMinLines(3);
        answerInput.setMaxLines(10);
        content.addView(answerInput, marginParams(8));
        answerInput.setEnabled(!quiz.isSubmitted());
        TextView hint = text(content, "完全一致才算正确：包含大小写、空格、标点和换行。", 11, MUTED, false);
        topMargin(hint, 9);
        if (quiz.isSubmitted()) {
            LinearLayout feedback = card(quiz.isCorrect() ? PALE : Color.rgb(251, 236, 230));
            text(feedback, quiz.isCorrect() ? "✓  回答正确" : "未答对，再记一次。", 18,
                    quiz.isCorrect() ? GREEN : RED, true);
            if (!quiz.isCorrect()) {
                TextView answerLabel = text(feedback, "正确答案（原文）", 11, MUTED, true);
                topMargin(answerLabel, 12);
                TextView correct = text(feedback, quiz.current().answer, 17, INK, true);
                correct.setTextIsSelectable(true);
                topMargin(correct, 6);
                TextView difference = text(feedback, "空白可视化：\n标准：" + visibleWhitespace(quiz.current().answer)
                        + "\n输入：" + visibleWhitespace(quiz.getInput()), 12, MUTED, false);
                topMargin(difference, 12);
            }
            if (set.questions.size() == 1) {
                addButton(content, "完成自测", true, this::finishQuiz);
                footer("本题集只有一道题，作答后结束，避免同题相邻重复。");
            } else {
                addButton(content, quiz.getPosition() == set.questions.size() ? "继续下一轮  →" : "下一题  →", true, () -> {
                    quiz.next();
                    quizDraft = "";
                    answerInput = null;
                    persistQuiz(false);
                    render();
                });
            }
        } else {
            addButton(content, "提交答案", true, () -> {
                quizDraft = answerInput.getText().toString();
                quiz.submit(quizDraft);
                hideKeyboard();
                persistQuiz(false);
                render();
            });
        }
        addButton(content, "结束本次自测", false, this::finishQuiz);
        if (set.questions.size() > 1) {
            footer("每轮随机遍历全部问题；跨轮也不会连续出现同一道题。");
        }
    }

    private void startQuiz(QuestionSet set) {
        if (set.questions.isEmpty()) {
            toast("先添加一道问题吧");
            return;
        }
        quiz = new QuizSession(set, new Random());
        quizDraft = "";
        selectedId = set.id;
        screen = "quiz";
        persistQuiz(false);
        render();
    }

    private void finishQuiz() {
        hideKeyboard();
        String rate = quiz.getAnswered() == 0 ? "尚未作答" : accuracy();
        new AlertDialog.Builder(this).setTitle("本次自测")
                .setMessage("准确率  " + rate + "\n\n已答 " + quiz.getAnswered() + " 道，答对 "
                        + quiz.getCorrect() + " 道。\n结束后，本次统计将清零。")
                .setNegativeButton("继续答题", null)
                .setPositiveButton("结束自测", (dialog, which) -> {
                    selectedId = quiz.getSet().id;
                    quiz = null;
                    quizDraft = "";
                    answerInput = null;
                    preferences.edit().remove("session").apply();
                    screen = "detail";
                    visibleCount = PAGE_SIZE;
                    render();
                }).show();
    }

    private void openSet(String id) {
        selectedId = id;
        screen = "detail";
        visibleCount = PAGE_SIZE;
        render();
    }

    private void newSet() {
        nameEditor(null, null);
    }

    private void nameEditor(QuestionSet set, String restored) {
        editorKind = set == null ? "create" : "rename";
        editorSetId = set == null ? null : set.id;
        editorQuestionId = null;
        LinearLayout body = dialogBody();
        text(body, "题集名称", 13, MUTED, true);
        editorFirst = input("例如：英语单词 / 计算机基础", restored != null ? restored : set == null ? "" : set.name,
                JsonCodec.MAX_NAME, false);
        body.addView(editorFirst, marginParams(10));
        editorSecond = null;
        editor = new AlertDialog.Builder(this).setTitle(set == null ? "新建题集" : "重命名题集")
                .setView(body).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        editor.setOnDismissListener(dialog -> clearEditor());
        editor.setOnShowListener(dialog -> editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = editorFirst.getText().toString().trim();
            try {
                JsonCodec.validateText(name, "题集名称", JsonCodec.MAX_NAME);
            } catch (DataException error) {
                editorFirst.setError(error.getMessage());
                return;
            }
            mutate("正在保存…", () -> {
                if (set == null) {
                    repository.create(name);
                } else {
                    repository.rename(set.id, name);
                }
            }, () -> {
                editor.dismiss();
                render();
            });
        }));
        editor.show();
        editor.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void questionEditor(QuestionSet set, Question question, String restoredPrompt, String restoredAnswer) {
        editorKind = "question";
        editorSetId = set.id;
        editorQuestionId = question == null ? null : question.id;
        LinearLayout body = dialogBody();
        text(body, "题面", 13, INK, true);
        editorFirst = input("请输入问题", restoredPrompt != null ? restoredPrompt : question == null ? "" : question.prompt,
                JsonCodec.MAX_TEXT, true);
        editorFirst.setMinLines(3);
        editorFirst.setMaxLines(7);
        body.addView(editorFirst, marginParams(8));
        TextView answerLabel = text(body, "标准答案", 13, INK, true);
        topMargin(answerLabel, 18);
        editorSecond = input("请输入标准答案", restoredAnswer != null ? restoredAnswer : question == null ? "" : question.answer,
                JsonCodec.MAX_TEXT, true);
        editorSecond.setMinLines(3);
        editorSecond.setMaxLines(7);
        body.addView(editorSecond, marginParams(8));
        TextView hint = text(body, "原样保存，不会删除首尾空格。自测时逐字比较。", 11, MUTED, false);
        topMargin(hint, 10);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        editor = new AlertDialog.Builder(this).setTitle(question == null ? "添加问题" : "编辑问题")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        editor.setOnDismissListener(dialog -> clearEditor());
        editor.setOnShowListener(dialog -> editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String prompt = editorFirst.getText().toString();
            String answer = editorSecond.getText().toString();
            try {
                JsonCodec.validateText(prompt, "题面", JsonCodec.MAX_TEXT);
                JsonCodec.validateText(answer, "答案", JsonCodec.MAX_TEXT);
            } catch (DataException error) {
                toast(error.getMessage());
                return;
            }
            mutate("正在保存…", () -> repository.putQuestion(set.id, question == null ? null : question.id, prompt, answer), () -> {
                editor.dismiss();
                render();
            });
        }));
        editor.show();
        editor.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void setMenu(View anchor, QuestionSet set) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("重命名").setOnMenuItemClickListener(item -> {
            nameEditor(set, null);
            return true;
        });
        menu.getMenu().add("导出 JSON").setOnMenuItemClickListener(item -> {
            exportSets(Collections.singletonList(set), safeFilename(set.name));
            return true;
        });
        menu.getMenu().add("删除题集").setOnMenuItemClickListener(item -> {
            confirm("删除「" + set.name + "」？", "其中的 " + set.questions.size() + " 道问题也会被删除，此操作无法撤销。",
                    () -> mutate("正在删除…", () -> repository.remove(set.id), () -> {
                        screen = "home";
                        render();
                    }));
            return true;
        });
        menu.show();
    }

    private void pickImport() {
        if (busy || filePickerOpen) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        launchPicker(intent, IMPORT_FILE);
    }

    private void exportSets(List<QuestionSet> sets, String filename) {
        if (busy || filePickerOpen || sets.isEmpty()) {
            return;
        }
        List<QuestionSet> snapshot = new ArrayList<>(sets);
        mutate("正在准备导出…", () -> {
            byte[] payload = JsonCodec.encode(snapshot).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(exportCache())) {
                output.write(payload);
            }
        }, () -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, filename);
            launchPicker(intent, EXPORT_FILE);
        });
    }

    private void launchPicker(Intent intent, int request) {
        try {
            filePickerOpen = true;
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException error) {
            filePickerOpen = false;
            toast("此设备没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        filePickerOpen = false;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == IMPORT_FILE) {
            final List<List<QuestionSet>> result = new ArrayList<>();
            mutate("正在读取 JSON…", () -> {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) {
                        throw new IOException("无法打开文件");
                    }
                    result.add(JsonCodec.decode(QuestionRepository.readUtf8(input), true));
                }
            }, () -> confirmImport(result.get(0)));
        } else if (requestCode == EXPORT_FILE) {
            mutate("正在导出…", () -> {
                try (InputStream input = new FileInputStream(exportCache());
                     OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) {
                        throw new IOException("无法写入目标文件");
                    }
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                    output.flush();
                }
            }, () -> toast("JSON 已导出"));
        }
    }

    private void confirmImport(List<QuestionSet> imported) {
        if (imported.isEmpty()) {
            toast("文件中没有可导入的题集");
            return;
        }
        int count = 0;
        for (QuestionSet set : imported) {
            count += set.questions.size();
        }
        new AlertDialog.Builder(this).setTitle("导入题集")
                .setMessage("找到 " + imported.size() + " 个题集、" + count + " 道问题。\n\n将作为新题集追加，不会覆盖已有数据。相同名字的题集会分别保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认导入", (dialog, which) -> mutate("正在保存题集…", () -> repository.importSets(imported), () -> {
                    screen = "home";
                    visibleCount = PAGE_SIZE;
                    render();
                    toast("导入成功");
                })).show();
    }

    private void mutate(String message, Work task, Runnable success) {
        if (busy) {
            return;
        }
        busy = true;
        ProgressDialog progress = new ProgressDialog(this);
        activeProgress = progress;
        progress.setMessage(message);
        progress.setCancelable(false);
        progress.show();
        WORKER.execute(() -> {
            String failure = null;
            try {
                task.run();
            } catch (Exception error) {
                failure = error instanceof DataException ? error.getMessage() : "操作失败，请检查文件权限和可用存储空间";
            }
            String finalFailure = failure;
            runOnUiThread(() -> {
                busy = false;
                if (!isDestroyed()) {
                    progress.dismiss();
                    activeProgress = null;
                    if (finalFailure == null) {
                        success.run();
                    } else {
                        new AlertDialog.Builder(this).setTitle("未能完成操作").setMessage(finalFailure)
                                .setPositiveButton("知道了", null).show();
                    }
                }
            });
        });
    }

    private void persistQuiz(boolean synchronous) {
        if (quiz == null) {
            return;
        }
        if (answerInput != null && !quiz.isSubmitted()) {
            quizDraft = answerInput.getText().toString();
        }
        try {
            QuizSession.State state = quiz.snapshot();
            JSONObject object = new JSONObject().put("setId", quiz.getSet().id)
                    .put("order", new JSONArray(state.order)).put("index", state.index)
                    .put("round", state.round).put("answered", state.answered).put("correct", state.correct)
                    .put("submitted", state.submitted).put("input", state.input).put("draft", quizDraft);
            SharedPreferences.Editor change = preferences.edit().putString("session", object.toString());
            if (synchronous) {
                if (!change.commit()) {
                    toast("自测进度保存失败；题库数据不受影响");
                }
            } else {
                change.apply();
            }
        } catch (Exception error) {
            toast("自测进度未保存；题库数据不受影响");
        }
    }

    private void restoreQuiz() {
        String saved = preferences.getString("session", null);
        if (saved == null) {
            return;
        }
        try {
            JSONObject object = new JSONObject(saved);
            QuestionSet set = repository.find(object.getString("setId"));
            if (set == null || set.questions.isEmpty()) {
                throw new IllegalArgumentException();
            }
            JSONArray identifiers = object.getJSONArray("order");
            List<String> order = new ArrayList<>();
            for (int index = 0; index < identifiers.length(); index++) {
                order.add(identifiers.getString(index));
            }
            quiz = QuizSession.restore(set, new Random(), new QuizSession.State(order, object.getInt("index"),
                    object.getInt("round"), object.getInt("answered"), object.getInt("correct"),
                    object.getBoolean("submitted"), object.getString("input")));
            quizDraft = object.optString("draft", "");
            selectedId = set.id;
            screen = "quiz";
        } catch (Exception error) {
            preferences.edit().remove("session").apply();
            quiz = null;
            screen = "home";
        }
    }

    @Override
    protected void onPause() {
        if (ready) {
            persistQuiz(true);
        }
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        persistQuiz(true);
        out.putString("screen", screen);
        out.putString("selectedId", selectedId);
        out.putInt("visibleCount", visibleCount);
        out.putBoolean("filePickerOpen", filePickerOpen);
        if (!busy && editor != null && editor.isShowing()) {
            out.putString("editorKind", editorKind);
            out.putString("editorSetId", editorSetId);
            out.putString("editorQuestionId", editorQuestionId);
            out.putString("editorFirst", editorFirst.getText().toString());
            out.putString("editorSecond", editorSecond == null ? null : editorSecond.getText().toString());
        }
    }

    private void restoreEditor() {
        if (initialState == null) {
            return;
        }
        Bundle state = initialState;
        initialState = null;
        String kind = state.getString("editorKind");
        QuestionSet set = repository.find(state.getString("editorSetId"));
        if ("create".equals(kind)) {
            nameEditor(null, state.getString("editorFirst"));
        } else if ("rename".equals(kind) && set != null) {
            nameEditor(set, state.getString("editorFirst"));
        } else if ("question".equals(kind) && set != null) {
            Question question = null;
            for (Question candidate : set.questions) {
                if (candidate.id.equals(state.getString("editorQuestionId"))) {
                    question = candidate;
                }
            }
            questionEditor(set, question, state.getString("editorFirst"), state.getString("editorSecond"));
        }
    }

    @Override
    public void onBackPressed() {
        if (busy || !ready) {
            return;
        }
        if ("quiz".equals(screen) && quiz != null) {
            finishQuiz();
        } else if ("detail".equals(screen)) {
            screen = "home";
            visibleCount = PAGE_SIZE;
            render();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (activeProgress != null) {
            activeProgress.dismiss();
        }
        if (editor != null) {
            editor.dismiss();
        }
        super.onDestroy();
    }

    private void page(String title, String subtitle, boolean back) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setFitsSystemWindows(true);
        root.setFocusableInTouchMode(true);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(16), dp(22), dp(32));
        content.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
        LinearLayout top = row(content);
        if (back) {
            smallButton(top, "‹ 返回", this::onBackPressed);
        } else {
            TextView mark = text(top, "问", 17, Color.WHITE, true);
            mark.setGravity(Gravity.CENTER);
            mark.setBackground(shape(GREEN, 12));
            mark.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
            TextView brand = text(top, "  简问", 17, INK, true);
            brand.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
            brand.setGravity(Gravity.CENTER_VERTICAL);
        }
        TextView local = text(top, "离线 · 自动保存", 11, MUTED, false);
        local.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        local.setLayoutParams(new LinearLayout.LayoutParams(0, dp(42), 1));
        TextView heading = text(content, title, 29, INK, true);
        heading.setMaxLines(3);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        topMargin(heading, 28);
        TextView subheading = text(content, subtitle, 13, MUTED, false);
        topMargin(subheading, 8);
        LinearLayout.LayoutParams subParams = (LinearLayout.LayoutParams) subheading.getLayoutParams();
        subParams.bottomMargin = dp(18);
    }

    private LinearLayout card(int color) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(16));
        card.setBackground(shape(color, 20));
        content.addView(card, marginParams(12));
        return card;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, marginParams(8));
        return row;
    }

    private TextView text(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(4), 1);
        if (bold) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{Color.rgb(163, 174, 166), primary ? Color.WHITE : GREEN}));
        button.setBackgroundTintList(null);
        button.setBackground(shape(primary ? GREEN : Color.rgb(234, 240, 233), 12));
        button.setStateListAnimator(null);
        button.setPadding(dp(10), dp(12), dp(10), dp(12));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private Button weightedButton(LinearLayout parent, String label, boolean primary, Runnable action) {
        Button button = button(label, primary, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (parent.getChildCount() > 0) {
            params.leftMargin = dp(8);
        }
        params.topMargin = dp(8);
        parent.addView(button, params);
        return button;
    }

    private Button smallButton(LinearLayout parent, String label, Runnable action) {
        Button button = button(label, false, action);
        button.setBackgroundColor(Color.TRANSPARENT);
        parent.addView(button, new LinearLayout.LayoutParams(-2, dp(48)));
        return button;
    }

    private void addButton(LinearLayout parent, String label, boolean primary, Runnable action) {
        parent.addView(button(label, primary, action), marginParams(14));
    }

    private EditText input(String hint, String value, int maxLength, boolean multiline) {
        EditText input = new EditText(this);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setTextSize(16);
        input.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable background = shape(Color.WHITE, 12);
        background.setStroke(dp(1), Color.rgb(210, 222, 212));
        input.setBackground(background);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        input.setSingleLine(!multiline);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        input.setHint(hint);
        input.setText(value);
        input.setSelectAllOnFocus(false);
        return input;
    }

    private LinearLayout dialogBody() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(12), dp(24), dp(20));
        return body;
    }

    private void clearEditor() {
        editor = null;
        editorFirst = null;
        editorSecond = null;
        editorKind = null;
        editorSetId = null;
        editorQuestionId = null;
    }

    private void section(String title, String description) {
        TextView heading = text(content, title, 17, INK, true);
        topMargin(heading, 28);
        TextView note = text(content, description, 11, MUTED, false);
        topMargin(note, 6);
    }

    private void moreItems(int total) {
        if (visibleCount < total) {
            addButton(content, "加载更多（" + Math.min(visibleCount, total) + " / " + total + "）", false, () -> {
                visibleCount += PAGE_SIZE;
                render();
            });
        }
    }

    private void footer(String note) {
        TextView footer = text(content, note, 11, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        topMargin(footer, 24);
    }

    private void confirm(String title, String message, Runnable action) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> action.run()).show();
    }

    private String accuracy() {
        return String.format(Locale.SIMPLIFIED_CHINESE, "%.1f%%", quiz.getAccuracy());
    }

    private static String visibleWhitespace(String text) {
        if (text.isEmpty()) {
            return "（空）";
        }
        return text.replace(" ", "·").replace("\t", "⇥").replace("\r", "␍").replace("\n", "↵\n");
    }

    private static String safeFilename(String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (safe.length() > 60) {
            safe = safe.substring(0, 60);
        }
        return safe + ".json";
    }

    private File exportCache() {
        return new File(getFilesDir(), "pending-export.json");
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            manager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private LinearLayout.LayoutParams marginParams(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        return params;
    }

    private void topMargin(View view, int margin) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.topMargin = dp(margin);
        view.setLayoutParams(params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private interface Work {
        void run() throws Exception;
    }
}
