package com.ggn.simplequestion;

public final class Question {
    public final String id;
    public final String prompt;
    public final String answer;

    public Question(String id, String prompt, String answer) {
        this.id = id;
        this.prompt = prompt;
        this.answer = answer;
    }
}
