package com.taskmanagement.backend.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

// 書き出し・読み込みのファイル全体の形（JSON）
// 例：{"version": 1, "exportedAt": "2026-09-29T10:00:00", "tasks": [{"text": "牛乳を買う", ...}]}
// version：ファイルの形の版。あとで形を変えたとき、古いファイルか新しいファイルかを見分けるため
// exportedAt：いつ書き出したか。人が見るためのもので、読み込むときは使わない
// tasks の中身に付けた @Valid：タスク1件1件（TaskFileItem）のチェックも、まとめて行う
public record TaskFile(
        @NotNull(message = "ファイルの版（version）がありません") Integer version,
        LocalDateTime exportedAt,
        @NotNull(message = "タスクの一覧（tasks）がありません")
        @Size(max = 10_000, message = "タスクは10000件までにしてください")
        List<@NotNull(message = "タスクの一覧（tasks）に空のものを含めないでください") @Valid TaskFileItem> tasks) {

    // 今のファイルの形の版。読み込めるのは、この版のファイルだけ
    public static final int CURRENT_VERSION = 1;
}
