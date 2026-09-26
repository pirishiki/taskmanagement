package com.taskmanagement.backend.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

// status と priority は省略してもよい（省略したときの値は TaskService で決める）
// status と priority は enum なので、決まった値以外は JSON を読む時点で断られる（400）
// text は 255 文字まで（DB の text 列が varchar(255) のため。超えると DB への保存で失敗し、500 エラーになる）
public record TaskRequest(
        @NotBlank(message = "text must not be blank")
        @Size(max = 255, message = "text must be at most 255 characters") String text,
        TaskStatus status,
        TaskPriority priority,
        LocalDate dueDate) {
}
