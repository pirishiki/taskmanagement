package com.taskmanagement.backend.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

// 古い版（version 1、#36 の形）のファイルに入っている、タスク1件の形。読み込むときだけ使う
// 列は status（todo・doing・done のどれか）で書いてある。読み込むときに「やるべきこと／進行中／終わったこと」の列に入れる
// status が3つのどれかかは、TaskService が確かめる
public record TaskFileV1Item(
        @NotBlank(message = "タスク名を入力してください")
        @Size(max = 255, message = "タスク名は255文字までにしてください") String text,
        @NotBlank(message = "列（status）を指定してください") String status,
        @NotNull(message = "優先度（priority）を指定してください") TaskPriority priority,
        LocalDate dueDate,
        @NotNull(message = "並び順（sortOrder）を指定してください") Double sortOrder) {
}
