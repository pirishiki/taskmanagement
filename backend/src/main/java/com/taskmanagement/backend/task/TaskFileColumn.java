package com.taskmanagement.backend.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

// 書き出し・読み込みのファイル（TaskFile、version 2）に入れる、列1つの形
// 列の並び順は、ファイルの columns に書かれた順（左から）。タスクの並び順も、tasks に書かれた順（上から）
// id は入れない。読み込むときは、DB が新しい id を付ける
// done：「完了の列」の印。全部の列の中でちょうど1つだけ true にする（TaskService が確かめる）
// 書かなくてもよい（false になる）。boolean ではなく Boolean にしているのは、boolean だと
// 書かなかったときに JSON を読む時点で断られてしまうため。書かなかったとき（null）は、下で false に直す
public record TaskFileColumn(
        @NotBlank(message = "列の名前を入力してください")
        @Size(max = 255, message = "列の名前は255文字までにしてください") String name,
        Boolean done,
        @NotNull(message = "列のタスクの一覧（tasks）がありません")
        @Size(max = 10_000, message = "タスクは10000件までにしてください")
        List<@NotNull(message = "タスクの一覧（tasks）に空のものを含めないでください") @Valid TaskFileItem> tasks) {

    // 作るときに、done が書かれていなければ false にする（コンパクトコンストラクタ：record を作るときに必ず通る）
    public TaskFileColumn {
        if (done == null) {
            done = false;
        }
    }
}
