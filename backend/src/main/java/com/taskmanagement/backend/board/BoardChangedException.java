package com.taskmanagement.backend.board;

// 「画面が覚えているボードの番号（ヘッダー Board-Id）が、今の番号とちがう」＝ DB が作り直されたことを表す例外
// GlobalExceptionHandler が 409 の返事（code: "BOARD_CHANGED"）にする（docs/同時編集の設計書.md の 5 節）
public class BoardChangedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    // 返事の code。画面は、これを見て「読み込み直す」の黄色の帯を出す
    public static final String CODE = "BOARD_CHANGED";

    public BoardChangedException() {
        super("データベースが作り直されています。画面を読み込み直してください");
    }
}
