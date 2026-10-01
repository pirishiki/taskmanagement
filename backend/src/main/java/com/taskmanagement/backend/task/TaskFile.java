package com.taskmanagement.backend.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

// 書き出し・読み込みのファイル全体の形（JSON）
// version：ファイルの形の版。形を変えたとき、古いファイルか新しいファイルかを見分けるため
// exportedAt：いつ書き出したか。人が見るためのもので、読み込むときは使わない
//
// 版ごとの形：
//   version 2（今の形）：列の一覧（columns）の中に、その列のタスクを入れる
//     例：{"version": 2, "exportedAt": "...",
//          "columns": [{"name": "やるべきこと", "fixed": true, "done": false, "tasks": [{"text": "牛乳を買う", ...}]}]}
//   version 1（#36 の形。読み込みだけできる）：タスクの一覧（tasks）に、列を status（todo・doing・done）で書く
//     例：{"version": 1, "exportedAt": "...", "tasks": [{"text": "牛乳を買う", "status": "todo", ...}]}
// 使わないほう（version 2 なら tasks）は null になる。@JsonInclude(NON_NULL) で、null の項目は書き出さない
// 一覧の中身に付けた @Valid：列1つ1つ・タスク1件1件のチェックも、まとめて行う
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskFile(
        @NotNull(message = "ファイルの版（version）がありません") Integer version,
        LocalDateTime exportedAt,
        @Size(max = 100, message = "列は100個までにしてください")
        List<@NotNull(message = "列の一覧（columns）に空のものを含めないでください") @Valid TaskFileColumn> columns,
        @Size(max = 10_000, message = "タスクは10000件までにしてください")
        List<@NotNull(message = "タスクの一覧（tasks）に空のものを含めないでください") @Valid TaskFileV1Item> tasks) {

    // 今のファイルの形の版。書き出すときは、この版で書く
    public static final int CURRENT_VERSION = 2;

    // 古い版。読み込むときだけ使う
    public static final int VERSION_1 = 1;
}
