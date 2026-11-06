package com.ggn.simplequestion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class QuizSession {
    public static final class State {
        public final List<String> order;
        public final int index;
        public final int round;
        public final int answered;
        public final int correct;
        public final boolean submitted;
        public final String input;

        public State(List<String> order, int index, int round, int answered, int correct,
                     boolean submitted, String input) {
            this.order = Collections.unmodifiableList(new ArrayList<>(order));
            this.index = index;
            this.round = round;
            this.answered = answered;
            this.correct = correct;
            this.submitted = submitted;
            this.input = input;
        }
    }

    private final QuestionSet set;
    private final Random random;
    private final List<Question> order = new ArrayList<>();
    private int index;
    private int round = 1;
    private int answered;
    private int correct;
    private boolean submitted;
    private String input = "";

    public QuizSession(QuestionSet set, Random random) {
        if (set.questions.isEmpty()) {
            throw new IllegalArgumentException("题集不能为空");
        }
        this.set = set;
        this.random = random;
        reshuffle(null);
    }

    public static QuizSession restore(QuestionSet set, Random random, State state) {
        if (state.order.size() != set.questions.size() || state.index < 0
                || state.index >= state.order.size() || state.round < 1 || state.answered < 0
                || state.correct < 0 || state.correct > state.answered || state.input == null
                || (state.submitted && state.answered == 0)) {
            throw new IllegalArgumentException("无效的自测状态");
        }
        QuizSession session = new QuizSession(set, random);
        session.order.clear();
        Set<String> seen = new HashSet<>();
        for (String id : state.order) {
            Question found = null;
            for (Question question : set.questions) {
                if (question.id.equals(id)) {
                    found = question;
                    break;
                }
            }
            if (found == null || !seen.add(id)) {
                throw new IllegalArgumentException("题集已改变");
            }
            session.order.add(found);
        }
        session.index = state.index;
        session.round = state.round;
        session.answered = state.answered;
        session.correct = state.correct;
        session.submitted = state.submitted;
        session.input = state.input;
        return session;
    }

    public Question current() {
        return order.get(index);
    }

    public boolean submit(String value) {
        if (submitted) {
            throw new IllegalStateException("本题已提交");
        }
        input = value;
        submitted = true;
        answered++;
        boolean matches = current().answer.equals(value);
        if (matches) {
            correct++;
        }
        return matches;
    }

    public boolean next() {
        if (!submitted) {
            throw new IllegalStateException("请先提交答案");
        }
        if (order.size() == 1) {
            return false;
        }
        String previousId = current().id;
        index++;
        if (index >= order.size()) {
            round++;
            reshuffle(previousId);
        }
        submitted = false;
        input = "";
        return true;
    }

    public State snapshot() {
        List<String> identifiers = new ArrayList<>();
        for (Question question : order) {
            identifiers.add(question.id);
        }
        return new State(identifiers, index, round, answered, correct, submitted, input);
    }

    public QuestionSet getSet() { return set; }
    public int getPosition() { return index + 1; }
    public int getRound() { return round; }
    public int getAnswered() { return answered; }
    public int getCorrect() { return correct; }
    public double getAccuracy() { return answered == 0 ? 0 : correct * 100.0 / answered; }
    public boolean isSubmitted() { return submitted; }
    public String getInput() { return input; }
    public boolean isCorrect() { return submitted && current().answer.equals(input); }

    private void reshuffle(String previousId) {
        order.clear();
        order.addAll(set.questions);
        Collections.shuffle(order, random);
        if (order.size() > 1 && order.get(0).id.equals(previousId)) {
            Collections.swap(order, 0, 1 + random.nextInt(order.size() - 1));
        }
        index = 0;
    }
}
