package com.taskmanagement.backend.column;

import jakarta.validation.constraints.NotNull;

import java.util.List;

// 列を並べ替えるときに、画面から送られてくる中身：全部の列の ID を、左から並べたい順に並べたもの
public record BoardColumnOrderRequest(
        @NotNull(message = "並べる順番（orderedIds）を指定してください")
        List<@NotNull(message = "並べる順番（orderedIds）に空の ID を含めないでください") Long> orderedIds) {
}
