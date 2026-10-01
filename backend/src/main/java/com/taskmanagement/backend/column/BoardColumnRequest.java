package com.taskmanagement.backend.column;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 列を作るとき・名前を変えるときに、画面から送られてくる中身
// name は 255 文字まで（DB の name 列が varchar(255) のため）
public record BoardColumnRequest(
        @NotBlank(message = "列の名前を入力してください")
        @Size(max = 255, message = "列の名前は255文字までにしてください") String name) {
}
