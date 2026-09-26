package com.taskmanagement.backend.task;

import jakarta.validation.constraints.NotNull;

import java.util.List;

// 並び替え用：どの列（status）を、どの ID の順番に並べるか
// どちらも省略できない。status は enum なので、決まった値以外は JSON を読む時点で断られる（400）
public record ReorderRequest(
        @NotNull(message = "status must not be null") TaskStatus status,
        @NotNull(message = "orderedIds must not be null") List<@NotNull Long> orderedIds) {
}
