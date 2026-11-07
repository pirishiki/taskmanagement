package com.taskmanagement.backend.version;

import java.util.function.Supplier;

// 「画面が見ていた版（version）が、今の版とちがう」ことを表す例外（docs/同時編集の設計書.md の 4.2 節）
// ほかの画面で先に変更されているので、何も書き込まずに止める。GlobalExceptionHandler が 409 の返事
// （ProblemDetail に code: "VERSION_CONFLICT" と、あれば current）にする
// 投げるのは、書き込みのトランザクションの中（RuntimeException なので、その書き込みは全部取り消される）
public class VersionConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    // 返事の code。画面が、同じ 409 のほかの理由（タスクが入っている列は消せない、など）と見分けるための印
    public static final String CODE = "VERSION_CONFLICT";

    // 返事の detail（設計書 4.2 節の表の文）
    public static final String TASK = "ほかの画面で、このタスクが変更されています。最新の内容を確かめてから、もう一度操作してください";
    public static final String TASK_REORDER =
            "ほかの画面で、この列のタスクが変更されています。最新の状態を読み込んでから、もう一度並び替えてください";
    public static final String COLUMN = "ほかの画面で、この列が変更されています。最新の内容を確かめてから、もう一度操作してください";
    public static final String COLUMN_ORDER = "ほかの画面で、列が変更されています。最新の状態を読み込んでから、もう一度並べ替えてください";
    public static final String SAFETY_NET = "ほかの画面で変更されています。最新の状態を読み込んでから、もう一度操作してください";

    // そのタスク・列の今の状態（TaskResponse か BoardColumnResponse）。返事の current に入れる
    // 何枚ものカード・列を対象にする操作（並べ替え）では null（current を付けない）
    // 例外は、ふつうシリアライズ（バイト列にして保存・送信）しないので、transient にしておく
    private final transient Object current;

    public VersionConflictException(String detail, Object current) {
        super(detail);
        this.current = current;
    }

    public Object getCurrent() {
        return current;
    }

    // 画面が見ていた版（expected。null なら省略＝確かめない）と、今の版（actual）を比べて、ちがえば投げる
    // current は、投げるときにだけ作る（ふだんは作らない）ので、作り方を渡してもらう
    public static void check(Long expected, Long actual, String detail, Supplier<Object> current) {
        if (expected != null && !expected.equals(actual)) {
            throw new VersionConflictException(detail, current.get());
        }
    }
}
