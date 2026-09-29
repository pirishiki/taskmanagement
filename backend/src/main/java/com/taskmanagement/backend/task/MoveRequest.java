package com.taskmanagement.backend.task;

import jakarta.validation.constraints.NotNull;

// カードを1枚動かす用：どの列（status）の、どのカード（prevId）のすぐ下に入れるか
// prevId は、画面で落とした位置のすぐ上に見えているカードの ID。一番上に落としたときは null（省略）
// status は省略できない。enum なので、決まった値以外は JSON を読む時点で断られる（400）
public record MoveRequest(
        @NotNull(message = "移動先の列（status）を指定してください") TaskStatus status,
        Long prevId) {
}
