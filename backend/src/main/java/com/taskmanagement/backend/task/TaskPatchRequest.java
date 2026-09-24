package com.taskmanagement.backend.task;

import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

// PATCH 用：変えたい項目だけを送る。送らなかった項目は null になり、今の値のまま残る
// そのため dueDate を消すこと（null にすること）は PATCH ではできない。消すときは PUT を使う
public record TaskPatchRequest(
        @Pattern(regexp = "(?s).*\\S.*", message = "text must not be blank") String text,
        @Pattern(regexp = "todo|doing|done", message = "status must be todo, doing or done") String status,
        @Pattern(regexp = "high|medium|low", message = "priority must be high, medium or low") String priority,
        LocalDate dueDate) {
}
