package com.taskmanagement.backend.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

// タスクの状態（どの列にあるか）。この3つ以外の値は作れない
// Java の中では TODO のように大文字で書き、JSON と DB では今までどおり "todo" のように小文字で表す
public enum TaskStatus {
    TODO("todo"),
    DOING("doing"),
    DONE("done");

    // JSON と DB で使う小文字の値
    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    // @JsonValue：JSON にするときは、この値（"todo" など）を使う
    @JsonValue
    public String getValue() {
        return value;
    }

    // @JsonCreator：JSON の "todo" などから TaskStatus を作るときに使う
    // 決まった値以外なら例外を投げる（Spring が 400 にして返す）
    @JsonCreator
    public static TaskStatus fromValue(String value) {
        for (TaskStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("status must be todo, doing or done: " + value);
    }
}
