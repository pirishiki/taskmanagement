package com.taskmanagement.backend.task;

import jakarta.validation.constraints.NotNull;

import java.util.List;

// 並び替え用：どの列（columnId）を、どの ID の順番に並べるか。どちらも省略できない
// versions は、orderedIds と同じ数・同じ順の版の配列（versions[i] が orderedIds[i] の版。docs/同時編集の設計書.md の 4.1 節）
// 省略（null）なら版を確かめない。数が orderedIds とちがえば 400（TaskController が確かめる）
public record ReorderRequest(
        @NotNull(message = "並べる列（columnId）を指定してください") Long columnId,
        @NotNull(message = "並べる順番（orderedIds）を指定してください")
        List<@NotNull(message = "並べる順番（orderedIds）に空の ID を含めないでください") Long> orderedIds,
        List<@NotNull(message = "版（versions）に空の値を含めないでください") Long> versions) {
}
