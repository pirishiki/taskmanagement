package com.taskmanagement.backend.task;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

// 並び替え用：どの列（status）を、どの ID の順番に並べるか
// どちらも省略できない。status は決められた値だけ（ほかの値が DB に入ると、どの列にも表示されなくなるため）
public record ReorderRequest(
        @NotNull(message = "status must not be null")
        @Pattern(regexp = "todo|doing|done", message = "status must be todo, doing or done") String status,
        @NotNull(message = "orderedIds must not be null") List<@NotNull Long> orderedIds) {
}
