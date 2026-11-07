package com.taskmanagement.backend.task;

import jakarta.validation.constraints.NotNull;

// カードを1枚動かす用：どの列（columnId）の、どのカード（prevId）のすぐ下に入れるか
// prevId は、画面で落とした位置のすぐ上に見えているカードの ID。一番上に落としたときは null（省略）
// columnId は省略できない。その番号の列が本当にあるかは TaskService が確かめる（なければ 400）
// version は、動かすカードの、画面が見ていた版（docs/同時編集の設計書.md の 4.1 節）。今の版とちがえば 409。省略なら確かめない
// prevId のカードの版は送らない（「Y のすぐ下に入れて」は、Y の位置が変わっていても意味が通るため。4.4 節）
public record MoveRequest(
        @NotNull(message = "移動先の列（columnId）を指定してください") Long columnId,
        Long prevId,
        Long version) {
}
