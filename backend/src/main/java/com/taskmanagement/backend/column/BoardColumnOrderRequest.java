package com.taskmanagement.backend.column;

import jakarta.validation.constraints.NotNull;

import java.util.List;

// 列を並べ替えるときに、画面から送られてくる中身：全部の列の ID を、左から並べたい順に並べたもの
// versions は、orderedIds と同じ数・同じ順の版の配列（docs/同時編集の設計書.md の 4.1 節）。省略なら版を確かめない
public record BoardColumnOrderRequest(
        @NotNull(message = "並べる順番（orderedIds）を指定してください")
        List<@NotNull(message = "並べる順番（orderedIds）に空の ID を含めないでください") Long> orderedIds,
        List<@NotNull(message = "版（versions）に空の値を含めないでください") Long> versions) {
}
