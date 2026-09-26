package com.taskmanagement.backend.task;

import java.time.LocalDate;

// API の返事専用の型（DTO）。画面に見せる項目だけを持つ
// DB の形（エンティティ Task）をそのまま返さないのは、DB の形を変えても画面への返事の形が変わらないようにするため
// （たとえば、DB に作成日時や内部用の項目を足しても、ここに足さなければ画面には出ない）
public record TaskResponse(
        Long id,
        String text,
        TaskStatus status,
        TaskPriority priority,
        LocalDate dueDate,
        Integer sortOrder) {

    // エンティティ Task から、返事用の TaskResponse を作る
    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getText(),
                task.getStatus(),
                task.getPriority(),
                task.getDueDate(),
                task.getSortOrder());
    }
}
