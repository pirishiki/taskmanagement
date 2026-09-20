package com.taskmanagement.backend.task;

import java.time.LocalDate;

public record TaskRequest(String text, String status, String priority, LocalDate dueDate) {
}
