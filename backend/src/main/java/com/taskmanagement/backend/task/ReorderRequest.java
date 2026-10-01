package com.taskmanagement.backend.task;

import jakarta.validation.constraints.NotNull;

import java.util.List;

// 並び替え用：どの列（columnId）を、どの ID の順番に並べるか
// どちらも省略できない
public record ReorderRequest(
        @NotNull(message = "並べる列（columnId）を指定してください") Long columnId,
        @NotNull(message = "並べる順番（orderedIds）を指定してください")
        List<@NotNull(message = "並べる順番（orderedIds）に空の ID を含めないでください") Long> orderedIds) {
}
