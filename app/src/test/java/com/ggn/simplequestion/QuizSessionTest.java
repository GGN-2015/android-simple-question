package com.ggn.simplequestion;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

public final class QuizSessionTest {
    private QuestionSet set(int count) {
        List<Question> questions = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            questions.add(new Question("question-" + index, "题面 " + index, "Answer " + index));
        }
        return new QuestionSet("set", "测试题集", questions);
    }

    @Test
    public void eachRoundVisitsAllQuestionsAndNeverRepeatsAdjacentAcrossRounds() {
        for (int size = 2; size <= 25; size++) {
            for (int seed = 0; seed < 20; seed++) {
                QuizSession session = new QuizSession(set(size), new Random(seed));
                String previous = null;
                for (int round = 1; round <= 30; round++) {
                    Set<String> visited = new HashSet<>();
                    for (int position = 1; position <= size; position++) {
                        assertEquals(round, session.getRound());
                        assertEquals(position, session.getPosition());
                        assertNotEquals(previous, session.current().id);
                        assertTrue(visited.add(session.current().id));
                        previous = session.current().id;
                        session.submit(session.current().answer);
                        assertTrue(session.next());
                    }
                    assertEquals(size, visited.size());
                }
            }
        }
    }

    @Test
    public void differentSeedsProduceDifferentOrders() {
        Set<List<String>> orders = new HashSet<>();
        for (int seed = 0; seed < 20; seed++) {
            orders.add(new QuizSession(set(8), new Random(seed)).snapshot().order);
        }
        assertTrue(orders.size() > 10);
    }

    @Test
    public void exactMatchingIncludesCaseWhitespacePunctuationAndUnicode() {
        String expected = " Answer\n中文！ ";
        QuestionSet set = new QuestionSet("set", "exact", Collections.singletonList(new Question("q", "题面", expected)));
        for (String wrong : Arrays.asList(expected.trim(), expected.toLowerCase(), expected + " ",
                expected.replace("\n", "\r\n"), expected.replace("！", "!"), "")) {
            assertFalse(new QuizSession(set, new Random(0)).submit(wrong));
        }
        assertTrue(new QuizSession(set, new Random(0)).submit(expected));
    }

    @Test
    public void accuracyUsesOnlySubmittedAnswers() {
        QuizSession session = new QuizSession(set(4), new Random(42));
        assertEquals(0.0, session.getAccuracy(), 0.001);
        session.submit(session.current().answer);
        session.next();
        session.submit("wrong");
        session.next();
        session.submit(session.current().answer);
        assertEquals(3, session.getAnswered());
        assertEquals(2, session.getCorrect());
        assertEquals(200.0 / 3, session.getAccuracy(), 0.001);
    }

    @Test(expected = IllegalStateException.class)
    public void cannotCountTheSameSubmissionTwice() {
        QuizSession session = new QuizSession(set(3), new Random());
        session.submit("wrong");
        session.submit("wrong again");
    }

    @Test(expected = IllegalStateException.class)
    public void cannotSkipUnansweredQuestion() {
        new QuizSession(set(3), new Random()).next();
    }

    @Test
    public void singleQuestionEndsWithoutAdjacentRepeat() {
        QuizSession session = new QuizSession(set(1), new Random());
        session.submit(session.current().answer);
        assertFalse(session.next());
        assertEquals(1, session.getAnswered());
        assertTrue(session.isSubmitted());
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptySetCannotStart() {
        new QuizSession(set(0), new Random());
    }

    @Test
    public void restoreKeepsCurrentQuestionOrderStatisticsAndSubmission() {
        QuestionSet set = set(5);
        QuizSession original = new QuizSession(set, new Random(3));
        original.submit("wrong");
        original.next();
        original.submit(original.current().answer);
        QuizSession restored = QuizSession.restore(set, new Random(4), original.snapshot());
        assertEquals(original.current().id, restored.current().id);
        assertEquals(original.snapshot().order, restored.snapshot().order);
        assertEquals(2, restored.getAnswered());
        assertEquals(1, restored.getCorrect());
        assertTrue(restored.isSubmitted());
        assertTrue(restored.isCorrect());
        assertEquals(original.getInput(), restored.getInput());
        restored.next();
        assertFalse(restored.isSubmitted());
    }

    @Test(expected = IllegalArgumentException.class)
    public void restoreRejectsDuplicateOrder() {
        QuizSession.restore(set(2), new Random(), new QuizSession.State(
                Arrays.asList("question-0", "question-0"), 0, 1, 0, 0, false, ""));
    }

    @Test(expected = IllegalArgumentException.class)
    public void restoreRejectsChangedQuestionSet() {
        QuizSession.restore(set(2), new Random(), new QuizSession.State(
                Arrays.asList("question-0", "deleted"), 0, 1, 0, 0, false, ""));
    }

    @Test
    public void questionSetCopiesAndFreezesItsInputList() {
        List<Question> source = new ArrayList<>(set(3).questions);
        QuestionSet copy = new QuestionSet("copy", "copy", source);
        source.clear();
        assertEquals(3, copy.questions.size());
        assertThrows(UnsupportedOperationException.class, () -> copy.questions.clear());
    }
}
