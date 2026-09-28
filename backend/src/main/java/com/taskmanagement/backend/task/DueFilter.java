package com.taskmanagement.backend.task;

// 期限での絞り込みの種類（GET /api/tasks?due=overdue のように使う）。この3つ以外の値は作れない
// Java の中では OVERDUE のように大文字で書き、URL では "overdue" のように小文字で表す（TaskPriority と同じ作り）
public enum DueFilter {
    OVERDUE("overdue"), // 期限切れ：期限日が今日より前
    WEEK("week"),       // 7日以内：期限日が今日から6日後まで（今日を含む）
    NONE("none");       // 期限なし：期限日が入っていない

    // URL で使う小文字の値
    private final String value;

    DueFilter(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    // URL の "overdue" などから DueFilter を作るときに使う
    // 決まった値以外なら例外を投げる（Spring が 400 にして返す）
    public static DueFilter fromValue(String value) {
        for (DueFilter filter : values()) {
            if (filter.value.equals(value)) {
                return filter;
            }
        }
        throw new IllegalArgumentException("due must be overdue, week or none: " + value);
    }
}
