package com.taskmanagement.backend.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

// columnId と priority は省略してもよい（省略したときの値は TaskService で決める）
// columnId は列の番号。その番号の列が本当にあるかは TaskService が確かめる（なければ 400）
// priority は enum なので、決まった値以外は JSON を読む時点で断られる（400）
// text は 255 文字まで（DB の text 列が varchar(255) のため。超えると DB への保存で失敗し、500 エラーになる）
// version は、画面が見ていた版（docs/同時編集の設計書.md の 4.1 節）。PUT（編集）だけで使い、今の版とちがえば 409
// 省略（null）なら確かめない（今までどおり後勝ち）。POST（追加）では書いても無視する（新しいタスクは必ず 0）
// 数字でなければ、JSON を読む時点で断られる（400）
public record TaskRequest(
        @NotBlank(message = "タスク名を入力してください")
        @Size(max = 255, message = "タスク名は255文字までにしてください") String text,
        Long columnId,
        TaskPriority priority,
        LocalDate dueDate,
        Long version) {
}
