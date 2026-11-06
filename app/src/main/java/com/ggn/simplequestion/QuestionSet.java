package com.ggn.simplequestion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class QuestionSet {
    public final String id;
    public final String name;
    public final List<Question> questions;

    public QuestionSet(String id, String name, List<Question> questions) {
        this.id = id;
        this.name = name;
        this.questions = Collections.unmodifiableList(new ArrayList<>(questions));
    }
}
