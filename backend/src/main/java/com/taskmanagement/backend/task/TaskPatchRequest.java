package com.taskmanagement.backend.task;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

// PATCH 用：変えたい項目だけを送る。送らなかった項目は null になり、今の値のまま残る
// そのため dueDate を消すこと（null にすること）は PATCH ではできない。消すときは PUT を使う
// columnId は列の番号。その番号の列が本当にあるかは TaskService が確かめる（なければ 400）
// priority は enum なので、決まった値以外は JSON を読む時点で断られる（400）
// text は 255 文字まで（TaskRequest と同じ理由）
public record TaskPatchRequest(
        @Pattern(regexp = "(?s).*\\S.*", message = "タスク名を入力してください")
        @Size(max = 255, message = "タスク名は255文字までにしてください") String text,
        Long columnId,
        TaskPriority priority,
        LocalDate dueDate) {
}
