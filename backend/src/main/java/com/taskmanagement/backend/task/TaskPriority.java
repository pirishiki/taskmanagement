package com.taskmanagement.backend.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

// タスクの優先度。この3つ以外の値は作れない
// Java の中では HIGH のように大文字で書き、JSON と DB では今までどおり "high" のように小文字で表す（TaskStatus と同じ作り）
public enum TaskPriority {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low");

    // JSON と DB で使う小文字の値
    private final String value;

    TaskPriority(String value) {
        this.value = value;
    }

    // @JsonValue：JSON にするときは、この値（"high" など）を使う（Java → JSON）
    @JsonValue
    public String getValue() {
        return value;
    }

    // @JsonCreator：JSON の "high" などから TaskPriority を作るときに使う（JSON → Java）
    // 決まった値以外なら例外を投げる（Spring が 400 にして返す）
    @JsonCreator
    public static TaskPriority fromValue(String value) {
        for (TaskPriority priority : values()) {
            if (priority.value.equals(value)) {
                return priority;
            }
        }
        throw new IllegalArgumentException("priority must be high, medium or low: " + value);
    }
}
