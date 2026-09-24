package com.taskmanagement.backend.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

// status と priority は省略してもよい（省略したときの値は TaskService で決める）
public record TaskRequest(
        @NotBlank(message = "text must not be blank") String text,
        @Pattern(regexp = "todo|doing|done", message = "status must be todo, doing or done") String status,
        @Pattern(regexp = "high|medium|low", message = "priority must be high, medium or low") String priority,
        LocalDate dueDate) {
}
