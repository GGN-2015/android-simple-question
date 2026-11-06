package com.ggn.simplequestion;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class SmokeInstrumentation extends Instrumentation {
    private final List<String> passed = new ArrayList<>();

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            testRepository();
            testInterface();
            result.putString("stream", "\nPASS: " + String.join("\nPASS: ", passed) + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            result.putString("stream", "\nFAIL: " + trace + "\nPassed: " + passed);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void testRepository() throws Exception {
        File directory = new File(getTargetContext().getCacheDir(), "repository-tests-" + System.nanoTime());
        check(directory.mkdirs(), "create isolated storage");
        Context isolated = new ContextWrapper(getTargetContext()) {
            @Override
            public File getFilesDir() {
                return directory;
            }
        };
        QuestionRepository repository = new QuestionRepository(isolated);
        repository.load();
        repository.create("Test");
        String id = repository.getSets().get(0).id;
        repository.putQuestion(id, null, "题面\n原文", " Answer \n");
        repository.rename(id, "Renamed");
        QuestionRepository reloaded = new QuestionRepository(isolated);
        reloaded.load();
        check(reloaded.getSets().get(0).name.equals("Renamed"), "renamed set persists");
        check(reloaded.getSets().get(0).questions.get(0).answer.equals(" Answer \n"), "exact answer persists");
        passed.add("题集、问题与原始空白字符持久化");
        File incomplete = new File(directory, "question-sets.json.new");
        try (FileOutputStream output = new FileOutputStream(incomplete)) {
            output.write("{broken".getBytes(StandardCharsets.UTF_8));
        }
        reloaded.load();
        check(reloaded.getSets().size() == 1, "interrupted write preserves original");
        passed.add("中断写入后保留完整题库");
        reloaded.importSets(JsonCodec.decode(JsonCodec.encode(reloaded.getSets()), true));
        check(reloaded.getSets().size() == 2, "import appends");
        check(!reloaded.getSets().get(0).id.equals(reloaded.getSets().get(1).id), "fresh import identifiers");
        passed.add("JSON 导出再导入，追加而不覆盖");
        String questionId = reloaded.find(id).questions.get(0).id;
        reloaded.putQuestion(id, questionId, "Updated", "Exact");
        check(reloaded.find(id).questions.size() == 1, "edit does not duplicate");
        reloaded.removeQuestion(id, questionId);
        check(reloaded.find(id).questions.isEmpty(), "delete question");
        reloaded.remove(id);
        reloaded.load();
        check(reloaded.find(id) == null, "deleted set remains deleted");
        passed.add("修改、删除问题及删除题集");
        File base = new File(directory, "question-sets.json");
        try (FileOutputStream output = new FileOutputStream(base)) {
            output.write("broken data".getBytes(StandardCharsets.UTF_8));
        }
        boolean rejected = false;
        try {
            reloaded.load();
        } catch (DataException expected) {
            rejected = true;
        }
        check(rejected, "invalid local data rejected");
        rejected = false;
        try {
            reloaded.create("Do not overwrite");
        } catch (DataException expected) {
            rejected = true;
        }
        check(rejected, "corrupt storage never overwritten");
        passed.add("损坏本地文件时禁止覆盖原数据");
    }

    private void testInterface() throws Exception {
        Intent intent = new Intent(getTargetContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = startActivitySync(intent);
        waitText("我的题集");
        click("+ 新建题集");
        setInput(0, "QA 临时题集");
        click("保存");
        waitText("QA 临时题集");
        passed.add("界面新建题集");
        click("管理问题");
        click("+ 添加问题");
        setInput(0, "Type Hello exactly");
        setInput(1, "Hello");
        click("保存");
        waitText("Type Hello exactly");
        click("+ 添加问题");
        setInput(0, "Type World exactly");
        setInput(1, "World");
        click("保存");
        waitText("Type World exactly");
        passed.add("界面添加多道问题");
        screenshot("detail.png");
        click("开始自测  →");
        waitText("本次准确率");
        String firstPrompt = exists("Type Hello exactly") ? "Type Hello exactly" : "Type World exactly";
        String firstAnswer = firstPrompt.contains("Hello") ? "Hello" : "World";
        setInput(0, firstAnswer.toLowerCase());
        click("提交答案");
        waitText("0.0%");
        check(exists("未答对，再记一次。"), "case mismatch is incorrect");
        passed.add("大小写不同判错，准确率为 0%");
        click("下一题  →");
        String nextPrompt = firstPrompt.contains("Hello") ? "Type World exactly" : "Type Hello exactly";
        waitText(nextPrompt);
        setInput(0, nextPrompt.contains("Hello") ? "Hello" : "World");
        click("提交答案");
        waitText("50.0%");
        check(exists("✓  回答正确"), "exact answer is correct");
        screenshot("quiz.png");
        passed.add("完整匹配判对，准确率为 50%，相邻题目不同");
        click("继续下一轮  →");
        waitText(firstPrompt);
        check(exists("第 2 轮   ·   1 / 2"), "next round starts without repeating previous question");
        setInput(0, "Draft before rotation");
        runOnMainSync(activity::recreate);
        SystemClock.sleep(1200);
        waitText("Draft before rotation");
        waitText("50.0%");
        passed.add("跨轮无相邻重复，重建界面后恢复草稿和准确率");
    }

    private void screenshot(String name) throws Exception {
        Bitmap screenshot = getUiAutomation().takeScreenshot();
        File directory = new File(getTargetContext().getExternalFilesDir(null), "qa");
        check(directory.exists() || directory.mkdirs(), "create screenshot directory");
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output), "save screenshot");
        }
    }

    private void setInput(int index, String value) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < deadline) {
            List<AccessibilityNodeInfo> fields = new ArrayList<>();
            collectInputs(getUiAutomation().getRootInActiveWindow(), fields);
            if (fields.size() > index) {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                if (fields.get(index).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                    SystemClock.sleep(150);
                    return;
                }
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Input field not found: " + index);
    }

    private void collectInputs(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> fields) {
        if (node == null) {
            return;
        }
        if ("android.widget.EditText".contentEquals(node.getClassName())) {
            fields.add(node);
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            collectInputs(node.getChild(index), fields);
        }
    }

    private void click(String text) {
        AccessibilityNodeInfo node = waitText(text);
        while (node != null && !node.isClickable()) {
            node = node.getParent();
        }
        check(node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "click " + text);
        SystemClock.sleep(250);
    }

    private boolean exists(String text) {
        return find(getUiAutomation().getRootInActiveWindow(), text) != null;
    }

    private AccessibilityNodeInfo waitText(String text) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo node = find(getUiAutomation().getRootInActiveWindow(), text);
            if (node != null) {
                return node;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Text not found: " + text);
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text) {
        if (node == null) {
            return null;
        }
        if (text.contentEquals(node.getText() == null ? "" : node.getText())) {
            return node;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = find(node.getChild(index), text);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
