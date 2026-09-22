package com.taskmanagement.backend.task;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record TaskRequest(
        @NotBlank(message = "text must not be blank") String text,
        String status,
        String priority,
        LocalDate dueDate) {
}
