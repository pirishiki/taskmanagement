package com.taskmanagement.backend.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

// 書き出し・読み込みのファイル（TaskFile）に入れる、タスク1件の形
// id は入れない。読み込むときは、DB が新しい id を付ける（今ある id とぶつからないようにするため）
// 元どおりに戻せるように、列（status）と並び順（sortOrder）も入れる
// text のチェックは、画面から登録するとき（TaskRequest）と同じ
public record TaskFileItem(
        @NotBlank(message = "タスク名を入力してください")
        @Size(max = 255, message = "タスク名は255文字までにしてください") String text,
        @NotNull(message = "列（status）を指定してください") TaskStatus status,
        @NotNull(message = "優先度（priority）を指定してください") TaskPriority priority,
        LocalDate dueDate,
        @NotNull(message = "並び順（sortOrder）を指定してください") Double sortOrder) {

    // エンティティ Task から、ファイル用の TaskFileItem を作る（書き出すときに使う）
    public static TaskFileItem from(Task task) {
        return new TaskFileItem(
                task.getText(),
                task.getStatus(),
                task.getPriority(),
                task.getDueDate(),
                task.getSortOrder());
    }
}
