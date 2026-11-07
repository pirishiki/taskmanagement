package com.taskmanagement.backend.column;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 列を作るとき・名前を変えるときに、画面から送られてくる中身
// name は 255 文字まで（DB の name 列が varchar(255) のため）
// version は、名前を変えるとき（PUT）に、見ていた版（docs/同時編集の設計書.md の 4.1 節）。今の版とちがえば 409
// 省略（null）なら確かめない。作るとき（POST）は書いても無視する（新しい列は必ず 0）
public record BoardColumnRequest(
        @NotBlank(message = "列の名前を入力してください")
        @Size(max = 255, message = "列の名前は255文字までにしてください") String name,
        Long version) {
}
