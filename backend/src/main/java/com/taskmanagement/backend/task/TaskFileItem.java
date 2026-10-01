package com.taskmanagement.backend.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

// 書き出し・読み込みのファイル（TaskFile、version 2）に入れる、タスク1件の形
// どの列にいるか・何番目かは、入っている列（TaskFileColumn）と、その tasks の中の順番で決まるので、ここには書かない
// id も入れない。読み込むときは、DB が新しい id を付ける（今ある id とぶつからないようにするため）
// text のチェックは、画面から登録するとき（TaskRequest）と同じ
public record TaskFileItem(
        @NotBlank(message = "タスク名を入力してください")
        @Size(max = 255, message = "タスク名は255文字までにしてください") String text,
        @NotNull(message = "優先度（priority）を指定してください") TaskPriority priority,
        LocalDate dueDate) {

    // エンティティ Task から、ファイル用の TaskFileItem を作る（書き出すときに使う）
    public static TaskFileItem from(Task task) {
        return new TaskFileItem(task.getText(), task.getPriority(), task.getDueDate());
    }
}
