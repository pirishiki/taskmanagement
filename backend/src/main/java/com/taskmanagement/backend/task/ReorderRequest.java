package com.taskmanagement.backend.task;

import jakarta.validation.constraints.NotNull;

import java.util.List;

// 並び替え用：どの列（status）を、どの ID の順番に並べるか
// どちらも省略できない。status は enum なので、決まった値以外は JSON を読む時点で断られる（400）
public record ReorderRequest(
        @NotNull(message = "並べる列（status）を指定してください") TaskStatus status,
        @NotNull(message = "並べる順番（orderedIds）を指定してください")
        List<@NotNull(message = "並べる順番（orderedIds）に空の ID を含めないでください") Long> orderedIds) {
}
